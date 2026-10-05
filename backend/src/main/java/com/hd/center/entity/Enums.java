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
    public enum TxnType { PRE_DRAW, USE, RETURN, WASTE, RESTOCK, RECALL_OUT, BATCH_IN }

    /** 异常事件类型 */
    public enum ExceptionType {
        LATE, LEAVE, INFECTION_CHANGE, MACHINE_FAULT, CONSUMABLE_SHORTAGE, HYPOTENSION, ORDER_CHANGE,
        CONSUMABLE_RECALL, RECALL_DISCOMFORT, OTHER
    }

    /** 异常处理状态 */
    public enum ExceptionStatus { OPEN, PROCESSING, RESOLVED }

    /** 收费核对状态 */
    public enum BillingStatus { PENDING, CONFIRMED }

    /** 耗材批次状态：在库可用 / 冻结（召回处理中）/ 已耗尽 / 已退回厂家 */
    public enum BatchStatus { ACTIVE, FROZEN, DEPLETED, RETURNED }

    /** 召回单状态 */
    public enum RecallStatus {
        /** 已发起，等待护士回访/库管冻结 */
        OPEN,
        /** 回访处理中（冻结完成、逐例回访） */
        PROCESSING,
        /** 回访全部完成，等待医生复查结论 */
        AWAIT_REVIEW,
        /** 已办结并进入质量与供应商考核 */
        CLOSED
    }

    /** 召回回访状态 */
    public enum FollowUpStatus { PENDING, CONTACTED_NO_DISCOMFORT, DISCOMFORT, UNREACHABLE }

    /** 医生复查结论 */
    public enum ReviewResult { PENDING, NO_REVIEW_NEEDED, RECHECK_SCHEDULED, RECHECK_DONE }

    /** 召回质量定性 */
    public enum QualityVerdict { CONFIRMED_DEFECT, SUSPECTED, QUALIFIED, INCONCLUSIVE }
}
