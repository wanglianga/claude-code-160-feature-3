<template>
  <div>
    <el-card>
      <div class="toolbar">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        <el-radio-group v-model="shift" @change="loadBoard">
          <el-radio-button value="AM">上午班</el-radio-button>
          <el-radio-button value="PM">下午班</el-radio-button>
          <el-radio-button value="EVE">晚班</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :icon="Refresh" @click="loadBoard">刷新</el-button>
        <div class="spacer" />
        <el-checkbox v-model="ignorePattern" style="margin-right:12px">忽略固定透析日</el-checkbox>
        <el-button type="success" :icon="MagicStick" :loading="generating" @click="doGenerate">
          自动排班
        </el-button>
        <el-button :icon="Plus" @click="addDialog = true">手动加排</el-button>
      </div>

      <el-alert v-if="recallWarningCount" type="error" :closable="false" show-icon class="gen-result">
        <template #title>
          有 <b>{{ recallWarningCount }}</b> 条已排治疗预领了召回冻结批次，库房已停发，请前往
          <el-link type="primary" :underline="false" @click="$router.push('/recalls')">批次召回</el-link>
          为患者更换批次
        </template>
      </el-alert>

      <el-alert v-if="generateResult" type="success" :closable="true" class="gen-result"
                @close="generateResult = null">
        <template #title>
          已生成 {{ generateResult.created }} 条治疗单
          <span v-if="generateResult.unassigned?.length" style="color:#f56c6c">
            ；未能安排：{{ generateResult.unassigned.join('、') }}
          </span>
        </template>
      </el-alert>

      <el-table :data="sessions" v-loading="loading" border stripe>
        <el-table-column label="机位" width="90" fixed>
          <template #default="{ row }">
            <el-tag effect="dark" :color="zoneColor(row.machine.zone)" style="border:none">
              {{ row.machine.code }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="顺序" width="60" align="center">
          <template #default="{ row }">{{ row.seqNo }}</template>
        </el-table-column>
        <el-table-column label="患者" width="100">
          <template #default="{ row }">
            <el-link type="primary" @click="openDetail(row)">{{ row.patient.name }}</el-link>
            <el-tooltip v-for="w in recallWarnings[row.id] || []" :key="w.recallId" placement="top">
              <template #content>
                该治疗预领了召回批号 {{ w.batchNo }}（{{ w.consumableName }} ×{{ w.usedQty }}），
                库房停发，请更换批次
              </template>
              <el-tag size="small" type="danger" class="recall-tag"
                      @click.stop="gotoRecall(w.recallId)">召回换批</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="感染标记" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('InfectionMarker', row.patient.infectionMarker)">
              {{ label('InfectionMarker', row.patient.infectionMarker) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="70" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('RiskLevel', row.patient.riskLevel)">
              {{ label('RiskLevel', row.patient.riskLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="计划开始" width="100">
          <template #default="{ row }">{{ fmtTime(row.plannedStart).slice(11) }}</template>
        </el-table-column>
        <el-table-column label="时长" width="70" align="center">
          <template #default="{ row }">{{ row.plannedDurationMin }}分</template>
        </el-table-column>
        <el-table-column label="接送" width="110">
          <template #default="{ row }">
            {{ label('TransportMode', row.patient.transportMode) }}
            <div v-if="row.patient.transportWindow" class="sub">{{ row.patient.transportWindow }}</div>
          </template>
        </el-table-column>
        <el-table-column label="责任护士" width="90">
          <template #default="{ row }">{{ row.nurseName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="95" align="center">
          <template #default="{ row }">
            <el-tag :type="tagType('SessionStatus', row.status)">
              {{ label('SessionStatus', row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openDetail(row)">治疗单</el-button>
            <el-button v-if="canMove(row)" size="small" text type="warning" @click="openMove(row)">调班</el-button>
            <el-button v-if="canMove(row)" size="small" text type="danger" @click="markLate(row)">迟到</el-button>
            <el-button v-if="canMove(row)" size="small" text type="info" @click="markLeave(row)">请假</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该班次暂无排班，点击右上角「自动排班」生成" />
        </template>
      </el-table>
    </el-card>

    <!-- 调班对话框：先看影响，再确认 -->
    <el-dialog v-model="moveDialog" title="调班（影响分析）" width="720px">
      <el-form label-width="90px" inline>
        <el-form-item label="患者">
          <b>{{ moveTarget?.patient.name }}</b>
        </el-form-item>
        <el-form-item label="目标机位">
          <el-select v-model="moveForm.machineId" style="width:180px" @change="analyze">
            <el-option v-for="m in machines" :key="m.id" :value="m.id"
                       :label="`${m.code}（${label('MachineZone', m.zone)}）`"
                       :disabled="m.status === 'FAULT' || m.status === 'MAINTENANCE'" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="moveForm.date" type="date" value-format="YYYY-MM-DD"
                          style="width:150px" :clearable="false" @change="analyze" />
        </el-form-item>
        <el-form-item label="班次">
          <el-select v-model="moveForm.shift" style="width:110px" @change="analyze">
            <el-option v-for="o in options('Shift')" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </el-form-item>
      </el-form>

      <div v-if="impact" class="impact">
        <el-alert v-if="impact.ok" type="success" :closable="false" title="调班无冲突，可执行" />
        <template v-else>
          <el-alert v-for="(w, i) in impact.warnings" :key="'w' + i" type="error" :closable="false" :title="w" />
          <el-alert v-for="(c, i) in impact.compressedDisinfection" :key="'c' + i" type="warning" :closable="false"
                    :title="`机位 ${c.machineCode}：${c.prevPatient} 与 ${c.currPatient} 之间消毒间隔仅 ${c.gapMinutes} 分钟（要求 ${c.requiredMinutes} 分钟）`" />
        </template>
        <el-descriptions :column="2" border size="small" style="margin-top:10px">
          <el-descriptions-item label="预计上机时间">{{ fmtTime(impact.estimatedStart) }}</el-descriptions-item>
          <el-descriptions-item label="受影响患者数">{{ impact.affectedPatients.length }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="impact.affectedPatients.length" class="impact-block">
          <div class="impact-title">目标机位当日已有治疗（受影响患者）：</div>
          <el-tag v-for="p in impact.affectedPatients" :key="p.sessionId" style="margin:0 6px 6px 0">
            {{ p.patientName }}（{{ label('Shift', p.shift) }} {{ fmtTime(p.plannedStart).slice(11) }}）
          </el-tag>
        </div>
        <div v-if="impact.preDrawnConsumables.length" class="impact-block">
          <div class="impact-title">已预领耗材（调班后请核对）：</div>
          <el-tag v-for="(t, i) in impact.preDrawnConsumables" :key="i" type="warning" style="margin:0 6px 6px 0">
            {{ t.patientName }} - {{ t.consumableName }} ×{{ t.qty }}
          </el-tag>
        </div>
      </div>

      <template #footer>
        <el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!impact || impact.warnings?.length" :loading="moving"
                   @click="confirmMove">确认调班</el-button>
      </template>
    </el-dialog>

    <!-- 手动加排 -->
    <el-dialog v-model="addDialog" title="手动加排治疗单" width="480px">
      <el-form label-width="90px">
        <el-form-item label="患者">
          <el-select v-model="addForm.patientId" filterable style="width:100%">
            <el-option v-for="p in patientOptions" :key="p.id" :value="p.id"
                       :label="`${p.name}（${label('InfectionMarker', p.infectionMarker)}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="机位">
          <el-select v-model="addForm.machineId" style="width:100%">
            <el-option v-for="m in machines" :key="m.id" :value="m.id"
                       :label="`${m.code}（${label('MachineZone', m.zone)}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="addForm.date" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
        <el-form-item label="班次">
          <el-select v-model="addForm.shift">
            <el-option v-for="o in options('Shift')" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" @click="doAdd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, MagicStick, Plus } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, options, fmtTime } from '../utils/labels'
import dayjs from 'dayjs'

const router = useRouter()
const date = ref(dayjs().format('YYYY-MM-DD'))
const shift = ref('AM')
const loading = ref(false)
const generating = ref(false)
const sessions = ref([])
const machines = ref([])
const ignorePattern = ref(false)
const generateResult = ref(null)
const recallWarnings = ref({})
const recallWarningCount = computed(() => Object.keys(recallWarnings.value).length)

const moveDialog = ref(false)
const moveTarget = ref(null)
const moveForm = reactive({ machineId: null, date: '', shift: 'AM' })
const impact = ref(null)
const moving = ref(false)

const addDialog = ref(false)
const addForm = reactive({ patientId: null, machineId: null, date: dayjs().format('YYYY-MM-DD'), shift: 'AM' })
const patientOptions = ref([])

const zoneColors = { NORMAL: '#67c23a', HBV: '#e6a23c', HCV: '#b37feb', ISOLATION: '#f56c6c' }
const zoneColor = (z) => zoneColors[z] || '#909399'

const canMove = (row) => ['SCHEDULED', 'ASSESSED'].includes(row.status)

async function loadBoard() {
  loading.value = true
  try {
    const data = await api.board(date.value, shift.value)
    sessions.value = data.sessions
    machines.value = data.machines
    recallWarnings.value = data.recallWarnings || {}
  } finally {
    loading.value = false
  }
}

function gotoRecall(recallId) {
  router.push({ path: '/recalls', query: { recallId } })
}

async function doGenerate() {
  generating.value = true
  try {
    generateResult.value = await api.generate(date.value, shift.value, ignorePattern.value)
    await loadBoard()
  } finally {
    generating.value = false
  }
}

function openMove(row) {
  moveTarget.value = row
  moveForm.machineId = row.machine.id
  moveForm.date = row.sessionDate
  moveForm.shift = row.shift
  impact.value = null
  moveDialog.value = true
  analyze()
}

async function analyze() {
  if (!moveForm.machineId) return
  impact.value = await api.impact(moveTarget.value.id, moveForm.machineId, moveForm.date, moveForm.shift)
}

async function confirmMove() {
  moving.value = true
  try {
    await api.moveSession(moveTarget.value.id, moveForm.machineId, moveForm.date, moveForm.shift)
    ElMessage.success('调班成功')
    moveDialog.value = false
    await loadBoard()
  } finally {
    moving.value = false
  }
}

async function markLate(row) {
  const { value } = await ElMessageBox.prompt('迟到说明（可留空）', `患者 ${row.patient.name} 迟到`, {
    confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '如：班车晚点 20 分钟'
  })
  await api.late(row.id, value || '')
  ElMessage.success('已记录迟到并上报异常')
  await loadBoard()
}

async function markLeave(row) {
  const { value } = await ElMessageBox.prompt('请假原因', `患者 ${row.patient.name} 临时请假`, {
    confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '如：家中有事'
  })
  await api.leave(row.id, value || '')
  ElMessage.success('已标记请假，预领耗材已归还')
  await loadBoard()
}

function openDetail(row) {
  router.push({ path: '/treatment', query: { sessionId: row.id } })
}

async function doAdd() {
  if (!addForm.patientId || !addForm.machineId) {
    ElMessage.warning('请选择患者和机位')
    return
  }
  await api.createSession(addForm)
  ElMessage.success('已加排')
  addDialog.value = false
  await loadBoard()
}

onMounted(async () => {
  await loadBoard()
  patientOptions.value = (await api.patients()).filter(p => p.active)
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.gen-result { margin-bottom: 12px; }
.sub { font-size: 12px; color: #909399; }
.impact { display: flex; flex-direction: column; gap: 8px; }
.impact-block { margin-top: 4px; }
.impact-title { font-size: 13px; color: #606266; margin-bottom: 6px; }
.recall-tag { margin-left: 4px; cursor: pointer; }
</style>
