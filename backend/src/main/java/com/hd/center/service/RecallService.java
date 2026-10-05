package com.hd.center.service;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.*;
import com.hd.center.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 耗材批次召回核心：
 * 1) 发起召回 -> 冻结批次未使用库存、依据出库/使用流水列出受影响治疗记录、上报异常；
 * 2) 召回处理期间库房停止发放该批次（ConsumableService 出库拦截），未开始治疗提示/执行换批；
 * 3) 护士逐例回访；发现不适自动上报，医生从治疗记录查看当日机器、护士、耗材与透析参数并给复查结论；
 * 4) 办结做质量定性并把结果计入供应商考核；未使用库存退回厂家。
 */
@Service
@RequiredArgsConstructor
public class RecallService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<TxnType> OUTBOUND = List.of(TxnType.PRE_DRAW, TxnType.USE);
    private static final Set<SessionStatus> NOT_STARTED = EnumSet.of(SessionStatus.SCHEDULED, SessionStatus.ASSESSED);

    private final RecallOrderRepository recallRepo;
    private final RecallFollowUpRepository followRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ConsumableRepository consumableRepo;
    private final ConsumableTxnRepository txnRepo;
    private final SessionRepository sessionRepo;
    private final ExceptionEventRepository exceptionRepo;
    private final ConsumableService consumableService;
    private final ExceptionService exceptionService;

    /** 发起厂家召回 */
    @Transactional
    public Map<String, Object> initiate(Long consumableId, String batchNo, String reason,
                                        String manufacturerDocNo, String supplier, String operator) {
        Objects.requireNonNull(batchNo, "批号不能为空");
        Objects.requireNonNull(reason, "召回原因不能为空");
        Consumable consumable = consumableRepo.findById(consumableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "耗材不存在"));
        ConsumableBatch batch = batchRepo.findByConsumableIdAndBatchNo(consumableId, batchNo.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "耗材「" + consumable.getName() + "」不存在批号 " + batchNo + "，请先在批次台账核对"));
        if (recallRepo.existsByConsumableIdAndBatchNoAndStatusNot(consumableId, batchNo.trim(), RecallStatus.CLOSED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该批号已有进行中的召回单，不能重复发起");
        }

        RecallOrder recall = new RecallOrder();
        recall.setRecallNo(generateRecallNo());
        recall.setConsumableId(consumableId);
        recall.setConsumableCode(consumable.getCode());
        recall.setConsumableName(consumable.getName());
        recall.setBatchNo(batchNo.trim());
        recall.setSupplier(supplier != null && !supplier.isBlank() ? supplier
                : batch.getSupplier() != null ? batch.getSupplier() : consumable.getSupplier());
        recall.setProductionDate(batch.getProductionDate());
        recall.setExpiryDate(batch.getExpiryDate());
        recall.setManufacturerDocNo(manufacturerDocNo);
        recall.setReason(reason);
        recall.setCreatedBy(operator);
        recall.setStatus(RecallStatus.PROCESSING);

        // 1) 冻结未使用库存：冻结后任何出库（预领/使用/损耗）都会被 ConsumableService 拦截
        recall.setFrozenQty(batch.getStockQty());
        batch.setStatus(BatchStatus.FROZEN);
        batch.setRecallId(null); // 召回单先保存再回填
        batch = batchRepo.save(batch);
        recall = recallRepo.save(recall);
        batch.setRecallId(recall.getId());
        batchRepo.save(batch);

        // 2) 依据出库 + 患者使用流水定位受影响治疗记录（按治疗单聚合并扣除已归还数量）
        List<ConsumableTxn> outbound = txnRepo
                .findByBatchIdAndTypeInAndSessionIdIsNotNull(batch.getId(), OUTBOUND);
        Map<Long, Integer> netBySession = new LinkedHashMap<>();
        for (ConsumableTxn t : outbound) {
            netBySession.merge(t.getSessionId(), -t.getQty(), Integer::sum);
        }
        List<ConsumableTxn> returns = txnRepo.findByBatchIdOrderByCreatedAtDesc(batch.getId()).stream()
                .filter(t -> t.getType() == TxnType.RETURN && t.getSessionId() != null).toList();
        for (ConsumableTxn t : returns) {
            netBySession.merge(t.getSessionId(), -t.getQty(), Integer::sum);
        }

        Set<Long> patientIds = new LinkedHashSet<>();
        int upcoming = 0;
        for (Map.Entry<Long, Integer> e : netBySession.entrySet()) {
            int net = e.getValue();
            if (net <= 0) continue; // 已全部归还，未实际接触患者
            DialysisSession session = sessionRepo.findById(e.getKey()).orElse(null);
            if (session == null || session.getStatus() == SessionStatus.CANCELLED) continue;
            if (session.getStatus() == SessionStatus.ABSENT) {
                // 患者已请假、治疗未发生：预领耗材未使用，直接归还到冻结批次，随召回退回厂家，
                // 不产生回访单（患者未接触召回批次）
                consumableService.returnToBatch(consumable, batch, net, session.getId(),
                        session.getPatient().getName(), operator,
                        "召回单 " + recall.getRecallNo() + "：患者已请假未治疗，预领的召回批号耗材归还冻结批次");
                continue;
            }
            RecallFollowUp fu = new RecallFollowUp();
            fu.setRecallId(recall.getId());
            fu.setBatchNo(recall.getBatchNo());
            fu.setSessionId(session.getId());
            fu.setPatientId(session.getPatient().getId());
            fu.setPatientName(session.getPatient().getName());
            fu.setPatientPhone(session.getPatient().getPhone());
            fu.setSessionDate(session.getSessionDate());
            fu.setShift(session.getShift().name());
            fu.setMachineCode(session.getMachine().getCode());
            fu.setNurseName(session.getNurseName());
            fu.setUsedQty(net);
            fu.setSessionStatus(session.getStatus().name());
            followRepo.save(fu);
            patientIds.add(session.getPatient().getId());
            if (NOT_STARTED.contains(session.getStatus())
                    && !session.getSessionDate().isBefore(LocalDate.now())) {
                upcoming++;
            }
        }
        int affectedSessions = followRepo.findByRecallIdOrderBySessionDateDescIdDesc(recall.getId()).size();
        recall.setAffectedSessionCount(affectedSessions);
        // 请假未治疗患者的预领已归还到冻结批次，冻结量以批次最终在库数为准
        recall.setFrozenQty(batch.getStockQty());
        recall.setAffectedPatientCount(patientIds.size());
        recall = recallRepo.save(recall);
        batch = batchRepo.save(batch);

        // 3) 异常上报：召回总事件 + 每条待治疗记录提示更换批次
        exceptionService.raise(ExceptionType.CONSUMABLE_RECALL, null, null, null,
                "厂家召回：耗材「" + consumable.getName() + "」批号 " + recall.getBatchNo()
                        + "（召回单 " + recall.getRecallNo() + "），剩余库存 " + recall.getFrozenQty()
                        + " " + consumable.getUnit() + " 已冻结停发；受影响治疗记录 "
                        + affectedSessions
                        + " 条、患者 " + recall.getAffectedPatientCount()
                        + " 人，需护士逐例回访。" + reason, operator);
        if (upcoming > 0) {
            exceptionService.raise(ExceptionType.CONSUMABLE_RECALL, null, null, null,
                    "召回单 " + recall.getRecallNo() + "：有 " + upcoming
                            + " 条已排治疗预领了召回批号 " + recall.getBatchNo()
                            + "，库房停发，请到「批次召回」为其更换批次", operator);
        }
        return detail(recall.getId());
    }

    /** 召回单详情：聚合回访单、批次流水、可换批次库存 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long recallId) {
        RecallOrder recall = get(recallId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recall", recall);
        List<RecallFollowUp> followUps = followRepo.findByRecallIdOrderBySessionDateDescIdDesc(recallId);
        result.put("followUps", followUps);

        Map<String, Long> followStats = followUps.stream()
                .collect(Collectors.groupingBy(f -> f.getStatus().name(), LinkedHashMap::new, Collectors.counting()));
        result.put("followStats", followStats);
        long pendingReview = followUps.stream()
                .filter(f -> Boolean.TRUE.equals(f.getDiscomfort())
                        && f.getReviewResult() == ReviewResult.PENDING).count();
        result.put("pendingReview", pendingReview);

        ConsumableBatch batch = batchRepo
                .findByConsumableIdAndBatchNo(recall.getConsumableId(), recall.getBatchNo()).orElse(null);
        result.put("batch", batch);

        // 可用于更换的同耗材在库批次（FEFO）
        List<ConsumableBatch> replacements = batchRepo
                .findByConsumableIdOrderByExpiryDateAscIdAsc(recall.getConsumableId()).stream()
                .filter(b -> b.getStatus() == BatchStatus.ACTIVE && b.getStockQty() > 0
                        && !b.getBatchNo().equals(recall.getBatchNo()))
                .toList();
        result.put("replacementBatches", replacements);

        if (batch != null) {
            result.put("batchTxns", txnRepo.findByBatchIdOrderByCreatedAtDesc(batch.getId()));
        }
        result.put("canClose", canClose(recall, followUps));
        return result;
    }

    @Transactional(readOnly = true)
    public List<RecallOrder> list(RecallStatus status) {
        if (status != null) {
            return recallRepo.findByStatusOrderByCreatedAtDesc(status);
        }
        return recallRepo.findAllByOrderByCreatedAtDesc();
    }

    /** 护士回访 */
    @Transactional
    public RecallFollowUp followUp(Long recallId, Long followUpId, FollowUpStatus status,
                                   String note, String symptoms, String operator) {
        RecallFollowUp fu = getFollowUp(recallId, followUpId);
        if (status == FollowUpStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择回访结果");
        }
        fu.setStatus(status);
        fu.setContactedBy(operator);
        fu.setContactedAt(LocalDateTime.now());
        fu.setFollowNote(note);
        boolean discomfort = status == FollowUpStatus.DISCOMFORT;
        fu.setDiscomfort(discomfort);
        if (discomfort) {
            fu.setDiscomfortSymptoms(symptoms);
            RecallOrder recall = get(recallId);
            DialysisSession session = sessionRepo.findById(fu.getSessionId()).orElse(null);
            DialysisMachine machine = session == null ? null : session.getMachine();
            Patient patient = session == null ? null : session.getPatient();
            raiseDiscomfortException(fu, recall, session, machine, patient, symptoms, operator);
        } else {
            fu.setDiscomfortSymptoms(null);
        }
        fu = followRepo.save(fu);
        refreshStatus(recallId);
        return fu;
    }

    /** 医生复查结论（从治疗记录查看当日机器/护士/耗材/透析参数后给出） */
    @Transactional
    public RecallFollowUp review(Long recallId, Long followUpId, ReviewResult result,
                                 String recheckPlan, String reviewNote, String doctor) {
        RecallFollowUp fu = getFollowUp(recallId, followUpId);
        if (result == ReviewResult.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择复查结论");
        }
        fu.setReviewResult(result);
        fu.setRecheckPlan(recheckPlan);
        fu.setReviewNote(reviewNote);
        fu.setReviewedBy(doctor);
        fu.setReviewedAt(LocalDateTime.now());
        return followRepo.save(fu);
    }

    /**
     * 为未开始的治疗单一键更换批次：召回批号预领数量退回冻结批次，从可用批次重新预领。
     */
    @Transactional
    public RecallFollowUp replaceBatch(Long recallId, Long followUpId, Long replacementBatchId, String operator) {
        RecallOrder recall = get(recallId);
        RecallFollowUp fu = getFollowUp(recallId, followUpId);
        if (recall.getStatus() == RecallStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "召回已办结，不能再换批");
        }
        DialysisSession session = sessionRepo.findById(fu.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
        if (!NOT_STARTED.contains(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该治疗已开始或完成，不能更换批次，按回访/复查流程处理");
        }
        Consumable recalled = consumableRepo.findById(recall.getConsumableId()).orElseThrow();
        ConsumableBatch oldBatch = batchRepo
                .findByConsumableIdAndBatchNo(recall.getConsumableId(), recall.getBatchNo()).orElseThrow();
        ConsumableBatch newBatch = batchRepo.findById(replacementBatchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "更换批次不存在"));
        if (!newBatch.getConsumableId().equals(recall.getConsumableId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更换批次必须是同一耗材");
        }
        if (newBatch.getStatus() != BatchStatus.ACTIVE || newBatch.getStockQty() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "批次 " + newBatch.getBatchNo() + " 不可用或无库存");
        }
        int qty = fu.getUsedQty();

        // 退回召回批次（保持冻结，随后续召回退回厂家），再从新批次出库预领
        consumableService.returnToBatch(recalled, oldBatch, qty, session.getId(),
                session.getPatient().getName(), operator,
                "召回单 " + recall.getRecallNo() + " 更换批次，退回召回批号 " + oldBatch.getBatchNo());
        consumableService.outboundBatch(recalled, newBatch, TxnType.PRE_DRAW, qty, session.getId(),
                session.getPatient().getName(), operator,
                "召回单 " + recall.getRecallNo() + " 换批：" + oldBatch.getBatchNo() + " → " + newBatch.getBatchNo());

        fu.setBatchReplaced(true);
        fu.setReplacementBatchNo(newBatch.getBatchNo());
        fu.setReplacedBy(operator);
        fu.setReplacedAt(LocalDateTime.now());
        return followRepo.save(fu);
    }

    /** 办结召回：质量定性 + 供应商考核扣分，未使用库存退回厂家 */
    @Transactional
    public RecallOrder close(Long recallId, QualityVerdict verdict, Integer deduction,
                             boolean returnUnused, String closeNote, String operator) {
        RecallOrder recall = get(recallId);
        if (recall.getStatus() == RecallStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "召回单已办结");
        }
        List<RecallFollowUp> followUps = followRepo.findByRecallIdOrderBySessionDateDescIdDesc(recallId);
        if (!canClose(recall, followUps)) {
            long pending = followUps.stream().filter(f -> f.getStatus() == FollowUpStatus.PENDING).count();
            long pendingReview = followUps.stream()
                    .filter(f -> Boolean.TRUE.equals(f.getDiscomfort()) && f.getReviewResult() == ReviewResult.PENDING)
                    .count();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "尚有 " + pending + " 例未回访、" + pendingReview + " 例不适未复查，不能办结");
        }
        ConsumableBatch batch = batchRepo
                .findByConsumableIdAndBatchNo(recall.getConsumableId(), recall.getBatchNo()).orElse(null);
        Consumable consumable = consumableRepo.findById(recall.getConsumableId()).orElse(null);
        if (returnUnused && batch != null && consumable != null && batch.getStockQty() > 0) {
            consumableService.recallOut(consumable, batch, recall.getRecallNo(), operator, closeNote);
            recall.setReturnedQty(recall.getFrozenQty());
        } else if (batch != null && batch.getStockQty() == 0) {
            batch.setStatus(BatchStatus.DEPLETED);
            batchRepo.save(batch);
        }
        if (batch != null) {
            batch.setRecallId(null);
            batchRepo.save(batch);
        }

        recall.setQualityVerdict(verdict);
        recall.setSupplierDeduction(deduction == null ? defaultDeduction(verdict) : deduction);
        recall.setCloseNote(closeNote);
        recall.setClosedBy(operator);
        recall.setClosedAt(LocalDateTime.now());
        recall.setStatus(RecallStatus.CLOSED);
        recall = recallRepo.save(recall);

        // 关联异常闭环
        final RecallOrder savedRecall = recall;
        final QualityVerdict finalVerdict = verdict;
        String keyword = savedRecall.getRecallNo();
        exceptionRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(e -> (e.getType() == ExceptionType.CONSUMABLE_RECALL
                        || e.getType() == ExceptionType.RECALL_DISCOMFORT)
                        && e.getStatus() != ExceptionStatus.RESOLVED
                        && e.getDescription() != null && e.getDescription().contains(keyword))
                .forEach(e -> {
                    e.setStatus(ExceptionStatus.RESOLVED);
                    e.setHandlerName(operator);
                    e.setHandleNote("召回单 " + savedRecall.getRecallNo() + " 已办结，质量定性："
                            + verdictLabel(finalVerdict) + "，供应商考核扣分 " + savedRecall.getSupplierDeduction());
                    e.setResolvedAt(LocalDateTime.now());
                    exceptionRepo.save(e);
                });
        return recall;
    }

    /**
     * 供应商考核：按供应商汇总已办结召回——召回次数、确认质量问题次数、累计扣分、考核得分（基础 100）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> supplierAssessment() {
        List<RecallOrder> closed = recallRepo.findByStatusOrderByCreatedAtDesc(RecallStatus.CLOSED);
        Map<String, List<RecallOrder>> bySupplier = closed.stream()
                .collect(Collectors.groupingBy(r -> r.getSupplier() == null ? "未登记供应商" : r.getSupplier(),
                        LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> rows = new ArrayList<>();
        bySupplier.forEach((supplier, recalls) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplier", supplier);
            row.put("recallCount", recalls.size());
            row.put("confirmedDefectCount", recalls.stream()
                    .filter(r -> r.getQualityVerdict() == QualityVerdict.CONFIRMED_DEFECT).count());
            int totalDeduction = recalls.stream().mapToInt(RecallOrder::getSupplierDeduction).sum();
            row.put("totalDeduction", totalDeduction);
            row.put("score", Math.max(0, 100 - totalDeduction));
            row.put("affectedSessionCount", recalls.stream().mapToInt(RecallOrder::getAffectedSessionCount).sum());
            row.put("affectedPatientCount", recalls.stream().mapToInt(RecallOrder::getAffectedPatientCount).sum());
            row.put("lastClosedAt", recalls.stream().map(RecallOrder::getClosedAt)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
            rows.add(row);
        });
        rows.sort(Comparator.comparingInt(m -> (int) m.get("score")));
        return rows;
    }

    /**
     * 排班看板/治疗单召回预警：某日治疗单中预领了进行中召回批次且尚未换批的。
     * 返回 sessionId -> 预警明细列表。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Map<String, Object>>> warningsForDate(LocalDate date) {
        List<RecallOrder> active = new ArrayList<>();
        active.addAll(recallRepo.findByStatusNotOrderByCreatedAtDesc(RecallStatus.CLOSED));
        if (active.isEmpty()) return Map.of();
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (RecallOrder recall : active) {
            ConsumableBatch batch = batchRepo
                    .findByConsumableIdAndBatchNo(recall.getConsumableId(), recall.getBatchNo()).orElse(null);
            if (batch == null) continue;
            Set<Long> sessionIds = txnRepo.findByBatchIdAndTypeInAndSessionIdIsNotNull(batch.getId(), OUTBOUND)
                    .stream().map(ConsumableTxn::getSessionId).collect(Collectors.toSet());
            for (Long sid : sessionIds) {
                RecallFollowUp fu = followRepo.findByRecallIdAndSessionId(recall.getId(), sid).orElse(null);
                if (fu == null || Boolean.TRUE.equals(fu.getBatchReplaced())) continue;
                DialysisSession s = sessionRepo.findById(sid).orElse(null);
                if (s == null || !s.getSessionDate().equals(date)) continue;
                if (!NOT_STARTED.contains(s.getStatus())) continue;
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("recallId", recall.getId());
                w.put("recallNo", recall.getRecallNo());
                w.put("consumableName", recall.getConsumableName());
                w.put("batchNo", recall.getBatchNo());
                w.put("usedQty", fu.getUsedQty());
                w.put("followUpId", fu.getId());
                result.computeIfAbsent(sid, k -> new ArrayList<>()).add(w);
            }
        }
        return result;
    }

    private boolean canClose(RecallOrder recall, List<RecallFollowUp> followUps) {
        if (recall.getStatus() == RecallStatus.CLOSED) return false;
        boolean allContacted = followUps.stream().noneMatch(f -> f.getStatus() == FollowUpStatus.PENDING);
        boolean discomfortReviewed = followUps.stream()
                .filter(f -> Boolean.TRUE.equals(f.getDiscomfort()))
                .allMatch(f -> f.getReviewResult() != ReviewResult.PENDING);
        return allContacted && discomfortReviewed;
    }

    /** 回访完成后推进召回单状态：全部回访完 -> 等待医生复查/办结 */
    private void refreshStatus(Long recallId) {
        RecallOrder recall = get(recallId);
        if (recall.getStatus() == RecallStatus.CLOSED) return;
        List<RecallFollowUp> all = followRepo.findByRecallIdOrderBySessionDateDescIdDesc(recallId);
        boolean allContacted = all.stream().noneMatch(f -> f.getStatus() == FollowUpStatus.PENDING);
        recall.setStatus(allContacted ? RecallStatus.AWAIT_REVIEW : RecallStatus.PROCESSING);
        recallRepo.save(recall);
    }

    private void raiseDiscomfortException(RecallFollowUp fu, RecallOrder recall, DialysisSession session,
                                          DialysisMachine machine, Patient patient, String symptoms, String operator) {
        String treatmentDay = session == null ? String.valueOf(fu.getSessionDate())
                : session.getSessionDate() + " " + (session.getShift() == null ? fu.getShift() : session.getShift());
        exceptionService.raise(ExceptionType.RECALL_DISCOMFORT, patient, session, machine,
                "召回回访发现患者「" + fu.getPatientName() + "」在 " + treatmentDay
                        + " 使用批号 " + recall.getBatchNo() + " 后报告不适："
                        + (symptoms == null || symptoms.isBlank() ? "(未描述症状)" : symptoms)
                        + "。请医生从治疗记录查看当日机器、护士、耗材与透析参数，判断是否安排复查"
                        + "（机位 " + fu.getMachineCode() + "，责任护士 "
                        + (fu.getNurseName() == null ? "-" : fu.getNurseName()) + "）", operator);
    }

    private RecallOrder get(Long id) {
        return recallRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "召回单不存在"));
    }

    private RecallFollowUp getFollowUp(Long recallId, Long followUpId) {
        RecallFollowUp fu = followRepo.findById(followUpId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "回访记录不存在"));
        if (!fu.getRecallId().equals(recallId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回访记录与召回单不匹配");
        }
        return fu;
    }

    private String generateRecallNo() {
        String prefix = "RC" + LocalDate.now().format(NO_FMT) + "-";
        long count = recallRepo.count() + 1;
        return prefix + String.format("%03d", count);
    }

    static int defaultDeduction(QualityVerdict verdict) {
        return switch (verdict) {
            case CONFIRMED_DEFECT -> 20;
            case SUSPECTED -> 8;
            case INCONCLUSIVE -> 3;
            case QUALIFIED -> 0;
        };
    }

    static String verdictLabel(QualityVerdict v) {
        return switch (v) {
            case CONFIRMED_DEFECT -> "确认质量缺陷";
            case SUSPECTED -> "疑似质量问题";
            case QUALIFIED -> "复检合格";
            case INCONCLUSIVE -> "无法判定";
        };
    }
}
