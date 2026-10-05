package com.hd.center.entity;

import com.hd.center.entity.Enums.RecallOutcome;
import com.hd.center.entity.Enums.RecallStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 耗材召回事件：厂家召回某批次耗材后，冻结库存、追踪受影响治疗、
 * 组织患者回访与复查，处理结果进入耗材质量与供应商考核。
 */
@Data
@Entity
@Table(name = "recall_event", indexes = @Index(name = "idx_recall_batch", columnList = "batch_id"))
public class RecallEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 召回单号，如 RC20261005-001 */
    @Column(nullable = false, unique = true, length = 32)
    private String recallNo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "batch_id")
    private ConsumableBatch batch;

    /** 召回原因（厂家通知） */
    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecallStatus status = RecallStatus.OPEN;

    /** 关闭结论：确认召回退回厂家 / 解除冻结 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RecallOutcome outcome;

    /** 处理结果总结（进入耗材质量与供应商考核） */
    @Column(length = 1000)
    private String conclusion;

    @Column(length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(length = 64)
    private String closedBy;

    private LocalDateTime closedAt;
}
