package com.hd.center.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 耗材档案与库存。
 */
@Data
@Entity
@Table(name = "consumable")
public class Consumable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 128)
    private String spec;

    /** 默认供应商（批次可覆盖），供应商考核按此归集 */
    @Column(length = 128)
    private String supplier;

    @Column(length = 16)
    private String unit;

    @Column(nullable = false)
    private Integer stock = 0;

    /** 安全库存，低于则预警 */
    @Column(nullable = false)
    private Integer safeStock = 0;

    /** 是否自费耗材 */
    @Column(nullable = false)
    private Boolean selfPay = false;

    @Column(precision = 10, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;
}
