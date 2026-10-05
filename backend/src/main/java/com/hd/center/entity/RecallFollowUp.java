package com.hd.center.entity;

import com.hd.center.entity.Enums.FollowUpStatus;
import com.hd.center.entity.Enums.ReviewResult;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 召回受影响治疗记录（回访单）：
 * 平台按耗材出库与患者使用记录列出——每一条使用过召回批次的治疗单生成一条。
 * 护士逐例电话回访是否不适；发现不适由医生查看当日机器、护士、耗材、透析参数并给出复查结论；
 * 对尚未开始的治疗单记录批次更换情况。
 */
@Data
@Entity
@Table(name = "recall_follow_up",
        uniqueConstraints = @UniqueConstraint(name = "uk_followup_recall_session",
                columnNames = {"recallId", "sessionId"}),
        indexes = @Index(name = "idx_followup_patient", columnList = "patientId"))
public class RecallFollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recallId;

    /** 冗余召回批号，便于治疗记录耗材列表标红 */
    @Column(length = 64)
    private String batchNo;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Long patientId;

    @Column(nullable = false, length = 64)
    private String patientName;

    @Column(length = 32)
    private String patientPhone;

    private LocalDate sessionDate;

    @Column(length = 8)
    private String shift;

    @Column(length = 64)
    private String machineCode;

    /** 该治疗单责任护士 */
    @Column(length = 64)
    private String nurseName;

    /** 该治疗单使用召回批次数量（来自出库流水，负数转正） */
    @Column(nullable = false)
    private Integer usedQty = 0;

    /** 该治疗时治疗单状态，区分已使用/待治疗 */
    @Column(length = 20)
    private String sessionStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FollowUpStatus status = FollowUpStatus.PENDING;

    @Column(length = 64)
    private String contactedBy;

    private LocalDateTime contactedAt;

    /** 回访记录（是否不适的详细描述） */
    @Column(length = 1000)
    private String followNote;

    /** 患者是否报告不适 */
    @Column(nullable = false)
    private Boolean discomfort = false;

    /** 不适症状描述 */
    @Column(length = 500)
    private String discomfortSymptoms;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewResult reviewResult = ReviewResult.PENDING;

    @Column(length = 64)
    private String reviewedBy;

    private LocalDateTime reviewedAt;

    /** 复查安排/医嘱 */
    @Column(length = 500)
    private String recheckPlan;

    @Column(length = 1000)
    private String reviewNote;

    /** 未开始治疗单的批次更换 */
    @Column(nullable = false)
    private Boolean batchReplaced = false;

    @Column(length = 64)
    private String replacementBatchNo;

    @Column(length = 64)
    private String replacedBy;

    private LocalDateTime replacedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
