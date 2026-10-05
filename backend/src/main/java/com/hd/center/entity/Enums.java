package com.hd.center.entity;

/**
 * 平台全部枚举定义。前端通过 /api/meta/enums 获取中文展示映射。
 */
public final class Enums {

    private Enums() {}

    /** 用户角色 */
    public enum Role { ADMIN, NURSE, DOCTOR, ENGINEER, FINANCE }

    /** 感染标记（传染病分区依据） */
    public enum InfectionMarker { NONE, HBV, HCV, TP, OTHER }

    /** 患者风险等级 */
    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /** 血管通路类型 */
    public enum AccessType { AVF, AVG, CATHETER }

    /** 交通接送方式 */
    public enum TransportMode { SELF, FAMILY, SHUTTLE }

    /** 班次 */
    public enum Shift { AM, PM, EVE }

    /** 机位分区 */
    public enum MachineZone { NORMAL, HBV, HCV, ISOLATION }

    /** 透析机状态 */
    public enum MachineStatus { IDLE, IN_USE, DISINFECTING, FAULT, MAINTENANCE }

    /** 透析治疗单状态 */
    public enum SessionStatus { SCHEDULED, ASSESSED, ON_DIALYSIS, OFF_DIALYSIS, FINISHED, ABSENT, CANCELLED }

    /** 通路情况 */
    public enum AccessCondition { NORMAL, REDNESS, SWELLING, BRUIT_WEAK, INFECTION }

    /** 离开方式 */
    public enum DepartureMode { SELF, FAMILY, SHUTTLE, WHEELCHAIR, STRETCHER }

    /** 消毒/工程记录类型 */
    public enum DisinfectionType { DAILY, HEAT, CITRIC_ACID, FILTER_CHANGE, WATER_TREATMENT, WATER_TEST }

    /** 消毒结果 */
    public enum DisinfectionResult { PASS, FAIL }

    /** 耗材出入库类型 */
    public enum TxnType { PRE_DRAW, USE, RETURN, WASTE, RESTOCK, SUPPLIER_RETURN }

    /** 耗材批次状态 */
    public enum BatchStatus { NORMAL, FROZEN, RECALLED }

    /** 召回事件状态 */
    public enum RecallStatus { OPEN, PROCESSING, CLOSED }

    /** 召回关闭结论：确认召回退回厂家 / 解除冻结 */
    public enum RecallOutcome { CONFIRMED, LIFTED }

    /** 召回回访状态 */
    public enum FollowupStatus { PENDING, DONE, UNREACHABLE }

    /** 异常事件类型 */
    public enum ExceptionType { LATE, LEAVE, INFECTION_CHANGE, MACHINE_FAULT, CONSUMABLE_SHORTAGE, HYPOTENSION, ORDER_CHANGE, RECALL, RECALL_DISCOMFORT, OTHER }

    /** 异常处理状态 */
    public enum ExceptionStatus { OPEN, PROCESSING, RESOLVED }

    /** 收费核对状态 */
    public enum BillingStatus { PENDING, CONFIRMED }
}
