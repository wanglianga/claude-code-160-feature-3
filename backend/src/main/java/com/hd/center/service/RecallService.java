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
 * 耗材批次召回：厂家召回 -> 冻结库存 -> 列出受影响治疗记录 -> 护士回访 / 医生复查 ->
 * 已排班治疗更换批次 -> 关闭召回（退回厂家或解除冻结）-> 结果进入供应商考核。
 */
@Service
@RequiredArgsConstructor
public class RecallService {

    private final RecallEventRepository recallRepo;
    private final RecallFollowupRepository followupRepo;
    private final ConsumableBatchRepository batchRepo;
    private final ConsumableTxnRepository txnRepo;
    private final ConsumableRepository consumableRepo;
    private final SessionRepository sessionRepo;
    private final ConsumableService consumableService;
    private final ExceptionService exceptionService;

    private static final List<RecallStatus> ACTIVE = List.of(RecallStatus.OPEN, RecallStatus.PROCESSING);
    /** 患者已暴露于耗材的治疗状态：需要回访 */
    private static final List<SessionStatus> EXPOSED = List.of(
            SessionStatus.ON_DIALYSIS, SessionStatus.OFF_DIALYSIS, SessionStatus.FINISHED);
    /** 尚未上机的治疗状态：需要更换批次 */
    private static final List<SessionStatus> REPLACEABLE = List.of(
            SessionStatus.SCHEDULED, SessionStatus.ASSESSED);

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return recallRepo.findAllByOrderByCreatedAtDesc().stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        RecallEvent recall = get(id);
        Map<String, Object> result = new LinkedHashMap<>(summary(recall));
        List<RecallFollowup> followups = followupRepo.findByRecallIdOrderById(id);
        result.put("followups", followups);
        result.put("pendingReplacement", pendingReplacement(recall));
        result.put("stats", statsOf(recall, followups));
        return result;
    }

    private Map<String, Object> summary(RecallEvent recall) {
        ConsumableBatch batch = recall.getBatch();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", recall.getId());
        m.put("recallNo", recall.getRecallNo());
        m.put("batchId", batch.getId());
        m.put("batchNo", batch.getBatchNo());
        m.put("consumableId", batch.getConsumable().getId());
        m.put("consumableName", batch.getConsumable().getName());
        m.put("supplier", batch.getSupplier());
        m.put("reason", recall.getReason());
        m.put("status", recall.getStatus().name());
        m.put("outcome", recall.getOutcome() == null ? null : recall.getOutcome().name());
        m.put("conclusion", recall.getConclusion());
        m.put("createdBy", recall.getCreatedBy());
        m.put("createdAt", recall.getCreatedAt());
        m.put("closedBy", recall.getClosedBy());
        m.put("closedAt", recall.getClosedAt());
        m.put("batchStatus", batch.getStatus().name());
        m.put("frozenRemaining", Math.max(0, consumableService.batchRemaining(batch.getId())));
        List<RecallFollowup> followups = followupRepo.findByRecallIdOrderById(recall.getId());
        m.put("followupTotal", followups.size());
        m.put("followupDone", followups.stream().filter(f -> f.getStatus() != FollowupStatus.PENDING).count());
        m.put("discomfortCount", followups.stream().filter(f -> Boolean.TRUE.equals(f.getDiscomfort())).count());
        return m;
    }

    private Map<String, Object> statsOf(RecallEvent recall, List<RecallFollowup> followups) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("followupTotal", followups.size());
        m.put("followupDone", followups.stream().filter(f -> f.getStatus() != FollowupStatus.PENDING).count());
        m.put("discomfortCount", followups.stream().filter(f -> Boolean.TRUE.equals(f.getDiscomfort())).count());
        m.put("reviewNeededCount", followups.stream().filter(f -> Boolean.TRUE.equals(f.getReviewNeeded())).count());
        m.put("pendingReplacementCount", pendingReplacement(recall).size());
        return m;
    }

    /**
     * 待更换批次的治疗单：已排班/已评估且预领了召回批次、尚未退回更换的。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingReplacement(RecallEvent recall) {
        Long batchId = recall.getBatch().getId();
        // 该批次全部预领/归还流水按治疗单归集， outstanding = 预领量 - 归还量 > 0
        Map<Long, Integer> outstandingBySession = new LinkedHashMap<>();
        for (ConsumableTxn t : txnRepo.findByBatchId(batchId)) {
            if (t.getSessionId() == null) continue;
            if (t.getType() == TxnType.PRE_DRAW) {
                outstandingBySession.merge(t.getSessionId(), -t.getQty(), Integer::sum);
            } else if (t.getType() == TxnType.RETURN) {
                outstandingBySession.merge(t.getSessionId(), -t.getQty(), Integer::sum);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        outstandingBySession.forEach((sessionId, outstanding) -> {
            if (outstanding <= 0) return;
            DialysisSession s = sessionRepo.findById(sessionId).orElse(null);
            if (s == null || !REPLACEABLE.contains(s.getStatus())) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sessionId", s.getId());
            m.put("patientName", s.getPatient().getName());
            m.put("sessionDate", s.getSessionDate());
            m.put("shift", s.getShift().name());
            m.put("machineCode", s.getMachine().getCode());
            m.put("status", s.getStatus().name());
            m.put("outstandingQty", outstanding);
            result.add(m);
        });
        result.sort(Comparator.comparing(m -> m.get("sessionDate").toString()));
        return result;
    }

    // ---------- 发起召回 ----------

    /**
     * 发起召回：冻结批次库存，按出库/使用记录生成受影响治疗的回访任务并上报异常。
     */
    @Transactional
    public Map<String, Object> create(Long batchId, String reason, String operator) {
        ConsumableBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批次不存在"));
        if (batch.getStatus() == BatchStatus.RECALLED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该批次已召回关闭，不能重复召回");
        }
        if (recallRepo.existsByBatchIdAndStatusIn(batchId, ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该批次已有进行中的召回单");
        }
        // 冻结未使用库存
        batch.setStatus(BatchStatus.FROZEN);
        batchRepo.save(batch);

        RecallEvent recall = new RecallEvent();
        recall.setRecallNo(nextRecallNo());
        recall.setBatch(batch);
        recall.setReason(reason);
        recall.setCreatedBy(operator);
        recall = recallRepo.save(recall);

        // 根据出库（预领/使用）记录列出受影响治疗记录
        Set<Long> sessionIds = txnRepo.findByBatchId(batchId).stream()
                .filter(t -> (t.getType() == TxnType.PRE_DRAW || t.getType() == TxnType.USE) && t.getQty() < 0)
                .map(ConsumableTxn::getSessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int followupCount = 0;
        for (Long sessionId : sessionIds) {
            DialysisSession s = sessionRepo.findById(sessionId).orElse(null);
            if (s == null || !EXPOSED.contains(s.getStatus())) continue;
            RecallFollowup f = new RecallFollowup();
            f.setRecall(recall);
            f.setSessionId(s.getId());
            f.setPatientId(s.getPatient().getId());
            f.setPatientName(s.getPatient().getName());
            f.setSessionDate(s.getSessionDate());
            f.setShift(s.getShift());
            followupRepo.save(f);
            followupCount++;
        }

        int frozen = Math.max(0, consumableService.batchRemaining(batchId));
        exceptionService.raise(ExceptionType.RECALL, null, null, null,
                "耗材召回：「" + batch.getConsumable().getName() + "」批次 " + batch.getBatchNo()
                        + "（供应商 " + batch.getSupplier() + "）被厂家召回：" + reason
                        + "。已冻结库存 " + frozen + "，涉及 " + followupCount + " 条已治疗记录待回访。",
                operator);
        return detail(recall.getId());
    }

    // ---------- 回访与复查 ----------

    /** 护士回访登记 */
    @Transactional
    public RecallFollowup followup(Long followupId, FollowupStatus status, Boolean discomfort,
                                   String symptoms, String note, String nurse) {
        RecallFollowup f = getFollowup(followupId);
        ensureActive(f.getRecall());
        f.setStatus(status);
        f.setNurseName(nurse);
        f.setFollowupAt(LocalDateTime.now());
        if (status == FollowupStatus.DONE) {
            f.setDiscomfort(Boolean.TRUE.equals(discomfort));
            f.setSymptoms(symptoms);
            f.setFollowupNote(note);
            if (Boolean.TRUE.equals(discomfort)) {
                // 回访发现不适：上报异常，医生可从治疗记录追溯当日机器/护士/耗材/透析参数
                DialysisSession session = f.getSessionId() == null ? null
                        : sessionRepo.findById(f.getSessionId()).orElse(null);
                exceptionService.raise(ExceptionType.RECALL_DISCOMFORT,
                        session != null ? session.getPatient() : null, session,
                        session != null ? session.getMachine() : null,
                        "召回回访发现患者「" + f.getPatientName() + "」不适：" + symptoms
                                + "（召回单 " + f.getRecall().getRecallNo() + "，请医生评估是否复查）", nurse);
            }
        }
        touchProcessing(f.getRecall());
        return followupRepo.save(f);
    }

    /** 医生复查评估 */
    @Transactional
    public RecallFollowup review(Long followupId, Boolean reviewNeeded, String reviewNote, String doctor) {
        RecallFollowup f = getFollowup(followupId);
        ensureActive(f.getRecall());
        f.setReviewNeeded(Boolean.TRUE.equals(reviewNeeded));
        f.setReviewNote(reviewNote);
        f.setDoctorName(doctor);
        f.setReviewAt(LocalDateTime.now());
        touchProcessing(f.getRecall());
        return followupRepo.save(f);
    }

    // ---------- 更换批次 ----------

    /** 为一条待治疗单更换批次：召回批次预领退回冻结库存，重新从正常批次预领 */
    @Transactional
    public Map<String, Object> replaceBatch(Long recallId, Long sessionId, String operator) {
        RecallEvent recall = get(recallId);
        ensureActive(recall);
        DialysisSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
        if (!REPLACEABLE.contains(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "治疗已开始或结束，无需更换批次");
        }
        ConsumableBatch batch = recall.getBatch();
        Consumable consumable = batch.getConsumable();
        int outstanding = txnRepo.findByBatchIdAndSessionId(batch.getId(), sessionId).stream()
                .filter(t -> t.getType() == TxnType.PRE_DRAW || t.getType() == TxnType.RETURN)
                .mapToInt(t -> -t.getQty())
                .sum();
        if (outstanding <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该治疗单无需更换的召回批次耗材");
        }
        String patientName = session.getPatient().getName();
        // 1. 召回批次预领退回（回到冻结库存）
        consumableService.transactBatch(consumable, TxnType.RETURN, outstanding, sessionId, patientName,
                operator, "召回退回预领（批次 " + batch.getBatchNo() + "，召回单 " + recall.getRecallNo() + "）",
                batch.getId());
        // 2. 从正常批次重新预领（无可用批次时整体回滚并提示）
        consumableService.transactBatch(consumable, TxnType.PRE_DRAW, outstanding, sessionId, patientName,
                operator, "召回更换批次重新预领（召回单 " + recall.getRecallNo() + "）", null);
        touchProcessing(recall);
        return detail(recallId);
    }

    /** 一键为全部待更换治疗单更换批次；无可用批次的治疗单跳过并列出 */
    @Transactional
    public Map<String, Object> replaceAll(Long recallId, String operator) {
        RecallEvent recall = get(recallId);
        ensureActive(recall);
        List<Long> sessionIds = pendingReplacement(recall).stream()
                .map(m -> ((Number) m.get("sessionId")).longValue()).toList();
        List<String> failed = new ArrayList<>();
        int done = 0;
        for (Long sessionId : sessionIds) {
            try {
                replaceBatch(recallId, sessionId, operator);
                done++;
            } catch (ResponseStatusException ex) {
                DialysisSession s = sessionRepo.findById(sessionId).orElse(null);
                failed.add((s != null ? s.getPatient().getName() : "治疗单#" + sessionId) + "：" + ex.getReason());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(detail(recallId));
        result.put("replacedCount", done);
        result.put("replaceFailed", failed);
        return result;
    }

    // ---------- 关闭召回 ----------

    /**
     * 关闭召回：CONFIRMED 确认召回（剩余冻结库存退回厂家，批次转「已召回」）；
     * LIFTED 解除冻结（批次恢复「正常」可继续发放）。处理结果进入供应商考核。
     */
    @Transactional
    public Map<String, Object> close(Long recallId, RecallOutcome outcome, String conclusion, String operator) {
        RecallEvent recall = get(recallId);
        ensureActive(recall);
        ConsumableBatch batch = recall.getBatch();
        int returned = 0;
        if (outcome == RecallOutcome.CONFIRMED) {
            int remaining = Math.max(0, consumableService.batchRemaining(batch.getId()));
            batch.setStatus(BatchStatus.RECALLED);
            batchRepo.save(batch);
            if (remaining > 0) {
                consumableService.transactBatch(batch.getConsumable(), TxnType.SUPPLIER_RETURN, remaining,
                        null, null, operator,
                        "召回退回厂家（召回单 " + recall.getRecallNo() + "）", batch.getId());
                returned = remaining;
            }
        } else {
            batch.setStatus(BatchStatus.NORMAL);
            batchRepo.save(batch);
        }
        recall.setStatus(RecallStatus.CLOSED);
        recall.setOutcome(outcome);
        recall.setConclusion(conclusion);
        recall.setClosedBy(operator);
        recall.setClosedAt(LocalDateTime.now());
        recallRepo.save(recall);

        Map<String, Object> result = new LinkedHashMap<>(detail(recallId));
        result.put("returnedToSupplier", returned);
        long pendingFollowups = followupRepo.countByRecallIdAndStatusNot(recallId, FollowupStatus.DONE);
        result.put("unfinishedFollowups", pendingFollowups);
        return result;
    }

    // ---------- 供应商考核 ----------

    /**
     * 供应商耗材质量考核：按供应商汇总批次、召回、回访不适与处理结果，
     * 质量分 = 100 - 20×确认召回次数 - 5×回访不适例数（下限 0）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> supplierStats() {
        Map<String, List<ConsumableBatch>> bySupplier = batchRepo.findAll().stream()
                .collect(Collectors.groupingBy(ConsumableBatch::getSupplier, LinkedHashMap::new, Collectors.toList()));
        List<RecallEvent> allRecalls = recallRepo.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        bySupplier.forEach((supplier, batches) -> {
            Set<Long> batchIds = batches.stream().map(ConsumableBatch::getId).collect(Collectors.toSet());
            List<RecallEvent> recalls = allRecalls.stream()
                    .filter(r -> batchIds.contains(r.getBatch().getId())).toList();
            List<RecallEvent> confirmed = recalls.stream()
                    .filter(r -> r.getOutcome() == RecallOutcome.CONFIRMED).toList();
            List<RecallFollowup> followups = recalls.stream()
                    .flatMap(r -> followupRepo.findByRecallIdOrderById(r.getId()).stream()).toList();
            long discomfort = followups.stream().filter(f -> Boolean.TRUE.equals(f.getDiscomfort())).count();
            long reviewNeeded = followups.stream().filter(f -> Boolean.TRUE.equals(f.getReviewNeeded())).count();
            int score = (int) Math.max(0, 100 - 20L * confirmed.size() - 5L * discomfort);

            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("supplier", supplier);
            m.put("batchCount", batches.size());
            m.put("recalledBatchCount", batches.stream().filter(b -> b.getStatus() == BatchStatus.RECALLED).count());
            m.put("frozenBatchCount", batches.stream().filter(b -> b.getStatus() == BatchStatus.FROZEN).count());
            m.put("recallTotal", recalls.size());
            m.put("confirmedRecallCount", confirmed.size());
            m.put("activeRecallCount", recalls.stream().filter(r -> ACTIVE.contains(r.getStatus())).count());
            m.put("followupTotal", followups.size());
            m.put("discomfortCount", discomfort);
            m.put("reviewNeededCount", reviewNeeded);
            m.put("qualityScore", score);
            m.put("grade", score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : "D");
            // 处理结果（考核依据）：已关闭召回的结论
            m.put("closedRecalls", recalls.stream()
                    .filter(r -> r.getStatus() == RecallStatus.CLOSED)
                    .map(r -> {
                        Map<String, Object> c = new LinkedHashMap<String, Object>();
                        c.put("recallNo", r.getRecallNo());
                        c.put("batchNo", r.getBatch().getBatchNo());
                        c.put("consumableName", r.getBatch().getConsumable().getName());
                        c.put("outcome", r.getOutcome() == null ? null : r.getOutcome().name());
                        c.put("conclusion", r.getConclusion());
                        c.put("closedAt", r.getClosedAt());
                        return c;
                    }).toList());
            result.add(m);
        });
        result.sort(Comparator.comparingInt(m -> ((Number) m.get("qualityScore")).intValue()));
        return result;
    }

    // ---------- 内部 ----------

    private String nextRecallNo() {
        String prefix = "RC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long seq = recallRepo.count() + 1;
        String no;
        do {
            no = prefix + "-" + String.format("%03d", seq++);
        } while (recallRepo.existsByRecallNo(no));
        return no;
    }

    private void touchProcessing(RecallEvent recall) {
        if (recall.getStatus() == RecallStatus.OPEN) {
            recall.setStatus(RecallStatus.PROCESSING);
            recallRepo.save(recall);
        }
    }

    private void ensureActive(RecallEvent recall) {
        if (!ACTIVE.contains(recall.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "召回单已关闭，不能继续操作");
        }
    }

    private RecallEvent get(Long id) {
        return recallRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "召回单不存在"));
    }

    private RecallFollowup getFollowup(Long id) {
        return followupRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "回访任务不存在"));
    }
}
