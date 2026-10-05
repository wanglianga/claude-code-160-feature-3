package com.hd.center.entity;

import com.hd.center.entity.Enums.BatchStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 耗材批次：同一耗材按厂家批号分批入库，出库流水关联批次实现追溯。
 * 批次剩余量 = 该批次全部流水数量之和（入库为正、出库为负）。
 */
@Data
@Entity
@Table(name = "consumable_batch",
        uniqueConstraints = @UniqueConstraint(name = "uk_batch_consumable_no", columnNames = {"consumable_id", "batchNo"}),
        indexes = @Index(name = "idx_batch_consumable", columnList = "consumable_id"))
public class ConsumableBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "consumable_id")
    private Consumable consumable;

    /** 厂家批号 */
    @Column(nullable = false, length = 64)
    private String batchNo;

    /** 供应商 */
    @Column(nullable = false, length = 128)
    private String supplier;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    /** 累计入库数量（含每次补货） */
    @Column(nullable = false)
    private Integer receivedQty = 0;

    /** 正常 / 已冻结（召回处理中，禁止发放）/ 已召回 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status = BatchStatus.NORMAL;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
