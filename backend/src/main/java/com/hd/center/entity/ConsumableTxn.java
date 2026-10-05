package com.hd.center.entity;

import com.hd.center.entity.Enums.TxnType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 耗材出入库流水：预领、使用、归还、损耗、入库。
 * qty 为有符号数量：出库为负，入库为正。
 */
@Data
@Entity
@Table(name = "consumable_txn", indexes = {
        @Index(name = "idx_txn_session", columnList = "sessionId"),
        @Index(name = "idx_txn_batch", columnList = "batchId")
})
public class ConsumableTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long consumableId;

    @Column(nullable = false, length = 128)
    private String consumableName;

    /** 耗材批次：按批出入库时记录，是召回追溯依据；非按批流水为空 */
    private Long batchId;

    @Column(length = 64)
    private String batchNo;

    @Column(nullable = false)
    private Integer qty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TxnType type;

    /** 关联治疗单（预领/使用时） */
    private Long sessionId;

    @Column(length = 64)
    private String patientName;

    @Column(length = 64)
    private String operatorName;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
