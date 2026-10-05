<template>
  <div>
    <el-row :gutter="16">
      <!-- 左：治疗单选择 -->
      <el-col :span="7">
        <el-card>
          <template #header><span>选择治疗单</span></template>
          <div class="picker">
            <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                            style="width:100%" @change="loadSessions" />
            <el-radio-group v-model="shift" style="margin-top:8px" @change="loadSessions">
              <el-radio-button value="AM">上午</el-radio-button>
              <el-radio-button value="PM">下午</el-radio-button>
              <el-radio-button value="EVE">晚班</el-radio-button>
            </el-radio-group>
          </div>
          <div class="session-list" v-loading="loadingList">
            <div v-for="s in sessions" :key="s.id" class="session-item"
                 :class="{ active: current?.session?.id === s.id }" @click="select(s.id)">
              <div class="si-top">
                <b>{{ s.patient.name }}</b>
                <el-tag size="small" :type="tagType('SessionStatus', s.status)">
                  {{ label('SessionStatus', s.status) }}
                </el-tag>
              </div>
              <div class="si-sub">
                {{ s.machine.code }} · {{ label('InfectionMarker', s.patient.infectionMarker) }} ·
                {{ fmtTime(s.plannedStart).slice(11) }}
              </div>
            </div>
            <el-empty v-if="!sessions.length" description="该班次无治疗单" :image-size="60" />
          </div>
        </el-card>
      </el-col>

      <!-- 右：治疗工作台 -->
      <el-col :span="17">
        <el-card v-if="current" v-loading="loadingDetail">
          <template #header>
            <div class="head">
              <div>
                <b style="font-size:16px">{{ current.session.patient.name }}</b>
                <el-tag style="margin-left:8px" :type="tagType('SessionStatus', current.session.status)">
                  {{ label('SessionStatus', current.session.status) }}
                </el-tag>
                <el-tag style="margin-left:6px" size="small"
                        :type="tagType('InfectionMarker', current.session.patient.infectionMarker)">
                  {{ label('InfectionMarker', current.session.patient.infectionMarker) }}
                </el-tag>
                <el-tag style="margin-left:6px" size="small" :type="tagType('RiskLevel', current.session.patient.riskLevel)">
                  {{ label('RiskLevel', current.session.patient.riskLevel) }}
                </el-tag>
              </div>
              <div>
                <el-button v-if="['SCHEDULED','ASSESSED'].includes(current.session.status)"
                           type="primary" size="small" @click="start">上机开始透析</el-button>
                <el-button size="small" @click="reload">刷新</el-button>
              </div>
            </div>
          </template>

          <el-alert v-for="rf in (current.recallFollowUps || [])" :key="rf.id"
                    :type="rf.discomfort ? 'error' : 'warning'" :closable="false" show-icon
                    style="margin-bottom:10px">
            <template #title>
              耗材召回关联：该治疗使用的耗材在召回范围内，
              <el-link type="primary" :underline="false" @click="gotoRecall(rf.recallId)">
                查看召回单 #{{ rf.recallId }}
              </el-link>
              ｜回访：
              <el-tag size="small" :type="tagType('FollowUpStatus', rf.status)">
                {{ label('FollowUpStatus', rf.status) }}
              </el-tag>
              <template v-if="rf.discomfort">
                ｜患者报告不适：{{ rf.discomfortSymptoms }}
                <el-tag size="small" :type="tagType('ReviewResult', rf.reviewResult)" style="margin-left:4px">
                  {{ label('ReviewResult', rf.reviewResult) }}
                </el-tag>
              </template>
            </template>
          </el-alert>

          <el-tabs v-model="tab">
            <!-- 总览 -->
            <el-tab-pane label="治疗单总览" name="overview">
              <el-descriptions :column="3" border size="small">
                <el-descriptions-item label="机位">{{ current.session.machine.code }}（{{ label('MachineZone', current.session.machine.zone) }}）</el-descriptions-item>
                <el-descriptions-item label="日期班次">{{ current.session.sessionDate }} {{ label('Shift', current.session.shift) }}</el-descriptions-item>
                <el-descriptions-item label="计划">{{ fmtTime(current.session.plannedStart).slice(11) }} 起 {{ current.session.plannedDurationMin }} 分钟</el-descriptions-item>
                <el-descriptions-item label="通路">{{ label('AccessType', current.session.patient.accessType) }}</el-descriptions-item>
                <el-descriptions-item label="干体重">{{ current.session.patient.dryWeight }} kg</el-descriptions-item>
                <el-descriptions-item label="抗凝方案">{{ current.session.patient.anticoagPlan }}</el-descriptions-item>
                <el-descriptions-item label="接送">{{ label('TransportMode', current.session.patient.transportMode) }} {{ current.session.patient.transportWindow }}</el-descriptions-item>
                <el-descriptions-item label="责任护士">{{ current.session.nurseName || '-' }}</el-descriptions-item>
                <el-descriptions-item label="实际时间">
                  {{ fmtTime(current.session.actualStartAt) }} ~ {{ fmtTime(current.session.actualEndAt) }}
                </el-descriptions-item>
              </el-descriptions>

              <div class="section-title">关联异常（{{ current.exceptions.length }}）</div>
              <el-timeline v-if="current.exceptions.length" style="padding-left:4px">
                <el-timeline-item v-for="e in current.exceptions" :key="e.id"
                                  :timestamp="fmtTime(e.createdAt)" :type="e.status === 'RESOLVED' ? 'success' : 'danger'">
                  <el-tag size="small" :type="tagType('ExceptionStatus', e.status)">
                    {{ label('ExceptionType', e.type) }}
                  </el-tag>
                  {{ e.description }}
                  <span v-if="e.handleNote" class="sub">｜处理：{{ e.handleNote }}</span>
                </el-timeline-item>
              </el-timeline>
              <div v-else class="empty">无异常</div>

              <div class="section-title">本机位当日消毒记录</div>
              <el-table :data="current.machineDisinfection" size="small">
                <el-table-column label="类型" width="120">
                  <template #default="{ row }">{{ label('DisinfectionType', row.type) }}</template>
                </el-table-column>
                <el-table-column label="结果" width="80">
                  <template #default="{ row }">
                    <el-tag size="small" :type="tagType('DisinfectionResult', row.result)">
                      {{ label('DisinfectionResult', row.result) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="operatorName" label="操作人" width="100" />
                <el-table-column label="时间">
                  <template #default="{ row }">{{ fmtTime(row.doneAt) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <!-- 透前评估 -->
            <el-tab-pane label="透前评估" name="pre">
              <el-form :model="preForm" label-width="110px" class="form">
                <el-row :gutter="12">
                  <el-col :span="8">
                    <el-form-item label="透前体重(kg)">
                      <el-input-number v-model="preForm.weight" :step="0.1" :precision="1" style="width:100%" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="收缩压/舒张压">
                      <div class="bp">
                        <el-input-number v-model="preForm.bpSys" :min="40" :max="260" controls-position="right" style="width:100px" />
                        /
                        <el-input-number v-model="preForm.bpDia" :min="20" :max="160" controls-position="right" style="width:100px" />
                      </div>
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="心率">
                      <el-input-number v-model="preForm.heartRate" :min="30" :max="200" style="width:100%" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="12">
                  <el-col :span="8">
                    <el-form-item label="通路情况">
                      <el-select v-model="preForm.accessCondition" style="width:100%">
                        <el-option v-for="o in options('AccessCondition')" :key="o.value" :value="o.value" :label="o.label" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="调整脱水量">
                      <el-switch v-model="preForm.adjustDehydration" active-text="需要" inactive-text="不需要" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="目标脱水(L)">
                      <el-input-number v-model="preForm.dehydrationTarget" :step="0.1" :precision="1" style="width:100%" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="上次不适">
                  <el-input v-model="preForm.lastDiscomfort" placeholder="上次透析后不适主诉" />
                </el-form-item>
                <el-form-item label="抗凝方案">
                  <el-input v-model="preForm.anticoagPlan" />
                </el-form-item>
                <el-form-item label="备注">
                  <el-input v-model="preForm.note" type="textarea" :rows="2" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="savePre">保存评估</el-button>
                  <span v-if="current.pre" class="sub" style="margin-left:10px">
                    上次由 {{ current.pre.nurseName }} 记录于 {{ fmtTime(current.pre.createdAt) }}
                  </span>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <!-- 透中记录 -->
            <el-tab-pane :label="`透中记录（${current.intra.length}）`" name="intra">
              <div style="margin-bottom:10px">
                <el-button type="primary" size="small" :disabled="current.session.status !== 'ON_DIALYSIS'"
                           @click="intraDialog = true">
                  录入透中记录
                </el-button>
                <span v-if="current.session.status !== 'ON_DIALYSIS'" class="sub" style="margin-left:8px">
                  需先「上机开始透析」
                </span>
              </div>
              <el-table :data="current.intra" size="small" border>
                <el-table-column label="时间" width="100">
                  <template #default="{ row }">{{ fmtTime(row.recordTime).slice(11) }}</template>
                </el-table-column>
                <el-table-column label="血压" width="90">
                  <template #default="{ row }">
                    <span :style="row.bpSys < 90 ? 'color:#f56c6c;font-weight:700' : ''">
                      {{ row.bpSys }}/{{ row.bpDia }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="heartRate" label="心率" width="60" />
                <el-table-column prop="alarm" label="报警" width="110" show-overflow-tooltip />
                <el-table-column label="补液ml" width="70">
                  <template #default="{ row }">{{ row.fluidMl || '-' }}</template>
                </el-table-column>
                <el-table-column prop="bloodDraw" label="抽血" width="100" show-overflow-tooltip />
                <el-table-column prop="discomfort" label="不适" width="120" show-overflow-tooltip />
                <el-table-column prop="orderChange" label="医嘱变更" min-width="140" show-overflow-tooltip />
                <el-table-column prop="nurseName" label="护士" width="80" />
              </el-table>
            </el-tab-pane>

            <!-- 透后记录 -->
            <el-tab-pane label="透后（下机）记录" name="post">
              <el-form :model="postForm" label-width="110px" class="form">
                <el-row :gutter="12">
                  <el-col :span="8">
                    <el-form-item label="下机体重(kg)">
                      <el-input-number v-model="postForm.offWeight" :step="0.1" :precision="1" style="width:100%" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="穿刺点止血">
                      <el-switch v-model="postForm.hemostasisOk" active-text="已止血" inactive-text="未止血" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="止血用时(分)">
                      <el-input-number v-model="postForm.hemostasisMin" :min="0" style="width:100%" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-form-item label="离开方式">
                  <el-radio-group v-model="postForm.departureMode">
                    <el-radio v-for="o in options('DepartureMode')" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="下次排班备注">
                  <el-input v-model="postForm.nextScheduleNote" type="textarea" :rows="2" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :disabled="current.session.status !== 'ON_DIALYSIS' && !current.post"
                           @click="savePost">
                    {{ current.post ? '更新下机记录' : '下机并结束治疗' }}
                  </el-button>
                  <span class="sub" style="margin-left:10px">保存后机器转入「消毒中」，并自动生成待核对账单</span>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <!-- 耗材与费用 -->
            <el-tab-pane label="耗材与费用" name="fee">
              <div class="section-title">本治疗单耗材流水</div>
              <el-table :data="current.consumables" size="small">
                <el-table-column prop="consumableName" label="耗材" min-width="120" />
                <el-table-column label="批号" width="110">
                  <template #default="{ row }">
                    <span v-if="row.batchNo" :style="isRecalledLot(row.batchNo) ? 'color:#f56c6c;font-weight:600' : ''">
                      {{ row.batchNo }}
                    </span>
                    <span v-else class="sub">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="80">
                  <template #default="{ row }">
                    <el-tag size="small">{{ label('TxnType', row.type) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="qty" label="数量" width="70" />
                <el-table-column prop="operatorName" label="操作人" width="90" />
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
                </el-table-column>
              </el-table>
              <div class="section-title">费用核对单</div>
              <el-descriptions v-if="current.billing" :column="4" border size="small">
                <el-descriptions-item label="医保结算">¥ {{ current.billing.insuranceAmount }}</el-descriptions-item>
                <el-descriptions-item label="自费耗材">¥ {{ current.billing.selfPayAmount }}</el-descriptions-item>
                <el-descriptions-item label="补收费用">¥ {{ current.billing.extraCharge }}</el-descriptions-item>
                <el-descriptions-item label="状态">
                  <el-tag size="small" :type="tagType('BillingStatus', current.billing.status)">
                    {{ label('BillingStatus', current.billing.status) }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
              <div v-else class="empty">下机后自动生成</div>
            </el-tab-pane>
          </el-tabs>
        </el-card>
        <el-card v-else>
          <el-empty description="请从左侧选择治疗单" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 透中记录对话框 -->
    <el-dialog v-model="intraDialog" title="录入透中记录" width="560px">
      <el-form :model="intraForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="收缩压">
              <el-input-number v-model="intraForm.bpSys" :min="40" :max="260" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="舒张压">
              <el-input-number v-model="intraForm.bpDia" :min="20" :max="160" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="心率">
              <el-input-number v-model="intraForm.heartRate" :min="30" :max="200" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="机器报警">
          <el-input v-model="intraForm.alarm" placeholder="如：静脉压报警" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="补液(ml)">
              <el-input-number v-model="intraForm.fluidMl" :min="0" :step="50" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="抽血">
              <el-input v-model="intraForm.bloodDraw" placeholder="如：透析中电解质" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="患者不适">
          <el-input v-model="intraForm.discomfort" placeholder="如：头晕、恶心" />
        </el-form-item>
        <el-form-item label="医嘱变更">
          <el-input v-model="intraForm.orderChange" placeholder="填写后自动上报「医嘱调整」异常" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="intraDialog = false">取消</el-button>
        <el-button type="primary" @click="saveIntra">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { label, tagType, options, fmtTime } from '../utils/labels'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const date = ref(dayjs().format('YYYY-MM-DD'))
const shift = ref('AM')
const sessions = ref([])
const current = ref(null)
const loadingList = ref(false)
const loadingDetail = ref(false)
const tab = ref('overview')
const intraDialog = ref(false)

const preForm = reactive({})
const postForm = reactive({})
const intraForm = reactive({ bpSys: 120, bpDia: 75, heartRate: 75, alarm: '', fluidMl: null, bloodDraw: '', discomfort: '', orderChange: '' })

async function loadSessions() {
  loadingList.value = true
  try {
    const data = await api.board(date.value, shift.value)
    sessions.value = data.sessions
  } finally {
    loadingList.value = false
  }
}

async function select(id) {
  loadingDetail.value = true
  try {
    current.value = await api.sessionDetail(id)
    fillForms()
  } finally {
    loadingDetail.value = false
  }
}

function fillForms() {
  const pre = current.value.pre || {}
  Object.assign(preForm, {
    weight: pre.weight ?? current.value.session.patient.dryWeight,
    bpSys: pre.bpSys ?? 130, bpDia: pre.bpDia ?? 80, heartRate: pre.heartRate ?? 75,
    accessCondition: pre.accessCondition ?? 'NORMAL',
    lastDiscomfort: pre.lastDiscomfort ?? '',
    anticoagPlan: pre.anticoagPlan ?? current.value.session.patient.anticoagPlan,
    adjustDehydration: pre.adjustDehydration ?? false,
    dehydrationTarget: pre.dehydrationTarget ?? null,
    note: pre.note ?? ''
  })
  const post = current.value.post || {}
  Object.assign(postForm, {
    offWeight: post.offWeight ?? current.value.session.patient.dryWeight,
    hemostasisOk: post.hemostasisOk ?? true,
    hemostasisMin: post.hemostasisMin ?? 10,
    departureMode: post.departureMode ?? 'SELF',
    nextScheduleNote: post.nextScheduleNote ?? ''
  })
}

async function reload() {
  if (current.value) await select(current.value.session.id)
}

function gotoRecall(recallId) {
  router.push({ path: '/recalls', query: { recallId } })
}

/** 该批号是否出现在本治疗单关联的召回回访中 */
const recalledBatchNos = computed(() => new Set(
  (current.value?.recallFollowUps || []).map(rf => rf.batchNo).filter(Boolean)))
function isRecalledLot(batchNo) {
  return recalledBatchNos.value.has(batchNo)
}

async function start() {
  await api.startSession(current.value.session.id)
  ElMessage.success('已上机，机器状态转为使用中')
  await reload()
}

async function savePre() {
  await api.savePre(current.value.session.id, preForm)
  ElMessage.success('透前评估已保存')
  await reload()
}

async function saveIntra() {
  await api.addIntra(current.value.session.id, intraForm)
  ElMessage.success('透中记录已保存')
  if (intraForm.bpSys && intraForm.bpSys < 90) {
    ElMessage.warning('收缩压低于 90，已自动上报「透析中低血压」异常')
  }
  intraDialog.value = false
  Object.assign(intraForm, { bpSys: 120, bpDia: 75, heartRate: 75, alarm: '', fluidMl: null, bloodDraw: '', discomfort: '', orderChange: '' })
  await reload()
}

async function savePost() {
  await api.savePost(current.value.session.id, postForm)
  ElMessage.success('下机记录已保存，机器转入消毒中，账单已生成')
  await reload()
}

onMounted(async () => {
  await loadSessions()
  const sid = route.query.sessionId
  if (sid) {
    await select(Number(sid))
  } else if (sessions.value.length) {
    await select(sessions.value[0].id)
  }
})
</script>

<style scoped>
.picker { margin-bottom: 10px; }
.session-list { max-height: 62vh; overflow-y: auto; }
.session-item {
  padding: 8px 10px; border: 1px solid #e4e7ed; border-radius: 6px; margin-bottom: 8px; cursor: pointer;
}
.session-item:hover { border-color: #409eff; }
.session-item.active { border-color: #409eff; background: #ecf5ff; }
.si-top { display: flex; justify-content: space-between; align-items: center; }
.si-sub { font-size: 12px; color: #909399; margin-top: 4px; }
.head { display: flex; justify-content: space-between; align-items: center; }
.section-title { font-weight: 600; margin: 16px 0 8px; color: #303133; }
.form { max-width: 900px; }
.bp { display: flex; align-items: center; gap: 6px; }
.sub { font-size: 12px; color: #909399; }
.empty { color: #909399; font-size: 13px; padding: 8px 0; }
</style>
