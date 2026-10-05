<template>
  <div>
    <el-card>
      <template #header>
        <div class="head">
          <el-radio-group v-model="tab">
            <el-radio-button value="recalls">召回单</el-radio-button>
            <el-radio-button value="suppliers">供应商考核</el-radio-button>
          </el-radio-group>
          <el-button v-if="canManage && tab === 'recalls'" type="danger" :icon="Warning" @click="openCreate">
            发起召回
          </el-button>
        </div>
      </template>

      <!-- 召回单列表 -->
      <el-table v-if="tab === 'recalls'" :data="recalls" v-loading="loading" border stripe size="small"
                @row-click="openDetail" style="cursor:pointer">
        <el-table-column prop="recallNo" label="召回单号" width="140" />
        <el-table-column prop="consumableName" label="耗材" width="130" />
        <el-table-column prop="batchNo" label="批号" width="120" />
        <el-table-column prop="supplier" label="供应商" width="100" />
        <el-table-column prop="reason" label="召回原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('RecallStatus', row.status)">
              {{ label('RecallStatus', row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回访" width="80" align="center">
          <template #default="{ row }">{{ row.followupDone }}/{{ row.followupTotal }}</template>
        </el-table-column>
        <el-table-column label="不适" width="60" align="center">
          <template #default="{ row }">
            <span :style="row.discomfortCount > 0 ? 'color:#f56c6c;font-weight:700' : ''">
              {{ row.discomfortCount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="冻结库存" width="80" align="center">
          <template #default="{ row }">{{ row.status === 'CLOSED' ? '-' : row.frozenRemaining }}</template>
        </el-table-column>
        <el-table-column label="发起" width="150">
          <template #default="{ row }">{{ row.createdBy }}<div class="sub">{{ fmtTime(row.createdAt) }}</div></template>
        </el-table-column>
        <el-table-column label="处理结果" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.outcome" size="small" :type="row.outcome === 'CONFIRMED' ? 'danger' : 'success'">
              {{ label('RecallOutcome', row.outcome) }}
            </el-tag>
            <span v-else class="sub">处理中</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 供应商考核 -->
      <div v-else>
        <el-alert type="info" :closable="false" style="margin-bottom:12px"
                  title="质量分 = 100 - 20×确认召回次数 - 5×回访不适例数；召回处理结果自动计入考核" />
        <el-table :data="suppliers" v-loading="loadingSuppliers" border stripe size="small">
          <el-table-column prop="supplier" label="供应商" width="120" />
          <el-table-column label="质量分" width="160">
            <template #default="{ row }">
              <el-progress :percentage="row.qualityScore" :stroke-width="14"
                           :color="row.qualityScore >= 90 ? '#67c23a' : row.qualityScore >= 75 ? '#e6a23c' : '#f56c6c'" />
            </template>
          </el-table-column>
          <el-table-column label="等级" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.grade === 'A' ? 'success' : row.grade === 'B' ? 'warning' : 'danger'">
                {{ row.grade }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="batchCount" label="批次数" width="70" align="center" />
          <el-table-column prop="confirmedRecallCount" label="确认召回" width="80" align="center" />
          <el-table-column prop="activeRecallCount" label="进行中召回" width="90" align="center" />
          <el-table-column prop="followupTotal" label="回访人次" width="80" align="center" />
          <el-table-column label="回访不适" width="80" align="center">
            <template #default="{ row }">
              <span :style="row.discomfortCount > 0 ? 'color:#f56c6c;font-weight:700' : ''">
                {{ row.discomfortCount }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="reviewNeededCount" label="需复查" width="70" align="center" />
          <el-table-column label="召回处理结果" min-width="260">
            <template #default="{ row }">
              <div v-for="c in row.closedRecalls" :key="c.recallNo" class="closed-item">
                <el-tag size="small" :type="c.outcome === 'CONFIRMED' ? 'danger' : 'success'">
                  {{ label('RecallOutcome', c.outcome) }}
                </el-tag>
                <b>{{ c.recallNo }}</b>（{{ c.consumableName }} {{ c.batchNo }}）：{{ c.conclusion }}
              </div>
              <span v-if="!row.closedRecalls.length" class="sub">暂无召回记录</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 发起召回 -->
    <el-dialog v-model="createDialog" title="发起耗材召回" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="发起后：批次库存立即冻结禁止发放；按出库/使用记录生成受影响治疗的回访任务；已排班治疗提示更换批次" />
      <el-form label-width="90px">
        <el-form-item label="耗材">
          <el-select v-model="createForm.consumableId" style="width:100%" @change="loadBatches">
            <el-option v-for="c in consumables" :key="c.id" :value="c.id" :label="`${c.name}（${c.spec || '-'}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="召回批次">
          <el-select v-model="createForm.batchId" style="width:100%" :disabled="!createForm.consumableId">
            <el-option v-for="b in normalBatches" :key="b.id" :value="b.id"
                       :label="`${b.batchNo}（${b.supplier}，剩余 ${b.remainingQty}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="召回原因">
          <el-input v-model="createForm.reason" type="textarea" :rows="3"
                    placeholder="如：厂家通报批次膜材存在渗漏风险，主动召回" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="danger" :disabled="!createForm.batchId || !createForm.reason" @click="doCreate">
          确认召回并冻结库存
        </el-button>
      </template>
    </el-dialog>

    <!-- 召回详情 -->
    <el-drawer v-model="detailDrawer" size="72%" :with-header="false">
      <div v-if="detail" class="detail">
        <div class="d-head">
          <div>
            <b style="font-size:17px">{{ detail.recallNo }}</b>
            <el-tag style="margin-left:8px" :type="tagType('RecallStatus', detail.status)">
              {{ label('RecallStatus', detail.status) }}
            </el-tag>
            <el-tag v-if="detail.outcome" style="margin-left:6px" size="small"
                    :type="detail.outcome === 'CONFIRMED' ? 'danger' : 'success'">
              {{ label('RecallOutcome', detail.outcome) }}
            </el-tag>
          </div>
          <el-button size="small" @click="reloadDetail">刷新</el-button>
        </div>

        <el-descriptions :column="4" border size="small" style="margin-top:12px">
          <el-descriptions-item label="耗材">{{ detail.consumableName }}</el-descriptions-item>
          <el-descriptions-item label="批号">{{ detail.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplier }}</el-descriptions-item>
          <el-descriptions-item label="批次状态">
            <el-tag size="small" :type="tagType('BatchStatus', detail.batchStatus)">
              {{ label('BatchStatus', detail.batchStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="召回原因" :span="3">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item label="冻结库存">
            <b :style="detail.frozenRemaining > 0 ? 'color:#e6a23c' : ''">{{ detail.frozenRemaining }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detail.createdBy }}（{{ fmtTime(detail.createdAt) }}）</el-descriptions-item>
          <el-descriptions-item v-if="detail.closedAt" label="关闭">
            {{ detail.closedBy }}（{{ fmtTime(detail.closedAt) }}）
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.conclusion" label="处理结果" :span="2">
            {{ detail.conclusion }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 受影响治疗回访 -->
        <div class="section-title">
          受影响治疗记录与患者回访（{{ detail.followups.length }}）
          <span class="sub">回访发现不适后，医生可从治疗记录直接查看当日机器、护士、耗材与透析参数</span>
        </div>
        <el-table :data="detail.followups" size="small" border>
          <el-table-column prop="patientName" label="患者" width="90" />
          <el-table-column label="治疗日期" width="150">
            <template #default="{ row }">
              {{ row.sessionDate || '-' }} {{ row.shift ? label('Shift', row.shift) : '' }}
            </template>
          </el-table-column>
          <el-table-column label="回访" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType('FollowupStatus', row.status)">
                {{ label('FollowupStatus', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="是否不适" width="130" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.discomfort === true" size="small" type="danger">不适</el-tag>
              <span v-else-if="row.discomfort === false">无不适</span>
              <span v-else class="sub">-</span>
              <div v-if="row.symptoms" class="sub">{{ row.symptoms }}</div>
            </template>
          </el-table-column>
          <el-table-column label="复查评估" width="130" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.reviewNeeded === true" size="small" type="warning">需复查</el-tag>
              <span v-else-if="row.reviewNeeded === false">无需复查</span>
              <span v-else class="sub">待评估</span>
              <div v-if="row.reviewNote" class="sub">{{ row.reviewNote }}</div>
            </template>
          </el-table-column>
          <el-table-column label="回访人" width="90">
            <template #default="{ row }">{{ row.nurseName || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="220">
            <template #default="{ row }">
              <template v-if="detail.status !== 'CLOSED'">
                <el-button v-if="canFollowup" size="small" text type="primary" @click="openFollowup(row)">
                  回访登记
                </el-button>
                <el-button v-if="canReview" size="small" text type="warning" @click="openReview(row)">
                  复查评估
                </el-button>
              </template>
              <el-button v-if="row.sessionId" size="small" text type="success" @click="gotoSession(row.sessionId)">
                查看治疗记录
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!detail.followups.length" description="无已治疗患者受影响" :image-size="60" />

        <!-- 待更换批次 -->
        <div class="section-title">
          已排班治疗更换批次（{{ detail.pendingReplacement.length }}）
          <el-button v-if="canManage && detail.status !== 'CLOSED' && detail.pendingReplacement.length"
                     type="primary" size="small" style="margin-left:10px" @click="doReplaceAll">
            一键全部更换
          </el-button>
        </div>
        <el-table v-if="detail.pendingReplacement.length" :data="detail.pendingReplacement" size="small" border>
          <el-table-column prop="patientName" label="患者" width="100" />
          <el-table-column prop="sessionDate" label="日期" width="110" />
          <el-table-column label="班次" width="80">
            <template #default="{ row }">{{ label('Shift', row.shift) }}</template>
          </el-table-column>
          <el-table-column prop="machineCode" label="机位" width="70" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="tagType('SessionStatus', row.status)">
                {{ label('SessionStatus', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="outstandingQty" label="召回批次耗材" width="100" align="center" />
          <el-table-column v-if="canManage && detail.status !== 'CLOSED'" label="操作" width="110">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="doReplace(row.sessionId)">更换批次</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-else type="success" :closable="false" title="已排班治疗均已更换为正常批次" />

        <!-- 关闭召回 -->
        <template v-if="canManage && detail.status !== 'CLOSED'">
          <div class="section-title">关闭召回</div>
          <el-form inline class="close-form">
            <el-form-item label="处理结论">
              <el-radio-group v-model="closeForm.outcome">
                <el-radio-button value="CONFIRMED">确认召回，剩余库存退回厂家</el-radio-button>
                <el-radio-button value="LIFTED">解除冻结，批次恢复可用</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="处理结果" style="width:100%">
              <el-input v-model="closeForm.conclusion" type="textarea" :rows="2"
                        placeholder="处理结果将计入耗材质量与供应商考核" style="width:100%" />
            </el-form-item>
            <el-form-item>
              <el-button type="danger" :disabled="!closeForm.conclusion" @click="doClose">关闭召回单</el-button>
              <span v-if="detail.stats.followupDone < detail.stats.followupTotal" class="sub" style="margin-left:8px">
                还有 {{ detail.stats.followupTotal - detail.stats.followupDone }} 条回访未完成
              </span>
            </el-form-item>
          </el-form>
        </template>
      </div>
    </el-drawer>

    <!-- 回访登记 -->
    <el-dialog v-model="followupDialog" :title="`回访登记 - ${followupTarget?.patientName || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="回访结果">
          <el-radio-group v-model="followupForm.status">
            <el-radio-button value="DONE">已回访</el-radio-button>
            <el-radio-button value="UNREACHABLE">联系不上</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="followupForm.status === 'DONE'">
          <el-form-item label="是否不适">
            <el-switch v-model="followupForm.discomfort" active-text="有不适" inactive-text="无不适" />
          </el-form-item>
          <el-form-item v-if="followupForm.discomfort" label="不适症状">
            <el-input v-model="followupForm.symptoms" placeholder="如：透析后皮肤瘙痒、红疹（将自动上报异常）" />
          </el-form-item>
          <el-form-item label="回访备注">
            <el-input v-model="followupForm.note" type="textarea" :rows="2" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="followupDialog = false">取消</el-button>
        <el-button type="primary" @click="saveFollowup">保存</el-button>
      </template>
    </el-dialog>

    <!-- 复查评估 -->
    <el-dialog v-model="reviewDialog" :title="`复查评估 - ${reviewTarget?.patientName || ''}`" width="440px">
      <el-form label-width="90px">
        <el-form-item label="是否复查">
          <el-switch v-model="reviewForm.reviewNeeded" active-text="需要复查" inactive-text="无需复查" />
        </el-form-item>
        <el-form-item label="评估意见">
          <el-input v-model="reviewForm.reviewNote" type="textarea" :rows="3"
                    placeholder="如：安排下次透析前复查皮肤过敏原" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog = false">取消</el-button>
        <el-button type="primary" @click="saveReview">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, fmtTime } from '../utils/labels'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()

const tab = ref('recalls')
const recalls = ref([])
const suppliers = ref([])
const loading = ref(false)
const loadingSuppliers = ref(false)

const createDialog = ref(false)
const consumables = ref([])
const normalBatches = ref([])
const createForm = reactive({ consumableId: null, batchId: null, reason: '' })

const detailDrawer = ref(false)
const detail = ref(null)

const followupDialog = ref(false)
const followupTarget = ref(null)
const followupForm = reactive({ status: 'DONE', discomfort: false, symptoms: '', note: '' })

const reviewDialog = ref(false)
const reviewTarget = ref(null)
const reviewForm = reactive({ reviewNeeded: false, reviewNote: '' })

const closeForm = reactive({ outcome: 'CONFIRMED', conclusion: '' })

const canFollowup = computed(() => ['ADMIN', 'NURSE'].includes(auth.role))
const canReview = computed(() => ['ADMIN', 'DOCTOR'].includes(auth.role))
const canManage = computed(() => ['ADMIN', 'ENGINEER'].includes(auth.role))

async function load() {
  loading.value = true
  try {
    recalls.value = await api.recalls()
  } finally {
    loading.value = false
  }
}

async function loadSuppliers() {
  loadingSuppliers.value = true
  try {
    suppliers.value = await api.supplierStats()
  } finally {
    loadingSuppliers.value = false
  }
}

async function openCreate() {
  createForm.consumableId = null
  createForm.batchId = null
  createForm.reason = ''
  normalBatches.value = []
  consumables.value = await api.consumables()
  createDialog.value = true
}

async function loadBatches() {
  createForm.batchId = null
  const all = await api.consumableBatches(createForm.consumableId)
  normalBatches.value = all.filter(b => b.status === 'NORMAL')
  if (!normalBatches.value.length) ElMessage.warning('该耗材没有可召回的正常批次')
}

async function doCreate() {
  const d = await api.createRecall({ batchId: createForm.batchId, reason: createForm.reason })
  ElMessage.success(`召回单 ${d.recallNo} 已创建：库存已冻结，${d.stats.followupTotal} 条治疗记录待回访，${d.stats.pendingReplacementCount} 条已排班治疗待更换批次`)
  createDialog.value = false
  await load()
  await openDetail({ id: d.id })
}

async function openDetail(row) {
  detail.value = await api.recallDetail(row.id)
  closeForm.outcome = 'CONFIRMED'
  closeForm.conclusion = ''
  detailDrawer.value = true
}

async function reloadDetail() {
  if (detail.value) detail.value = await api.recallDetail(detail.value.id)
  await load()
}

function openFollowup(row) {
  followupTarget.value = row
  Object.assign(followupForm, {
    status: row.status === 'UNREACHABLE' ? 'UNREACHABLE' : 'DONE',
    discomfort: row.discomfort === true,
    symptoms: row.symptoms || '',
    note: row.followupNote || ''
  })
  followupDialog.value = true
}

async function saveFollowup() {
  if (followupForm.status === 'DONE' && followupForm.discomfort && !followupForm.symptoms) {
    ElMessage.warning('请填写不适症状')
    return
  }
  const f = await api.recallFollowup(followupTarget.value.id, { ...followupForm })
  if (f.discomfort) ElMessage.warning('已记录患者不适并自动上报异常，请医生复查评估')
  else ElMessage.success('回访已登记')
  followupDialog.value = false
  await reloadDetail()
}

function openReview(row) {
  reviewTarget.value = row
  Object.assign(reviewForm, {
    reviewNeeded: row.reviewNeeded === true,
    reviewNote: row.reviewNote || ''
  })
  reviewDialog.value = true
}

async function saveReview() {
  await api.recallReview(reviewTarget.value.id, { ...reviewForm })
  ElMessage.success('复查评估已保存')
  reviewDialog.value = false
  await reloadDetail()
}

async function doReplace(sessionId) {
  const d = await api.recallReplace(detail.value.id, sessionId)
  detail.value = d
  ElMessage.success('已退回召回批次并重新预领正常批次')
  await load()
}

async function doReplaceAll() {
  const d = await api.recallReplaceAll(detail.value.id)
  detail.value = d
  if (d.replaceFailed?.length) {
    ElMessage.warning(`已更换 ${d.replacedCount} 条；失败：${d.replaceFailed.join('；')}`)
  } else {
    ElMessage.success(`已为 ${d.replacedCount} 条治疗单更换批次`)
  }
  await load()
}

async function doClose() {
  const action = closeForm.outcome === 'CONFIRMED'
    ? `剩余 ${detail.value.frozenRemaining} 库存将退回厂家，批次转为「已召回」`
    : '批次库存将解除冻结恢复可发放'
  await ElMessageBox.confirm(action + '，处理结果计入供应商考核。确认关闭？', '关闭召回单', {
    confirmButtonText: '确认关闭', cancelButtonText: '取消', type: 'warning'
  })
  const d = await api.recallClose(detail.value.id, { ...closeForm })
  detail.value = d
  ElMessage.success(closeForm.outcome === 'CONFIRMED'
    ? `已关闭：${d.returnedToSupplier} 库存退回厂家`
    : '已关闭：批次解除冻结')
  await load()
  await loadSuppliers()
}

function gotoSession(sessionId) {
  detailDrawer.value = false
  router.push({ path: '/treatment', query: { sessionId } })
}

onMounted(async () => {
  await Promise.all([load(), loadSuppliers()])
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.sub { font-size: 12px; color: #909399; }
.detail { padding: 0 8px 16px; }
.d-head { display: flex; justify-content: space-between; align-items: center; }
.section-title { font-weight: 600; margin: 18px 0 8px; color: #303133; display: flex; align-items: center; gap: 8px; }
.section-title .sub { font-weight: 400; }
.closed-item { margin-bottom: 4px; font-size: 12px; }
.close-form { width: 100%; }
</style>
