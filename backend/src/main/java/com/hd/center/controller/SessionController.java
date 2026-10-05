package com.hd.center.controller;

import com.hd.center.config.CurrentUser;
import com.hd.center.entity.*;
import com.hd.center.entity.Enums.*;
import com.hd.center.repository.*;
import com.hd.center.service.ConsumableService;
import com.hd.center.service.ExceptionService;
import com.hd.center.service.SchedulingService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排班看板与治疗单主流程。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionRepository sessionRepo;
    private final MachineRepository machineRepo;
    private final PatientRepository patientRepo;
    private final PreAssessmentRepository preRepo;
    private final IntraRecordRepository intraRepo;
    private final PostRecordRepository postRepo;
    private final ExceptionEventRepository exceptionRepo;
    private final BillingRecordRepository billingRepo;
    private final ConsumableTxnRepository txnRepo;
    private final DisinfectionRecordRepository disinfectionRepo;
    private final SchedulingService schedulingService;
    private final ConsumableService consumableService;
    private final ExceptionService exceptionService;
    private final ConsumableRepository consumableRepo;
    private final ConsumableBatchRepository batchRepo;
    private final RecallFollowupRepository followupRepo;

    /** 看板：某日某班次的机位与治疗单；recallAlerts 提示预领了冻结/召回批次、需更换批次的治疗单 */
    @GetMapping("/board")
    public Map<String, Object> board(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam Shift shift) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("machines", machineRepo.findByActiveTrueOrderByCode());
        List<DialysisSession> sessions = sessionRepo.findBySessionDateAndShiftOrderBySeqNo(date, shift);
        result.put("sessions", sessions);
        result.put("recallAlerts", recallAlerts(sessions));
        return result;
    }

    /** 已排班治疗中仍持有冻结/召回批次预领耗材的提示（自动提示更换批次） */
    private List<Map<String, Object>> recallAlerts(List<DialysisSession> sessions) {
        List<Long> sessionIds = sessions.stream()
                .filter(s -> s.getStatus() == SessionStatus.SCHEDULED || s.getStatus() == SessionStatus.ASSESSED)
                .map(DialysisSession::getId).toList();
        List<Map<String, Object>> alerts = new ArrayList<>();
        if (sessionIds.isEmpty()) {
            return alerts;
        }
        // 预领为负、归还为正：按 治疗单+批次 计算未更换的冻结/召回批次余量
        Map<String, Integer> outstanding = new LinkedHashMap<>();
        Map<String, ConsumableTxn> sample = new LinkedHashMap<>();
        List<ConsumableTxn> txns = new ArrayList<>();
        txns.addAll(txnRepo.findByTypeAndSessionIdIn(TxnType.PRE_DRAW, sessionIds));
        txns.addAll(txnRepo.findByTypeAndSessionIdIn(TxnType.RETURN, sessionIds));
        Set<Long> batchIds = new HashSet<>();
        for (ConsumableTxn t : txns) {
            if (t.getBatchId() == null) continue;
            String key = t.getSessionId() + "@" + t.getBatchId();
            outstanding.merge(key, -t.getQty(), Integer::sum);
            sample.putIfAbsent(key, t);
            batchIds.add(t.getBatchId());
        }
        Map<Long, ConsumableBatch> batches = new HashMap<>();
        batchRepo.findAllById(batchIds).forEach(b -> batches.put(b.getId(), b));
        Map<Long, DialysisSession> sessionMap = new HashMap<>();
        sessions.forEach(s -> sessionMap.put(s.getId(), s));
        outstanding.forEach((key, qty) -> {
            if (qty <= 0) return;
            ConsumableTxn t = sample.get(key);
            ConsumableBatch batch = batches.get(t.getBatchId());
            if (batch == null || batch.getStatus() == BatchStatus.NORMAL) return;
            DialysisSession s = sessionMap.get(t.getSessionId());
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("sessionId", t.getSessionId());
            m.put("patientName", s != null ? s.getPatient().getName() : t.getPatientName());
            m.put("consumableName", t.getConsumableName());
            m.put("batchNo", batch.getBatchNo());
            m.put("batchStatus", batch.getStatus().name());
            m.put("qty", qty);
            alerts.add(m);
        });
        return alerts;
    }

    public record GenerateRequest(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @NotNull Shift shift, boolean ignorePattern) {}

    /** 自动排班 */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody GenerateRequest request,
                                        @AuthenticationPrincipal CurrentUser current) {
        return schedulingService.generate(request.date(), request.shift(), request.ignorePattern(), current.name());
    }

    public record CreateRequest(@NotNull Long patientId, @NotNull Long machineId,
                                @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @NotNull Shift shift) {}

    /** 手动加排一条治疗单 */
    @PostMapping
    public DialysisSession create(@RequestBody CreateRequest request) {
        Patient patient = patientRepo.findById(request.patientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在"));
        DialysisMachine machine = machineRepo.findById(request.machineId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "机位不存在"));
        if (!SchedulingService.compatibleZones(patient.getInfectionMarker()).contains(machine.getZone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "感染分区不匹配，不能排到该机位");
        }
        DialysisSession session = new DialysisSession();
        session.setPatient(patient);
        session.setMachine(machine);
        session.setSessionDate(request.date());
        session.setShift(request.shift());
        session.setPlannedStart(SchedulingService.shiftStart(request.date(), request.shift()));
        session.setPlannedDurationMin(patient.getDurationMin() == null ? 240 : patient.getDurationMin());
        int maxSeq = sessionRepo.findBySessionDateAndShiftAndMachineId(request.date(), request.shift(), machine.getId())
                .stream().mapToInt(s -> s.getSeqNo() == null ? 0 : s.getSeqNo()).max().orElse(0);
        session.setSeqNo(maxSeq + 1);
        session.setNurseName(patient.getFamiliarNurse());
        return sessionRepo.save(session);
    }

    /** 调班影响分析 */
    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam Long sessionId, @RequestParam Long machineId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam Shift shift) {
        return schedulingService.impact(sessionId, machineId, date, shift);
    }

    public record MoveRequest(@NotNull Long machineId,
                              @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              @NotNull Shift shift) {}

    /** 执行调班 */
    @PostMapping("/{id}/move")
    public DialysisSession move(@PathVariable Long id, @RequestBody MoveRequest request) {
        return schedulingService.move(id, request.machineId(), request.date(), request.shift());
    }

    /** 患者迟到 */
    @PostMapping("/{id}/late")
    public ExceptionEvent late(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                               @AuthenticationPrincipal CurrentUser current) {
        DialysisSession session = get(id);
        String note = body != null ? body.getOrDefault("note", "") : "";
        session.setNote((session.getNote() == null ? "" : session.getNote() + " ") + "[迟到]" + note);
        sessionRepo.save(session);
        return exceptionService.raise(ExceptionType.LATE, session.getPatient(), session, session.getMachine(),
                "患者「" + session.getPatient().getName() + "」迟到。" + note, current.name());
    }

    /** 临时请假：治疗单标记缺席，预领耗材自动归还 */
    @PostMapping("/{id}/leave")
    public Map<String, Object> leave(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                     @AuthenticationPrincipal CurrentUser current) {
        DialysisSession session = get(id);
        if (session.getStatus() == SessionStatus.ON_DIALYSIS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "透析中不能标记请假");
        }
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        session.setStatus(SessionStatus.ABSENT);
        sessionRepo.save(session);

        // 预领耗材归还：按 耗材+批次 计算未归还余量（召回更换批次后原批次已退回，不能重复归还）
        List<ConsumableTxn> sessionTxns = txnRepo.findBySessionId(id);
        Map<String, Integer> outstanding = new LinkedHashMap<>();
        Map<String, ConsumableTxn> preDrawSample = new LinkedHashMap<>();
        for (ConsumableTxn t : sessionTxns) {
            if (t.getType() != TxnType.PRE_DRAW && t.getType() != TxnType.RETURN) continue;
            String key = t.getConsumableId() + "@" + t.getBatchId();
            outstanding.merge(key, -t.getQty(), Integer::sum);
            if (t.getType() == TxnType.PRE_DRAW) {
                preDrawSample.putIfAbsent(key, t);
            }
        }
        int returned = 0;
        for (Map.Entry<String, Integer> entry : outstanding.entrySet()) {
            if (entry.getValue() <= 0) continue;
            ConsumableTxn t = preDrawSample.get(entry.getKey());
            if (t == null) continue;
            Consumable c = consumableRepo.findById(t.getConsumableId()).orElse(null);
            if (c != null) {
                consumableService.transactBatch(c, TxnType.RETURN, entry.getValue(), id,
                        session.getPatient().getName(), current.name(), "请假归还预领", t.getBatchId());
                returned++;
            }
        }
        ExceptionEvent event = exceptionService.raise(ExceptionType.LEAVE, session.getPatient(), session,
                session.getMachine(),
                "患者「" + session.getPatient().getName() + "」临时请假，预领耗材已归还。" + reason, current.name());
        return Map.of("session", session, "returned", returned, "exception", event);
    }

    /** 上机开始透析 */
    @PostMapping("/{id}/start")
    public DialysisSession start(@PathVariable Long id) {
        DialysisSession session = get(id);
        if (session.getStatus() != SessionStatus.SCHEDULED && session.getStatus() != SessionStatus.ASSESSED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前状态不能上机");
        }
        session.setStatus(SessionStatus.ON_DIALYSIS);
        session.setActualStartAt(LocalDateTime.now());
        DialysisMachine machine = session.getMachine();
        machine.setStatus(MachineStatus.IN_USE);
        machineRepo.save(machine);
        return sessionRepo.save(session);
    }

    /** 治疗单详情：把患者、护理记录、异常、耗材、费用、机器消毒串成一条记录 */
    @GetMapping("/{id}/detail")
    public Map<String, Object> detail(@PathVariable Long id) {
        DialysisSession session = get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", session);
        result.put("pre", preRepo.findBySessionId(id).orElse(null));
        result.put("intra", intraRepo.findBySessionIdOrderByRecordTime(id));
        result.put("post", postRepo.findBySessionId(id).orElse(null));
        result.put("exceptions", exceptionRepo.findBySessionIdOrderByCreatedAt(id));
        result.put("billing", billingRepo.findBySessionId(id).orElse(null));
        result.put("consumables", txnRepo.findBySessionId(id));
        result.put("recallFollowups", followupRepo.findBySessionId(id));
        LocalDate day = session.getSessionDate();
        result.put("machineDisinfection", disinfectionRepo.findByMachineIdOrderByDoneAtDesc(session.getMachine().getId())
                .stream().filter(r -> r.getDoneAt().toLocalDate().equals(day)).limit(10).toList());
        return result;
    }

    private DialysisSession get(Long id) {
        return sessionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "治疗单不存在"));
    }
}
