package com.hd.center.entity;

import com.hd.center.entity.Enums.QualityVerdict;
import com.hd.center.entity.Enums.RecallStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 厂家召回单：针对某耗材某一批次发起。
 * 发起后自动冻结该批次未使用库存、列出受影响治疗记录、暂停发放并提示已排治疗更换批次；
 * 处理结果（质量定性）回流到耗材质量与供应商考核。
 */
@Data
@Entity
@Table(name = "recall_order", indexes = @Index(name = "idx_recall_status", columnList = "status"))
public class RecallOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 召回单号，如 RC20261005-001 */
    @Column(nullable = false, unique = true, length = 32)
    private String recallNo;

    @Column(nullable = false)
    private Long consumableId;

    @Column(nullable = false, length = 128)
    private String consumableName;

    @Column(nullable = false, length = 32)
    private String consumableCode;

    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(length = 128)
    private String supplier;

    /** 受影响批次的生产日期/失效日期（冗余展示） */
    private LocalDate productionDate;

    private LocalDate expiryDate;

    /** 厂家来函/召回文号 */
    @Column(length = 128)
    private String manufacturerDocNo;

    /** 召回原因 */
    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RecallStatus status = RecallStatus.OPEN;

    /** 发起时冻结的未使用库存数量 */
    @Column(nullable = false)
    private Integer frozenQty = 0;

    /** 已退回厂家的数量 */
    @Column(nullable = false)
    private Integer returnedQty = 0;

    /** 发起时统计：受影响治疗记录数 / 涉及患者数 */
    @Column(nullable = false)
    private Integer affectedSessionCount = 0;

    @Column(nullable = false)
    private Integer affectedPatientCount = 0;

    @Column(length = 64)
    private String createdBy;

    @Column(length = 64)
    private String closedBy;

    /** 办结时质量定性 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private QualityVerdict qualityVerdict;

    /** 供应商考核扣分（办结时记入） */
    private Integer supplierDeduction = 0;

    /** 办结/考核说明 */
    @Column(length = 1000)
    private String closeNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;
}
