<template>
  <div v-loading="loading">
    <el-row :gutter="16">
      <!-- 左：召回单列表 -->
      <el-col :span="6">
        <el-card>
          <template #header>
            <div class="head">
              <span>召回单</span>
              <el-tag size="small">{{ recalls.length }}</el-tag>
            </div>
          </template>
          <el-radio-group v-model="statusFilter" size="small" style="margin-bottom:10px" @change="loadRecalls">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="PROCESSING">处理中</el-radio-button>
            <el-radio-button value="CLOSED">已办结</el-radio-button>
          </el-radio-group>
          <div class="recall-list">
            <div v-for="r in recalls" :key="r.id" class="recall-item"
                 :class="{ active: current?.recall?.id === r.id }" @click="select(r.id)">
              <div class="ri-top">
                <b>{{ r.recallNo }}</b>
                <el-tag size="small" :type="tagType('RecallStatus', r.status)">
                  {{ label('RecallStatus', r.status) }}
                </el-tag>
              </div>
              <div class="ri-sub">{{ r.consumableName }} · 批号 {{ r.batchNo }}</div>
              <div class="ri-sub">
                受影响 {{ r.affectedSessionCount }} 条 / {{ r.affectedPatientCount }} 人
                <span v-if="r.status !== 'CLOSED'">· 冻结 {{ r.frozenQty }}</span>
              </div>
            </div>
            <el-empty v-if="!recalls.length" description="暂无召回单" :image-size="60" />
          </div>
        </el-card>
      </el-col>

      <!-- 右：召回处理工作台 -->
      <el-col :span="18">
        <el-card v-if="current">
          <template #header>
            <div class="head">
              <div>
                <b style="font-size:16px">{{ current.recall.recallNo }}</b>
                <el-tag style="margin-left:8px" :type="tagType('RecallStatus', current.recall.status)">
                  {{ label('RecallStatus', current.recall.status) }}
                </el-tag>
                <el-tag style="margin-left:6px" size="small" type="info">
                  {{ current.recall.supplier || '未登记供应商' }}
                </el-tag>
              </div>
              <div>
                <el-button size="small" @click="select(current.recall.id)">刷新</el-button>
                <el-button v-if="current.recall.status !== 'CLOSED'" size="small" type="danger"
                           :disabled="!current.canClose" @click="closeDialog = true">
                  办结并考核
                </el-button>
              </div>
            </div>
          </template>

          <!-- 召回信息 -->
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="耗材">{{ current.recall.consumableCode }} {{ current.recall.consumableName }}</el-descriptions-item>
            <el-descriptions-item label="召回批号"><b style="color:#f56c6c">{{ current.recall.batchNo }}</b></el-descriptions-item>
            <el-descriptions-item label="厂家文号">{{ current.recall.manufacturerDocNo || '—' }}</el-descriptions-item>
            <el-descriptions-item label="生产日期/失效">{{ current.recall.productionDate || '—' }} / {{ current.recall.expiryDate || '—' }}</el-descriptions-item>
            <el-descriptions-item label="冻结未使用">{{ current.recall.frozenQty }}（已退厂 {{ current.recall.returnedQty }}）</el-descriptions-item>
            <el-descriptions-item label="发起人">{{ current.recall.createdBy }} · {{ fmtTime(current.recall.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="召回原因" :span="3">{{ current.recall.reason }}</el-descriptions-item>
            <el-descriptions-item v-if="current.recall.status === 'CLOSED'" label="质量定性" :span="3">
              <el-tag size="small" :type="tagType('QualityVerdict', current.recall.qualityVerdict)">
                {{ label('QualityVerdict', current.recall.qualityVerdict) }}
              </el-tag>
              供应商考核扣分 <b>{{ current.recall.supplierDeduction }}</b>；{{ current.recall.closeNote }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- 进度统计 -->
          <div class="stat-row">
            <div class="stat-chip">受影响治疗 <b>{{ current.followUps.length }}</b></div>
            <div class="stat-chip">待回访 <b class="c-info">{{ stat.PENDING || 0 }}</b></div>
            <div class="stat-chip">无不适 <b class="c-success">{{ stat.CONTACTED_NO_DISCOMFORT || 0 }}</b></div>
            <div class="stat-chip">报告不适 <b class="c-danger">{{ stat.DISCOMFORT || 0 }}</b></div>
            <div class="stat-chip">无法联系 <b class="c-warning">{{ stat.UNREACHABLE || 0 }}</b></div>
            <div class="stat-chip" v-if="current.pendingReview">
              待医生复查 <b class="c-danger">{{ current.pendingReview }}</b>
            </div>
            <el-button v-if="current.recall.status !== 'CLOSED' && !current.canClose" size="small" text
                       type="info">全部回访完成、不适病例复查完成后方可办结</el-button>
          </div>

          <el-alert v-if="current.recall.status !== 'CLOSED'" type="error" :closable="false" show-icon
                    style="margin:8px 0"
                    title="召回处理期间该批号已冻结：库房不能继续发放；已排治疗请逐例更换批次（见操作列），已使用患者由护士电话回访。" />

          <!-- 受影响治疗记录 -->
          <div class="section-title">受影响治疗记录与回访 / 复查</div>
          <div class="filter-line">
            <el-radio-group v-model="fuFilter" size="small">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="PENDING">待回访</el-radio-button>
              <el-radio-button value="DISCOMFORT">报告不适</el-radio-button>
              <el-radio-button value="REVIEW">待复查</el-radio-button>
            </el-radio-group>
          </div>
          <el-table :data="filteredFollowUps" size="small" border max-height="430">
            <el-table-column label="患者" width="90">
              <template #default="{ row }">
                {{ row.patientName }}
                <div class="sub">{{ row.patientPhone }}</div>
              </template>
            </el-table-column>
            <el-table-column label="治疗日期" width="130">
              <template #default="{ row }">
                {{ row.sessionDate }} {{ label('Shift', row.shift) }}
                <div class="sub">{{ row.machineCode }} · {{ row.nurseName || '—' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="治疗状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType('SessionStatus', row.sessionStatus)">
                  {{ label('SessionStatus', row.sessionStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="用量" width="50" align="center">
              <template #default="{ row }">{{ row.usedQty }}</template>
            </el-table-column>
            <el-table-column label="回访" width="105">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType('FollowUpStatus', row.status)">
                  {{ label('FollowUpStatus', row.status) }}
                </el-tag>
                <div v-if="row.contactedBy" class="sub">{{ row.contactedBy }}</div>
              </template>
            </el-table-column>
            <el-table-column label="不适/复查" min-width="170">
              <template #default="{ row }">
                <template v-if="row.discomfort">
                  <el-tag size="small" type="danger">不适</el-tag>
                  <span class="symptom">{{ row.discomfortSymptoms }}</span>
                  <div class="sub" v-if="row.reviewResult !== 'PENDING'">
                    <el-tag size="small" :type="tagType('ReviewResult', row.reviewResult)">
                      {{ label('ReviewResult', row.reviewResult) }}
                    </el-tag>
                    {{ row.reviewedBy }}
                  </div>
                  <div class="sub" v-else style="color:#f56c6c">等待医生复查</div>
                </template>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>
            <el-table-column label="换批" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.batchReplaced" size="small" type="success">
                  已换 {{ row.replacementBatchNo }}
                </el-tag>
                <span v-else-if="['SCHEDULED','ASSESSED'].includes(row.sessionStatus)" class="sub">待更换</span>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="235" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openTreatment(row)">治疗记录</el-button>
                <el-button v-if="canFollow" size="small" text type="warning" @click="openFollow(row)">回访</el-button>
                <el-button v-if="canReview && row.discomfort && row.reviewResult === 'PENDING'"
                           size="small" text type="danger" @click="openReview(row)">复查</el-button>
                <el-button v-if="['SCHEDULED','ASSESSED'].includes(row.sessionStatus) && !row.batchReplaced
                               && current.recall.status !== 'CLOSED'"
                           size="small" text type="success" @click="openReplace(row)">换批</el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 批次追溯流水 -->
          <el-collapse style="margin-top:12px">
            <el-collapse-item title="召回批次出入库追溯流水" name="txns">
              <el-table :data="current.batchTxns" size="small" max-height="280">
                <el-table-column label="类型" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.qty < 0 ? 'warning' : 'success'">
                      {{ label('TxnType', row.type) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="qty" label="数量" width="70" align="right" />
                <el-table-column prop="patientName" label="患者" width="90">
                  <template #default="{ row }">{{ row.patientName || '—' }}</template>
                </el-table-column>
                <el-table-column prop="operatorName" label="操作人" width="100" />
                <el-table-column label="时间" width="140">
                  <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column prop="note" label="备注" show-overflow-tooltip />
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </el-card>
        <el-card v-else>
          <el-empty description="请从左侧选择召回单" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 供应商考核 -->
    <el-card style="margin-top:16px">
      <template #header><span>耗材质量与供应商考核（按已办结召回汇总）</span></template>
      <el-table :data="assessment" size="small" border>
        <el-table-column prop="supplier" label="供应商" min-width="160" />
        <el-table-column prop="recallCount" label="召回次数" width="90" align="center" />
        <el-table-column prop="confirmedDefectCount" label="确认质量缺陷" width="110" align="center" />
        <el-table-column prop="affectedSessionCount" label="累计影响治疗" width="120" align="center" />
        <el-table-column prop="affectedPatientCount" label="累计影响患者" width="120" align="center" />
        <el-table-column label="累计扣分" width="90" align="center">
          <template #default="{ row }"><b :style="row.totalDeduction ? 'color:#f56c6c' : ''">{{ row.totalDeduction }}</b></template>
        </el-table-column>
        <el-table-column label="考核得分（满分100）" width="150" align="center">
          <template #default="{ row }">
            <el-tag :type="row.score >= 90 ? 'success' : row.score >= 70 ? 'warning' : 'danger'">{{ row.score }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近办结" width="150">
          <template #default="{ row }">{{ fmtTime(row.lastClosedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 护士回访 -->
    <el-dialog v-model="followDialog" :title="`护士回访 - ${followTarget?.patientName || ''}`" width="520px">
      <el-descriptions :column="2" border size="small" style="margin-bottom:12px">
        <el-descriptions-item label="治疗">{{ followTarget?.sessionDate }} {{ label('Shift', followTarget?.shift) }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ followTarget?.patientPhone }}</el-descriptions-item>
      </el-descriptions>
      <el-form :model="followForm" label-width="90px">
        <el-form-item label="回访结果">
          <el-radio-group v-model="followForm.status">
            <el-radio value="CONTACTED_NO_DISCOMFORT">已联系·无不适</el-radio>
            <el-radio value="DISCOMFORT">报告不适</el-radio>
            <el-radio value="UNREACHABLE">无法联系</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="followForm.status === 'DISCOMFORT'" label="不适症状">
          <el-input v-model="followForm.symptoms" type="textarea" :rows="2"
                    placeholder="如：发热、寒战、皮疹、恶心……提交后自动转医生复查" />
        </el-form-item>
        <el-form-item label="回访记录">
          <el-input v-model="followForm.note" type="textarea" :rows="2" placeholder="电话回访情况" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="followDialog = false">取消</el-button>
        <el-button type="primary" @click="saveFollow">提交回访</el-button>
      </template>
    </el-dialog>

    <!-- 医生复查 -->
    <el-dialog v-model="reviewDialog" :title="`医生复查 - ${reviewTarget?.patientName || ''}`" width="560px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:10px"
                :title="`患者报告不适：${reviewTarget?.discomfortSymptoms || '（见回访记录）'}。请先点「治疗记录」核对当日机器、护士、耗材批号与透析参数。`" />
      <el-form :model="reviewForm" label-width="90px">
        <el-form-item label="复查结论">
          <el-radio-group v-model="reviewForm.result">
            <el-radio value="NO_REVIEW_NEEDED">无需复查</el-radio>
            <el-radio value="RECHECK_SCHEDULED">已安排复查</el-radio>
            <el-radio value="RECHECK_DONE">复查完成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复查安排">
          <el-input v-model="reviewForm.recheckPlan" type="textarea" :rows="2"
                    placeholder="如：加查血常规/CRP，安排本周六复查透析" />
        </el-form-item>
        <el-form-item label="复查说明">
          <el-input v-model="reviewForm.reviewNote" type="textarea" :rows="2"
                    placeholder="当日机器、护士、耗材批号、透中参数核对结论" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="openTreatment(reviewTarget)">查看治疗记录</el-button>
        <el-button @click="reviewDialog = false">取消</el-button>
        <el-button type="primary" @click="saveReview">提交复查结论</el-button>
      </template>
    </el-dialog>

    <!-- 一键换批 -->
    <el-dialog v-model="replaceDialog" :title="`更换批次 - ${replaceTarget?.patientName || ''}`" width="520px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
                :title="`该治疗预领了召回批号 ${current?.recall.batchNo} ×${replaceTarget?.usedQty}。召回批号退回冻结库存，从下列在库批次重新预领。`" />
      <el-form label-width="90px">
        <el-form-item label="更换为">
          <el-select v-model="replaceBatchId" style="width:100%" placeholder="选择在库批次（近效期优先）">
            <el-option v-for="b in current?.replacementBatches || []" :key="b.id" :value="b.id"
                       :label="`${b.batchNo}｜在库 ${b.stockQty}｜失效 ${b.expiryDate || '—'}`" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replaceDialog = false">取消</el-button>
        <el-button type="success" :disabled="!replaceBatchId" @click="saveReplace">确认换批</el-button>
      </template>
    </el-dialog>

    <!-- 办结 -->
    <el-dialog v-model="closeDialog" title="办结召回并进入供应商考核" width="520px">
      <el-form :model="closeForm" label-width="110px">
        <el-form-item label="质量定性">
          <el-radio-group v-model="closeForm.verdict" @change="onVerdictChange">
            <el-radio v-for="o in options('QualityVerdict')" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="供应商扣分">
          <el-input-number v-model="closeForm.deduction" :min="0" :max="100" />
          <span class="sub"> 基础分 100，累计扣分计入供应商考核</span>
        </el-form-item>
        <el-form-item label="未使用库存">
          <el-checkbox v-model="closeForm.returnUnused">
            将冻结剩余 {{ current?.batch?.stockQty }} 件整批退回厂家（生成召回出库）
          </el-checkbox>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="closeForm.closeNote" type="textarea" :rows="3"
                    placeholder="处置措施、整改要求、考核结论" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog = false">取消</el-button>
        <el-button type="danger" @click="saveClose">确认办结</el-button>
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

const route = useRoute()
const router = useRouter()
const auth = JSON.parse(localStorage.getItem('hd_user') || 'null')
const role = auth?.role
const canFollow = ['ADMIN', 'NURSE'].includes(role)
const canReview = ['ADMIN', 'DOCTOR'].includes(role)

const loading = ref(false)
const recalls = ref([])
const current = ref(null)
const assessment = ref([])
const statusFilter = ref('')
const fuFilter = ref('')

const followDialog = ref(false)
const reviewDialog = ref(false)
const replaceDialog = ref(false)
const closeDialog = ref(false)
const followTarget = ref(null)
const reviewTarget = ref(null)
const replaceTarget = ref(null)
const replaceBatchId = ref(null)
const followForm = reactive({ status: 'CONTACTED_NO_DISCOMFORT', symptoms: '', note: '' })
const reviewForm = reactive({ result: 'RECHECK_SCHEDULED', recheckPlan: '', reviewNote: '' })
const closeForm = reactive({ verdict: 'CONFIRMED_DEFECT', deduction: 20, returnUnused: true, closeNote: '' })

const stat = computed(() => current.value?.followStats || {})
const filteredFollowUps = computed(() => {
  const list = current.value?.followUps || []
  if (!fuFilter.value) return list
  if (fuFilter.value === 'REVIEW') {
    return list.filter(f => f.discomfort && f.reviewResult === 'PENDING')
  }
  if (fuFilter.value === 'PENDING') return list.filter(f => f.status === 'PENDING')
  if (fuFilter.value === 'DISCOMFORT') return list.filter(f => f.discomfort)
  return list
})

async function loadRecalls() {
  loading.value = true
  try {
    const data = await api.recalls(statusFilter.value === 'CLOSED' ? 'CLOSED' : undefined)
    // 「处理中」= 所有未办结（PROCESSING / AWAIT_REVIEW / OPEN）
    recalls.value = statusFilter.value === 'PROCESSING'
      ? data.filter(r => r.status !== 'CLOSED')
      : data
  } finally {
    loading.value = false
  }
}

async function select(id) {
  current.value = await api.recallDetail(id)
  fuFilter.value = ''
}

function openTreatment(row) {
  router.push({ path: '/treatment', query: { sessionId: row.sessionId } })
}

function openFollow(row) {
  followTarget.value = row
  Object.assign(followForm, {
    status: row.status === 'PENDING' ? 'CONTACTED_NO_DISCOMFORT' : row.status,
    symptoms: row.discomfortSymptoms || '', note: row.followNote || ''
  })
  followDialog.value = true
}

async function saveFollow() {
  await api.recallFollowUp(current.value.recall.id, followTarget.value.id, { ...followForm })
  ElMessage.success(followForm.status === 'DISCOMFORT' ? '已记录不适并转医生复查' : '回访已提交')
  followDialog.value = false
  await select(current.value.recall.id)
}

function openReview(row) {
  reviewTarget.value = row
  Object.assign(reviewForm, {
    result: row.reviewResult === 'PENDING' ? 'RECHECK_SCHEDULED' : row.reviewResult,
    recheckPlan: row.recheckPlan || '', reviewNote: row.reviewNote || ''
  })
  reviewDialog.value = true
}

async function saveReview() {
  await api.recallReview(current.value.recall.id, reviewTarget.value.id, { ...reviewForm })
  ElMessage.success('复查结论已提交')
  reviewDialog.value = false
  await select(current.value.recall.id)
}

function openReplace(row) {
  replaceTarget.value = row
  replaceBatchId.value = current.value.replacementBatches[0]?.id || null
  replaceDialog.value = true
}

async function saveReplace() {
  await api.recallReplace(current.value.recall.id, replaceTarget.value.id, replaceBatchId.value)
  ElMessage.success('已更换批次：召回批号退回冻结，新批次已重新预领')
  replaceDialog.value = false
  await select(current.value.recall.id)
}

function onVerdictChange(v) {
  const map = { CONFIRMED_DEFECT: 20, SUSPECTED: 8, INCONCLUSIVE: 3, QUALIFIED: 0 }
  closeForm.deduction = map[v] ?? 0
}

async function saveClose() {
  await api.closeRecall(current.value.recall.id, { ...closeForm })
  ElMessage.success('召回已办结，结果已计入耗材质量与供应商考核')
  closeDialog.value = false
  await Promise.all([loadRecalls(), select(current.value.recall.id), loadAssessment()])
}

async function loadAssessment() {
  assessment.value = await api.supplierAssessment()
}

onMounted(async () => {
  await loadRecalls()
  await loadAssessment()
  const qid = route.query.recallId
  if (qid) await select(Number(qid))
  else if (recalls.value.length) await select(recalls.value[0].id)
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.recall-list { max-height: 70vh; overflow-y: auto; }
.recall-item { padding: 9px 10px; border: 1px solid #e4e7ed; border-radius: 6px; margin-bottom: 8px; cursor: pointer; }
.recall-item:hover { border-color: #409eff; }
.recall-item.active { border-color: #409eff; background: #ecf5ff; }
.ri-top { display: flex; justify-content: space-between; align-items: center; }
.ri-sub { font-size: 12px; color: #909399; margin-top: 3px; }
.stat-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 12px; }
.stat-chip { background: #f4f6fa; border-radius: 14px; padding: 4px 12px; font-size: 13px; color: #606266; }
.c-info { color: #909399; } .c-success { color: #67c23a; } .c-danger { color: #f56c6c; } .c-warning { color: #e6a23c; }
.section-title { font-weight: 600; margin: 14px 0 8px; color: #303133; }
.filter-line { margin-bottom: 8px; }
.sub { font-size: 12px; color: #909399; }
.symptom { font-size: 12px; color: #f56c6c; margin-left: 4px; }
</style>
