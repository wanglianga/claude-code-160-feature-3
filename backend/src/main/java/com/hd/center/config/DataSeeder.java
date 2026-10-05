package com.hd.center.config;

import com.hd.center.entity.*;
import com.hd.center.entity.Enums.*;
import com.hd.center.repository.*;
import com.hd.center.service.BillingService;
import com.hd.center.service.ConsumableService;
import com.hd.center.service.ExceptionService;
import com.hd.center.service.SchedulingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 首次启动时初始化演示数据：账号、机位、患者、耗材、今日排班与一条完整治疗流水。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepo;
    private final PatientRepository patientRepo;
    private final MachineRepository machineRepo;
    private final ConsumableRepository consumableRepo;
    private final ConsumableBatchRepository batchRepo;
    private final RecallEventRepository recallRepo;
    private final RecallFollowupRepository followupRepo;
    private final SessionRepository sessionRepo;
    private final PreAssessmentRepository preRepo;
    private final IntraRecordRepository intraRepo;
    private final PostRecordRepository postRepo;
    private final DisinfectionRecordRepository disinfectionRepo;
    private final ConsumableTxnRepository txnRepo;
    private final PasswordEncoder passwordEncoder;
    private final SchedulingService schedulingService;
    private final BillingService billingService;
    private final ExceptionService exceptionService;
    private final ConsumableService consumableService;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.count() > 0) {
            return;
        }
        log.info("初始化演示数据...");
        seedUsers();
        seedMachines();
        seedPatients();
        seedConsumables();
        seedHistoricalRecall();
        seedDisinfectionHistory();
        seedTodaySessions();
        log.info("演示数据初始化完成");
    }

    private void seedUsers() {
        user("admin", "admin123", "王敏（护士长）", Role.ADMIN);
        user("nurse1", "123456", "李静", Role.NURSE);
        user("nurse2", "123456", "张岚", Role.NURSE);
        user("doctor1", "123456", "陈国强", Role.DOCTOR);
        user("eng1", "123456", "赵工", Role.ENGINEER);
        user("fin1", "123456", "刘会计", Role.FINANCE);
    }

    private void user(String username, String password, String name, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setName(name);
        u.setRole(role);
        userRepo.save(u);
    }

    private void seedMachines() {
        for (int i = 1; i <= 12; i++) {
            DialysisMachine m = new DialysisMachine();
            m.setCode(String.format("M%02d", i));
            m.setModel(i % 2 == 0 ? "Fresenius 4008S" : "Baxter AK98");
            if (i <= 8) m.setZone(MachineZone.NORMAL);
            else if (i <= 10) m.setZone(MachineZone.HBV);
            else if (i == 11) m.setZone(MachineZone.HCV);
            else m.setZone(MachineZone.ISOLATION);
            m.setDisinfectBufferMin(40);
            machineRepo.save(m);
        }
    }

    private void seedPatients() {
        // AM 班（周一三五）
        patient("P0001", "王建国", "男", 67, InfectionMarker.NONE, RiskLevel.HIGH, AccessType.AVF, 62.5,
                "低分子肝素 3000IU", TransportMode.SHUTTLE, "06:50-07:10", "1,3,5", Shift.AM, 240, "李静");
        patient("P0002", "李秀兰", "女", 58, InfectionMarker.NONE, RiskLevel.MEDIUM, AccessType.AVF, 55.0,
                "普通肝素 2500IU", TransportMode.SELF, null, "1,3,5", Shift.AM, 240, "李静");
        patient("P0003", "张志强", "男", 45, InfectionMarker.HBV, RiskLevel.MEDIUM, AccessType.AVF, 70.2,
                "低分子肝素 4000IU", TransportMode.FAMILY, null, "1,3,5", Shift.AM, 240, "张岚");
        patient("P0004", "刘桂芳", "女", 72, InfectionMarker.NONE, RiskLevel.HIGH, AccessType.CATHETER, 48.6,
                "无肝素透析", TransportMode.SHUTTLE, "06:50-07:10", "1,3,5", Shift.AM, 210, "李静");
        patient("P0005", "陈明远", "男", 53, InfectionMarker.HCV, RiskLevel.LOW, AccessType.AVG, 75.0,
                "普通肝素 3000IU", TransportMode.SELF, null, "1,3,5", Shift.AM, 240, "张岚");
        patient("P0006", "杨丽娟", "女", 61, InfectionMarker.NONE, RiskLevel.LOW, AccessType.AVF, 52.3,
                "低分子肝素 2500IU", TransportMode.FAMILY, null, "1,3,5", Shift.AM, 240, "李静");
        // PM 班（周一三五）
        patient("P0007", "赵国庆", "男", 66, InfectionMarker.NONE, RiskLevel.MEDIUM, AccessType.AVF, 68.0,
                "低分子肝素 3500IU", TransportMode.SHUTTLE, "12:50-13:10", "1,3,5", Shift.PM, 240, "张岚");
        patient("P0008", "孙玉梅", "女", 49, InfectionMarker.HBV, RiskLevel.LOW, AccessType.AVF, 58.5,
                "普通肝素 2500IU", TransportMode.SELF, null, "1,3,5", Shift.PM, 240, "张岚");
        patient("P0009", "周立本", "男", 70, InfectionMarker.NONE, RiskLevel.HIGH, AccessType.AVF, 65.4,
                "低分子肝素 3000IU", TransportMode.FAMILY, null, "1,3,5", Shift.PM, 210, "李静");
        patient("P0018", "唐晓燕", "女", 44, InfectionMarker.NONE, RiskLevel.LOW, AccessType.AVF, 53.5,
                "普通肝素 2000IU", TransportMode.SELF, null, "1,3,5", Shift.PM, 240, "李静");
        // AM 班（周二四六）
        patient("P0010", "吴桂英", "女", 63, InfectionMarker.NONE, RiskLevel.MEDIUM, AccessType.AVF, 54.8,
                "低分子肝素 3000IU", TransportMode.SHUTTLE, "06:50-07:10", "2,4,6", Shift.AM, 240, "李静");
        patient("P0011", "郑海涛", "男", 55, InfectionMarker.NONE, RiskLevel.LOW, AccessType.AVF, 72.0,
                "普通肝素 3000IU", TransportMode.SELF, null, "2,4,6", Shift.AM, 240, "张岚");
        patient("P0013", "黄志明", "男", 60, InfectionMarker.NONE, RiskLevel.MEDIUM, AccessType.AVG, 66.7,
                "低分子肝素 3500IU", TransportMode.SELF, null, "2,4,6", Shift.AM, 240, "张岚");
        patient("P0017", "罗国栋", "男", 62, InfectionMarker.OTHER, RiskLevel.MEDIUM, AccessType.CATHETER, 69.0,
                "无肝素透析", TransportMode.FAMILY, null, "2,4,6", Shift.AM, 240, "张岚");
        // PM 班（周二四六）
        patient("P0012", "林秀英", "女", 68, InfectionMarker.HCV, RiskLevel.MEDIUM, AccessType.CATHETER, 50.5,
                "低分子肝素 2500IU", TransportMode.FAMILY, null, "2,4,6", Shift.PM, 240, "李静");
        patient("P0014", "徐凤仙", "女", 74, InfectionMarker.NONE, RiskLevel.HIGH, AccessType.AVF, 47.9,
                "无肝素透析", TransportMode.SHUTTLE, "12:50-13:10", "2,4,6", Shift.PM, 210, "李静");
        patient("P0015", "高建军", "男", 51, InfectionMarker.HBV, RiskLevel.MEDIUM, AccessType.AVF, 71.3,
                "低分子肝素 4000IU", TransportMode.SELF, null, "2,4,6", Shift.PM, 240, "张岚");
        patient("P0016", "何丽芬", "女", 57, InfectionMarker.NONE, RiskLevel.LOW, AccessType.AVF, 56.0,
                "普通肝素 2500IU", TransportMode.FAMILY, null, "2,4,6", Shift.PM, 240, "李静");
    }

    private void patient(String mrn, String name, String gender, int age, InfectionMarker marker,
                         RiskLevel risk, AccessType access, double dryWeight, String anticoag,
                         TransportMode transport, String window, String pattern, Shift shift,
                         int duration, String nurse) {
        Patient p = new Patient();
        p.setMrn(mrn);
        p.setName(name);
        p.setGender(gender);
        p.setAge(age);
        p.setPhone("138****" + String.format("%04d", Math.abs(mrn.hashCode()) % 10000));
        p.setInfectionMarker(marker);
        p.setRiskLevel(risk);
        p.setAccessType(access);
        p.setDryWeight(dryWeight);
        p.setAnticoagPlan(anticoag);
        p.setTransportMode(transport);
        p.setTransportWindow(window);
        p.setUsualPattern(pattern);
        p.setUsualShift(shift);
        p.setDurationMin(duration);
        p.setFamiliarNurse(nurse);
        patientRepo.save(p);
    }

    private void seedConsumables() {
        consumable("DIALYZER", "空心纤维透析器", "FX60", "支", 30, false, "0");
        consumable("BLOODLINE", "体外循环血路管", "成人型", "套", 40, false, "0");
        consumable("NEEDLE", "内瘘穿刺针", "16G", "支", 60, false, "0");
        consumable("HEPARIN", "肝素钠注射液", "12500IU", "支", 20, false, "0");
        consumable("SALINE", "生理盐水", "500ml", "袋", 30, false, "0");
        consumable("DIALYSATE", "碳酸氢盐透析液", "A液 10L", "桶", 25, false, "0");
        consumable("DISINFECTANT", "柠檬酸消毒液", "5L", "桶", 10, false, "0");
        consumable("FILTER", "水处理滤芯", "10寸 PP棉", "支", 3, false, "0");
        consumable("DRESSING", "自费敷料贴", "防水型", "片", 15, true, "12.50");
        // 批次入库：库存由入库流水累计，出库按 FEFO 分配批次
        batch("DIALYZER", "FX60-2508A", "德医医疗", 70, 4);
        batch("DIALYZER", "FX60-2509B", "德医医疗", 50, 8);
        batch("BLOODLINE", "BL-2507C", "康诚生物", 80, 5);
        batch("BLOODLINE", "BL-2509D", "康诚生物", 70, 9);
        batch("NEEDLE", "ND-2508", "康诚生物", 200, 10);
        batch("HEPARIN", "HP-2506", "华瑞制药", 80, 7);
        batch("SALINE", "SA-2508", "华瑞制药", 100, 11);
        batch("DIALYSATE", "DL-2507", "德医医疗", 90, 6);
        batch("DISINFECTANT", "DS-2508", "洁诺化工", 40, 12);
        batch("FILTER", "FT-2505", "洁诺化工", 8, 18);
        batch("DRESSING", "DR-2508", "康诚生物", 60, 12);
    }

    private void consumable(String code, String name, String spec, String unit,
                            int safeStock, boolean selfPay, String price) {
        Consumable c = new Consumable();
        c.setCode(code);
        c.setName(name);
        c.setSpec(spec);
        c.setUnit(unit);
        c.setStock(0);
        c.setSafeStock(safeStock);
        c.setSelfPay(selfPay);
        c.setPrice(new BigDecimal(price));
        consumableRepo.save(c);
    }

    /** 批次入库：建立批次档案并记录入库流水（库存随之增加） */
    private void batch(String consumableCode, String batchNo, String supplier, int qty, int expiryMonths) {
        Consumable c = consumableRepo.findAllByOrderByCode().stream()
                .filter(x -> x.getCode().equals(consumableCode)).findFirst().orElseThrow();
        consumableService.restock(c, qty, batchNo, supplier, LocalDate.now().plusMonths(expiryMonths),
                "赵工", "批次入库 " + batchNo);
    }

    /**
     * 历史已关闭召回（供应商考核演示数据）：血路管批次 BL-2505X 环氧乙烷残留超标，
     * 剩余库存退回厂家，患者回访均无不适，处理结果计入供应商考核。
     */
    private void seedHistoricalRecall() {
        Consumable bloodline = consumableRepo.findAllByOrderByCode().stream()
                .filter(c -> c.getCode().equals("BLOODLINE")).findFirst().orElseThrow();
        ConsumableBatch old = new ConsumableBatch();
        old.setConsumable(bloodline);
        old.setBatchNo("BL-2505X");
        old.setSupplier("康诚生物");
        old.setProductionDate(LocalDate.now().minusMonths(6));
        old.setExpiryDate(LocalDate.now().plusMonths(2));
        old.setReceivedQty(60);
        old.setStatus(BatchStatus.RECALLED);
        old.setCreatedAt(LocalDateTime.now().minusDays(70));
        old = batchRepo.save(old);

        // 历史流水：入库 +60、消耗 -47、退回厂家 -13（净额 0，不影响当前库存）
        backdatedTxn(bloodline, old, TxnType.RESTOCK, 60, "赵工", "批次入库 BL-2505X", 70);
        backdatedTxn(bloodline, old, TxnType.USE, -47, "李静", "历史消耗汇总", 30);
        backdatedTxn(bloodline, old, TxnType.SUPPLIER_RETURN, -13, "赵工", "召回退回厂家", 5);

        RecallEvent recall = new RecallEvent();
        recall.setRecallNo("RC" + LocalDate.now().minusDays(7).format(DateTimeFormatter.BASIC_ISO_DATE) + "-001");
        recall.setBatch(old);
        recall.setReason("厂家自检发现批次 BL-2505X 血路管环氧乙烷残留超标，主动召回");
        recall.setStatus(RecallStatus.CLOSED);
        recall.setOutcome(RecallOutcome.CONFIRMED);
        recall.setConclusion("剩余 13 套已退回厂家；2 名相关患者回访均无不适、无需复查。供应商已提交整改报告。");
        recall.setCreatedBy("王敏（护士长）");
        recall.setCreatedAt(LocalDateTime.now().minusDays(7));
        recall.setClosedBy("王敏（护士长）");
        recall.setClosedAt(LocalDateTime.now().minusDays(5));
        recall = recallRepo.save(recall);

        // 历史回访记录（均已回访、无不适、无需复查）
        List<Patient> patients = patientRepo.findByActiveTrueOrderByMrn();
        for (int i = 0; i < 2 && i < patients.size(); i++) {
            RecallFollowup f = new RecallFollowup();
            f.setRecall(recall);
            f.setPatientId(patients.get(i).getId());
            f.setPatientName(patients.get(i).getName());
            f.setSessionDate(LocalDate.now().minusDays(8));
            f.setShift(Shift.AM);
            f.setStatus(FollowupStatus.DONE);
            f.setDiscomfort(false);
            f.setFollowupNote("电话回访，自述无不适");
            f.setNurseName("李静");
            f.setFollowupAt(LocalDateTime.now().minusDays(6));
            f.setReviewNeeded(false);
            f.setReviewNote("无不适，无需复查");
            f.setDoctorName("陈国强");
            f.setReviewAt(LocalDateTime.now().minusDays(6));
            f.setCreatedAt(LocalDateTime.now().minusDays(7));
            followupRepo.save(f);
        }
    }

    /** 补记历史流水（仅留追溯记录，不影响当前库存） */
    private void backdatedTxn(Consumable c, ConsumableBatch batch, TxnType type, int signedQty,
                              String operator, String note, int daysAgo) {
        ConsumableTxn txn = new ConsumableTxn();
        txn.setConsumableId(c.getId());
        txn.setConsumableName(c.getName());
        txn.setQty(signedQty);
        txn.setType(type);
        txn.setBatchId(batch.getId());
        txn.setBatchNo(batch.getBatchNo());
        txn.setOperatorName(operator);
        txn.setNote(note);
        txn.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        txnRepo.save(txn);
    }

    /** 过去三天每台机的日常消毒记录（消毒合规统计用） */
    private void seedDisinfectionHistory() {
        List<DialysisMachine> machines = machineRepo.findByActiveTrueOrderByCode();
        for (int d = 3; d >= 1; d--) {
            for (DialysisMachine m : machines) {
                DisinfectionRecord r = new DisinfectionRecord();
                r.setMachineId(m.getId());
                r.setMachineCode(m.getCode());
                r.setType(DisinfectionType.DAILY);
                r.setOperatorName("赵工");
                r.setDoneAt(LocalDateTime.now().minusDays(d).withHour(6).withMinute(20));
                r.setResult(DisinfectionResult.PASS);
                r.setNote("班前日常消毒");
                disinfectionRepo.save(r);
            }
        }
        // 水处理记录
        DisinfectionRecord water = new DisinfectionRecord();
        water.setType(DisinfectionType.WATER_TEST);
        water.setOperatorName("赵工");
        water.setDoneAt(LocalDateTime.now().minusDays(1).withHour(8).withMinute(0));
        water.setResult(DisinfectionResult.PASS);
        water.setNote("反渗水细菌内毒素检测合格");
        disinfectionRepo.save(water);
    }

    /** 今日排班 + 一条完整治疗流水演示 */
    private void seedTodaySessions() {
        LocalDate today = LocalDate.now();
        // 今日 AM/PM 排班（忽略固定透析日，保证任意日期都有演示数据）
        schedulingService.generate(today, Shift.AM, true, "系统初始化");
        schedulingService.generate(today, Shift.PM, true, "系统初始化");
        // 明日排班（按患者固定透析日自然生成）
        schedulingService.generate(today.plusDays(1), Shift.AM, false, "系统初始化");
        schedulingService.generate(today.plusDays(1), Shift.PM, false, "系统初始化");

        List<DialysisSession> amSessions = sessionRepo.findBySessionDateAndShiftOrderBySeqNo(today, Shift.AM)
                .stream().filter(s -> s.getStatus() == SessionStatus.SCHEDULED).toList();
        if (amSessions.size() < 6) {
            return;
        }

        // 1) 前四位患者完成透前评估
        for (int i = 0; i < 4; i++) {
            DialysisSession s = amSessions.get(i);
            PreAssessment pre = new PreAssessment();
            pre.setSessionId(s.getId());
            pre.setWeight(s.getPatient().getDryWeight() + 1.8);
            pre.setBpSys(135 + i * 5);
            pre.setBpDia(82);
            pre.setHeartRate(76);
            pre.setAccessCondition(AccessCondition.NORMAL);
            pre.setLastDiscomfort(i == 1 ? "上次透析后轻度乏力" : "无");
            pre.setAnticoagPlan(s.getPatient().getAnticoagPlan());
            pre.setAdjustDehydration(i == 0);
            pre.setDehydrationTarget(i == 0 ? 2.0 : 1.8);
            pre.setNurseName("李静");
            preRepo.save(pre);
            s.setStatus(SessionStatus.ASSESSED);
            sessionRepo.save(s);
        }

        // 2) 前两位患者上机
        DialysisSession s0 = amSessions.get(0);
        DialysisSession s1 = amSessions.get(1);
        startSession(s0, 120);
        startSession(s1, 90);

        // 3) 透中记录：s0 两条正常记录；s1 第二条低血压触发异常
        intra(s0, 100, 128, 80, 74, null, null, null, null, null);
        intra(s0, 40, 132, 84, 76, null, 100, null, null, null);
        intra(s1, 60, 126, 78, 72, null, null, null, null, null);
        intra(s1, 20, 86, 55, 88, "静脉压报警", 200, null, "头晕、出汗", "减慢超滤，遵医嘱静推 50% 葡萄糖 40ml");
        exceptionService.raise(ExceptionType.HYPOTENSION, s1.getPatient(), s1, s1.getMachine(),
                "透析中低血压：收缩压 86 mmHg，请立即处理", "李静");
        exceptionService.raise(ExceptionType.ORDER_CHANGE, s1.getPatient(), s1, s1.getMachine(),
                "医嘱变更：减慢超滤，遵医嘱静推 50% 葡萄糖 40ml", "陈国强");

        // 4) s0 完成下机 -> 机器消毒 -> 账单
        PostRecord post = new PostRecord();
        post.setSessionId(s0.getId());
        post.setOffWeight(s0.getPatient().getDryWeight() + 0.3);
        post.setHemostasisOk(true);
        post.setHemostasisMin(12);
        post.setDepartureMode(DepartureMode.SHUTTLE);
        post.setNextScheduleNote("下次按常规排班，注意控制间期体重增长");
        post.setNurseName("李静");
        postRepo.save(post);
        s0.setStatus(SessionStatus.OFF_DIALYSIS);
        s0.setActualEndAt(LocalDateTime.now().minusMinutes(30));
        sessionRepo.save(s0);
        s0.getMachine().setStatus(MachineStatus.DISINFECTING);
        machineRepo.save(s0.getMachine());
        billingService.createForSession(s0);

        DisinfectionRecord dis = new DisinfectionRecord();
        dis.setMachineId(s0.getMachine().getId());
        dis.setMachineCode(s0.getMachine().getCode());
        dis.setType(DisinfectionType.CITRIC_ACID);
        dis.setOperatorName("赵工");
        dis.setDoneAt(LocalDateTime.now().minusMinutes(10));
        dis.setResult(DisinfectionResult.PASS);
        dis.setNote("下机后柠檬酸消毒");
        disinfectionRepo.save(dis);
        s0.getMachine().setStatus(MachineStatus.IDLE);
        s0.getMachine().setLastDisinfectedAt(dis.getDoneAt());
        machineRepo.save(s0.getMachine());

        // 5) 一位患者请假、一位迟到
        DialysisSession s4 = amSessions.get(4);
        s4.setStatus(SessionStatus.ABSENT);
        sessionRepo.save(s4);
        exceptionService.raise(ExceptionType.LEAVE, s4.getPatient(), s4, s4.getMachine(),
                "患者临时请假（家中有事），预领耗材待归还", "李静");

        DialysisSession s5 = amSessions.get(5);
        exceptionService.raise(ExceptionType.LATE, s5.getPatient(), s5, s5.getMachine(),
                "患者迟到约 25 分钟，班车晚点", "张岚");

        // 6) 耗材损耗演示
        consumableRepo.findAllByOrderByCode().stream()
                .filter(c -> c.getCode().equals("NEEDLE")).findFirst()
                .ifPresent(c -> consumableService.transact(c, TxnType.WASTE, 2, null, null, "李静", "包装破损报废"));
        consumableRepo.findAllByOrderByCode().stream()
                .filter(c -> c.getCode().equals("SALINE")).findFirst()
                .ifPresent(c -> consumableService.transact(c, TxnType.WASTE, 1, null, null, "张岚", "污染报废"));

        // 7) 一台空闲机位维护
        machineRepo.findByActiveTrueOrderByCode().stream()
                .filter(m -> m.getCode().equals("M08")).findFirst()
                .ifPresent(m -> {
                    m.setStatus(MachineStatus.MAINTENANCE);
                    machineRepo.save(m);
                    exceptionService.raise(ExceptionType.MACHINE_FAULT, null, null, m,
                            "机位 M08 电导度漂移，停机维护", "赵工");
                });
    }

    private void startSession(DialysisSession s, int minutesAgo) {
        s.setStatus(SessionStatus.ON_DIALYSIS);
        s.setActualStartAt(LocalDateTime.now().minusMinutes(minutesAgo));
        sessionRepo.save(s);
        s.getMachine().setStatus(MachineStatus.IN_USE);
        machineRepo.save(s.getMachine());
    }

    private void intra(DialysisSession s, int minutesAgo, Integer bpSys, Integer bpDia, Integer hr,
                       String alarm, Integer fluid, String bloodDraw, String discomfort, String orderChange) {
        IntraRecord r = new IntraRecord();
        r.setSessionId(s.getId());
        r.setRecordTime(LocalDateTime.now().minusMinutes(minutesAgo));
        r.setBpSys(bpSys);
        r.setBpDia(bpDia);
        r.setHeartRate(hr);
        r.setAlarm(alarm);
        r.setFluidMl(fluid);
        r.setBloodDraw(bloodDraw);
        r.setDiscomfort(discomfort);
        r.setOrderChange(orderChange);
        r.setNurseName("李静");
        intraRepo.save(r);
    }
}
