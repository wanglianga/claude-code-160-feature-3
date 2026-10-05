/** 枚举中文标签与标签颜色（与后端 /api/meta/enums 保持一致） */

export const LABELS = {
  InfectionMarker: { NONE: '无', HBV: '乙肝', HCV: '丙肝', TP: '梅毒', OTHER: '其他传染' },
  RiskLevel: { LOW: '低危', MEDIUM: '中危', HIGH: '高危' },
  AccessType: { AVF: '自体动静脉内瘘', AVG: '移植物内瘘', CATHETER: '带 cuff 导管' },
  TransportMode: { SELF: '自行来院', FAMILY: '家属接送', SHUTTLE: '班车接送' },
  Shift: { AM: '上午班', PM: '下午班', EVE: '晚班' },
  MachineZone: { NORMAL: '普通区', HBV: '乙肝区', HCV: '丙肝区', ISOLATION: '隔离区' },
  MachineStatus: { IDLE: '空闲', IN_USE: '使用中', DISINFECTING: '消毒中', FAULT: '故障', MAINTENANCE: '维护中' },
  SessionStatus: {
    SCHEDULED: '已排班', ASSESSED: '已评估', ON_DIALYSIS: '透析中', OFF_DIALYSIS: '已下机',
    FINISHED: '已完成', ABSENT: '请假/缺席', CANCELLED: '已取消'
  },
  AccessCondition: { NORMAL: '正常', REDNESS: '红肿', SWELLING: '肿胀', BRUIT_WEAK: '震颤减弱', INFECTION: '感染迹象' },
  DepartureMode: { SELF: '自行离开', FAMILY: '家属接回', SHUTTLE: '班车送回', WHEELCHAIR: '轮椅', STRETCHER: '平车' },
  DisinfectionType: { DAILY: '日常消毒', HEAT: '热消毒', CITRIC_ACID: '柠檬酸消毒', FILTER_CHANGE: '滤芯更换', WATER_TREATMENT: '水处理维护', WATER_TEST: '水质检测' },
  DisinfectionResult: { PASS: '合格', FAIL: '不合格' },
  TxnType: { PRE_DRAW: '预领', USE: '使用', RETURN: '归还', WASTE: '损耗', RESTOCK: '入库', BATCH_IN: '批次入库', RECALL_OUT: '召回出库' },
  ExceptionType: {
    LATE: '患者迟到', LEAVE: '临时请假', INFECTION_CHANGE: '感染标记变化', MACHINE_FAULT: '机器故障',
    CONSUMABLE_SHORTAGE: '耗材不足', HYPOTENSION: '透析中低血压', ORDER_CHANGE: '医嘱调整',
    CONSUMABLE_RECALL: '耗材召回', RECALL_DISCOMFORT: '召回回访不适', OTHER: '其他'
  },
  ExceptionStatus: { OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决' },
  BillingStatus: { PENDING: '待核对', CONFIRMED: '已核对' },
  BatchStatus: { ACTIVE: '在库可用', FROZEN: '召回冻结', DEPLETED: '已耗尽', RETURNED: '已退厂家' },
  RecallStatus: { OPEN: '已发起', PROCESSING: '回访处理中', AWAIT_REVIEW: '待复查/办结', CLOSED: '已办结' },
  FollowUpStatus: { PENDING: '待回访', CONTACTED_NO_DISCOMFORT: '已联系无不适', DISCOMFORT: '报告不适', UNREACHABLE: '无法联系' },
  ReviewResult: { PENDING: '待复查', NO_REVIEW_NEEDED: '无需复查', RECHECK_SCHEDULED: '已安排复查', RECHECK_DONE: '复查完成' },
  QualityVerdict: { CONFIRMED_DEFECT: '确认质量缺陷', SUSPECTED: '疑似质量问题', QUALIFIED: '复检合格', INCONCLUSIVE: '无法判定' }
}

export const TAG_TYPES = {
  MachineStatus: { IDLE: 'success', IN_USE: 'primary', DISINFECTING: 'warning', FAULT: 'danger', MAINTENANCE: 'info' },
  SessionStatus: {
    SCHEDULED: 'info', ASSESSED: 'warning', ON_DIALYSIS: 'primary', OFF_DIALYSIS: 'success',
    FINISHED: 'success', ABSENT: 'danger', CANCELLED: 'info'
  },
  ExceptionStatus: { OPEN: 'danger', PROCESSING: 'warning', RESOLVED: 'success' },
  BillingStatus: { PENDING: 'warning', CONFIRMED: 'success' },
  RiskLevel: { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' },
  InfectionMarker: { NONE: 'success', HBV: 'warning', HCV: 'warning', TP: 'danger', OTHER: 'danger' },
  DisinfectionResult: { PASS: 'success', FAIL: 'danger' },
  BatchStatus: { ACTIVE: 'success', FROZEN: 'danger', DEPLETED: 'info', RETURNED: 'warning' },
  RecallStatus: { OPEN: 'danger', PROCESSING: 'warning', AWAIT_REVIEW: 'warning', CLOSED: 'success' },
  FollowUpStatus: { PENDING: 'info', CONTACTED_NO_DISCOMFORT: 'success', DISCOMFORT: 'danger', UNREACHABLE: 'warning' },
  ReviewResult: { PENDING: 'danger', NO_REVIEW_NEEDED: 'info', RECHECK_SCHEDULED: 'warning', RECHECK_DONE: 'success' },
  QualityVerdict: { CONFIRMED_DEFECT: 'danger', SUSPECTED: 'warning', QUALIFIED: 'success', INCONCLUSIVE: 'info' }
}

export function label(group, value) {
  return LABELS[group]?.[value] ?? value ?? '-'
}

export function tagType(group, value) {
  return TAG_TYPES[group]?.[value] ?? 'info'
}

/** 下拉选项辅助 */
export function options(group) {
  return Object.entries(LABELS[group] || {}).map(([value, text]) => ({ value, label: text }))
}

export function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '-'
}

export function fmtDate(t) {
  return t ? String(t).slice(0, 10) : '-'
}
