package com.hd.center.entity;

import com.hd.center.entity.Enums.FollowupStatus;
import com.hd.center.entity.Enums.Shift;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 召回患者回访任务：召回批次涉及的治疗记录逐条生成，
 * 护士回访登记是否不适，医生判断是否需要复查。
 */
@Data
@Entity
@Table(name = "recall_followup", indexes = {
        @Index(name = "idx_followup_recall", columnList = "recall_id"),
        @Index(name = "idx_followup_session", columnList = "sessionId")
})
public class RecallFollowup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recall_id")
    private RecallEvent recall;

    /** 受影响治疗单（历史演示数据可为空） */
    private Long sessionId;

    private Long patientId;

    @Column(nullable = false, length = 64)
    private String patientName;

    private LocalDate sessionDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Shift shift;

    /** 回访状态：待回访 / 已回访 / 联系不上 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FollowupStatus status = FollowupStatus.PENDING;

    /** 回访发现患者是否不适 */
    private Boolean discomfort;

    @Column(length = 500)
    private String symptoms;

    @Column(length = 500)
    private String followupNote;

    @Column(length = 64)
    private String nurseName;

    private LocalDateTime followupAt;

    /** 医生判断是否需要复查 */
    private Boolean reviewNeeded;

    @Column(length = 500)
    private String reviewNote;

    @Column(length = 64)
    private String doctorName;

    private LocalDateTime reviewAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
