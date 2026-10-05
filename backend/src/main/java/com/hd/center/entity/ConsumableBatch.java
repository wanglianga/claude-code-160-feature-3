package com.hd.center.entity;

import com.hd.center.entity.Enums.BatchStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 耗材批次：按「耗材 + 厂家批号」管理库存，是召回追溯的最小单元。
 * 同一耗材可有多个批次，出库按近效期优先（FEFO），召回期间批次冻结、停止发放。
 */
@Data
@Entity
@Table(name = "consumable_batch",
        uniqueConstraints = @UniqueConstraint(name = "uk_batch_consumable_no",
                columnNames = {"consumableId", "batchNo"}),
        indexes = {
                @Index(name = "idx_batch_consumable", columnList = "consumableId"),
                @Index(name = "idx_batch_status", columnList = "status")
        })
public class ConsumableBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long consumableId;

    /** 冗余耗材名称，便于列表展示 */
    @Column(nullable = false, length = 128)
    private String consumableName;

    /** 耗材编码 */
    @Column(nullable = false, length = 32)
    private String consumableCode;

    /** 厂家批号 */
    @Column(nullable = false, length = 64)
    private String batchNo;

    /** 供应商/厂家 */
    @Column(length = 128)
    private String supplier;

    private LocalDate productionDate;

    /** 失效日期，FEFO 排序依据 */
    private LocalDate expiryDate;

    /** 本批次入库总量 */
    @Column(nullable = false)
    private Integer inboundQty = 0;

    /** 当前在库数量（含冻结期间未动用部分） */
    @Column(nullable = false)
    private Integer stockQty = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BatchStatus status = BatchStatus.ACTIVE;

    /** 当前关联的进行中召回单（如有） */
    private Long recallId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
