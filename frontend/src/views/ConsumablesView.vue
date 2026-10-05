<template>
  <div>
    <el-tabs v-model="tab">
      <!-- 库存与出入库 -->
      <el-tab-pane label="库存与出入库" name="stock">
        <el-row :gutter="16">
          <el-col :span="13">
            <el-card>
              <template #header>
                <div class="head">
                  <span>耗材库存</span>
                  <div>
                    <el-button size="small" @click="batchDialog = true">批次入库</el-button>
                    <el-button size="small" type="primary" :icon="Plus" @click="openEdit(null)">新增耗材</el-button>
                  </div>
                </div>
              </template>
              <el-table :data="list" v-loading="loading" border stripe size="small"
                        :row-class-name="rowClass">
                <el-table-column prop="code" label="编码" width="105" />
                <el-table-column prop="name" label="名称" min-width="120" />
                <el-table-column prop="spec" label="规格" width="95" show-overflow-tooltip />
                <el-table-column label="库存" width="100" align="center">
                  <template #default="{ row }">
                    <b :style="row.stock <= row.safeStock ? 'color:#f56c6c' : ''">{{ row.stock }}</b>
                    <span class="sub"> / {{ row.safeStock }} {{ row.unit }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="supplier" label="供应商" width="110" show-overflow-tooltip />
                <el-table-column label="自费" width="56" align="center">
                  <template #default="{ row }">
                    <el-tag v-if="row.selfPay" size="small" type="warning">自费</el-tag>
                    <span v-else class="sub">医保</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="150" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" text type="primary" @click="openTxn(row)">出入库</el-button>
                    <el-button size="small" text type="warning" @click="openEdit(row)">编辑</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
          <el-col :span="11">
            <el-card>
              <template #header><span>最近出入库流水</span></template>
              <el-table :data="txns" size="small" max-height="560">
                <el-table-column prop="consumableName" label="耗材" width="105" show-overflow-tooltip />
                <el-table-column label="批号" width="105">
                  <template #default="{ row }">{{ row.batchNo || '—' }}</template>
                </el-table-column>
                <el-table-column label="类型" width="72">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.qty < 0 ? 'warning' : 'success'">
                      {{ label('TxnType', row.type) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="qty" label="数量" width="56" align="right" />
                <el-table-column prop="operatorName" label="操作人" width="80" />
                <el-table-column label="时间" width="130">
                  <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column prop="note" label="备注" show-overflow-tooltip />
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-tab-pane>

      <!-- 批次台账 -->
      <el-tab-pane label="批次台账" name="batch">
        <el-card>
          <template #header>
            <div class="head">
              <span>耗材批次台账（按批号追溯，出库近效期优先 FEFO）</span>
              <div>
                <el-select v-model="batchFilter" placeholder="全部耗材" clearable size="small" style="width:180px"
                           @change="loadBatches">
                  <el-option v-for="c in list" :key="c.id" :value="c.id" :label="`${c.code} ${c.name}`" />
                </el-select>
                <el-button size="small" type="primary" @click="openBatchInbound(null)">批次入库</el-button>
              </div>
            </div>
          </template>
          <el-table :data="batches" v-loading="batchLoading" border stripe size="small">
            <el-table-column label="耗材" min-width="150">
              <template #default="{ row }">
                {{ row.consumableCode }} {{ row.consumableName }}
              </template>
            </el-table-column>
            <el-table-column prop="batchNo" label="厂家批号" width="130" />
            <el-table-column prop="supplier" label="供应商" width="120" show-overflow-tooltip />
            <el-table-column label="生产日期" width="105">
              <template #default="{ row }">{{ row.productionDate || '—' }}</template>
            </el-table-column>
            <el-table-column label="失效日期" width="105">
              <template #default="{ row }">
                <span :style="isNearExpiry(row) ? 'color:#e6a23c;font-weight:600' : ''">{{ row.expiryDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="inboundQty" label="入库量" width="70" align="right" />
            <el-table-column prop="stockQty" label="在库量" width="70" align="right">
              <template #default="{ row }"><b>{{ row.stockQty }}</b></template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType('BatchStatus', row.status)">
                  {{ label('BatchStatus', row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="viewBatchTxns(row)">追溯流水</el-button>
                <el-button v-if="row.status === 'ACTIVE'" size="small" text @click="openBatchInbound(row)">补货</el-button>
                <el-button v-if="row.status === 'ACTIVE' || row.status === 'FROZEN'" size="small"
                           text type="danger" @click="openRecall(row)">发起召回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 出入库 -->
    <el-dialog v-model="txnDialog" :title="`出入库 - ${txnTarget?.name || ''}`" width="480px">
      <el-form :model="txnForm" label-width="90px">
        <el-form-item label="类型">
          <el-radio-group v-model="txnForm.type">
            <el-radio-button value="USE">使用</el-radio-button>
            <el-radio-button value="WASTE">损耗</el-radio-button>
            <el-radio-button value="RETURN">归还</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="出库批次">
          <el-select v-model="txnForm.batchId" style="width:100%" :placeholder="txnForm.type === 'RETURN' ? '不选则总量归还' : '不选则按近效期自动挑批（FEFO）'">
            <el-option :value="null" :label="txnForm.type === 'RETURN' ? '不选批次（总量归还）' : '自动按效期挑批（跳过召回冻结批次）'" />
            <el-option v-for="b in txnBatches" :key="b.id" :value="b.id"
                       :label="`${b.batchNo}｜在库 ${b.stockQty}｜失效 ${b.expiryDate || '—'}｜${label('BatchStatus', b.status)}`"
                       :disabled="txnForm.type !== 'RETURN' && b.status !== 'ACTIVE'" />
          </el-select>
    </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="txnForm.qty" :min="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="关联患者" v-if="txnForm.type === 'USE'">
          <el-input v-model="txnForm.patientName" placeholder="选填" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="txnForm.note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="txnDialog = false">取消</el-button>
        <el-button type="primary" @click="saveTxn">确定</el-button>
      </template>
    </el-dialog>

    <!-- 批次入库 -->
    <el-dialog v-model="batchDialog" :title="inboundForm.id ? `批次补货 - ${inboundForm.consumableName}` : '批次入库'" width="500px">
      <el-form :model="inboundForm" label-width="100px">
        <el-form-item label="耗材">
          <el-select v-model="inboundForm.consumableId" style="width:100%" :disabled="!!inboundForm.id">
            <el-option v-for="c in list" :key="c.id" :value="c.id" :label="`${c.code} ${c.name}（${c.spec}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="厂家批号">
          <el-input v-model="inboundForm.batchNo" :disabled="!!inboundForm.id" placeholder="如 DLZ-260901" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="inboundForm.supplier" placeholder="默认取耗材供应商" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="生产日期">
              <el-date-picker v-model="inboundForm.productionDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="失效日期">
              <el-date-picker v-model="inboundForm.expiryDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="入库数量">
          <el-input-number v-model="inboundForm.qty" :min="1" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialog = false">取消</el-button>
        <el-button type="primary" @click="saveBatchInbound">入库</el-button>
      </template>
    </el-dialog>

    <!-- 批次追溯流水 -->
    <el-dialog v-model="batchTxnDialog" :title="`批次追溯 - ${batchTxnTarget?.batchNo || ''}`" width="720px">
      <el-descriptions :column="3" border size="small" style="margin-bottom:10px">
        <el-descriptions-item label="耗材">{{ batchTxnTarget?.consumableName }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ batchTxnTarget?.supplier || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="tagType('BatchStatus', batchTxnTarget?.status)">
            {{ label('BatchStatus', batchTxnTarget?.status) }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <el-table :data="batchTxns" size="small" max-height="380">
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
        <el-table-column prop="operatorName" label="操作人" width="90" />
        <el-table-column label="时间" width="135">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="note" label="备注" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- 编辑耗材 -->
    <el-dialog v-model="editDialog" :title="editForm.id ? '编辑耗材' : '新增耗材'" width="440px">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="编码"><el-input v-model="editForm.code" :disabled="!!editForm.id" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="editForm.spec" /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="editForm.supplier" placeholder="如 费森尤斯医疗" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="editForm.unit" /></el-form-item>
        <el-form-item label="安全库存"><el-input-number v-model="editForm.safeStock" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="自费耗材"><el-switch v-model="editForm.selfPay" /></el-form-item>
        <el-form-item label="单价(元)" v-if="editForm.selfPay">
          <el-input-number v-model="editForm.price" :min="0" :precision="2" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveConsumable">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, fmtTime } from '../utils/labels'

const router = useRouter()
const tab = ref('stock')
const list = ref([])
const txns = ref([])
const batches = ref([])
const loading = ref(false)
const batchLoading = ref(false)
const batchFilter = ref(null)
const txnDialog = ref(false)
const editDialog = ref(false)
const batchDialog = ref(false)
const batchTxnDialog = ref(false)
const txnTarget = ref(null)
const txnBatches = ref([])
const batchTxnTarget = ref(null)
const batchTxns = ref([])
const txnForm = reactive({ type: 'USE', qty: 1, batchId: null, patientName: '', note: '' })
const editForm = reactive({})
const inboundForm = reactive({})

const rowClass = ({ row }) => (row.stock <= row.safeStock ? 'warn-row' : '')
const isNearExpiry = (row) => row.expiryDate && row.status === 'ACTIVE' &&
  new Date(row.expiryDate) - new Date() < 180 * 86400000

async function load() {
  loading.value = true
  try {
    const [cs, ts] = await Promise.all([api.consumables(), api.consumableTxns()])
    list.value = cs
    txns.value = ts
  } finally {
    loading.value = false
  }
}

async function loadBatches() {
  batches.value = await api.batches(batchFilter.value || undefined)
}

async function openTxn(row) {
  txnTarget.value = row
  Object.assign(txnForm, { type: 'USE', qty: 1, batchId: null, patientName: '', note: '' })
  txnBatches.value = await api.batches(row.id)
  txnDialog.value = true
}

async function saveTxn() {
  if (txnForm.type === 'RETURN') {
    await api.consumableReturn(txnTarget.value.id, {
      qty: txnForm.qty, batchId: txnForm.batchId, patientName: txnForm.patientName, note: txnForm.note
    })
  } else {
    await api.consumableTxn(txnTarget.value.id, { ...txnForm })
  }
  ElMessage.success('已记录')
  txnDialog.value = false
  await load()
}

function openEdit(row) {
  if (row) {
    Object.assign(editForm, JSON.parse(JSON.stringify(row)))
  } else {
    Object.keys(editForm).forEach(k => delete editForm[k])
    Object.assign(editForm, { code: '', name: '', spec: '', supplier: '', unit: '支', safeStock: 10, selfPay: false, price: 0 })
  }
  editDialog.value = true
}

async function saveConsumable() {
  if (editForm.id) {
    await api.updateConsumable(editForm.id, editForm)
  } else {
    await api.createConsumable(editForm)
  }
  ElMessage.success('已保存')
  editDialog.value = false
  await load()
}

function openBatchInbound(row) {
  if (row) {
    Object.assign(inboundForm, {
      id: row.id, consumableId: row.consumableId, consumableName: row.consumableName,
      batchNo: row.batchNo, supplier: row.supplier, productionDate: row.productionDate,
      expiryDate: row.expiryDate, qty: 10
    })
  } else {
    Object.keys(inboundForm).forEach(k => delete inboundForm[k])
    Object.assign(inboundForm, { id: null, consumableId: list.value[0]?.id, batchNo: '', supplier: '', productionDate: '', expiryDate: '', qty: 10 })
  }
  batchDialog.value = true
}

async function saveBatchInbound() {
  if (!inboundForm.consumableId || !inboundForm.batchNo) {
    ElMessage.warning('请选择耗材并填写批号')
    return
  }
  await api.batchInbound(inboundForm.consumableId, { ...inboundForm })
  ElMessage.success('批次入库成功')
  batchDialog.value = false
  tab.value = 'batch'
  await Promise.all([load(), loadBatches()])
}

async function viewBatchTxns(row) {
  batchTxnTarget.value = row
  batchTxns.value = await api.batchTxns(row.id)
  batchTxnDialog.value = true
}

async function openRecall(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      `对「${row.consumableName}」批号 ${row.batchNo} 发起厂家召回。\n发起后该批次剩余在库 ${row.stockQty} 件将立即冻结停发，并自动列出受影响治疗记录。`,
      '发起厂家召回', {
        confirmButtonText: '发起召回', cancelButtonText: '取消',
        inputType: 'textarea', inputPlaceholder: '请填写厂家来函文号与召回原因（必填）'
      })
    const detail = await api.createRecall({
      consumableId: row.consumableId, batchNo: row.batchNo, reason: value,
      manufacturerDocNo: '', supplier: row.supplier
    })
    ElMessage.success('召回已发起，批次已冻结')
    router.push({ path: '/recalls', query: { recallId: detail.recall.id } })
  } catch (e) { /* 取消 */ }
}

onMounted(async () => {
  await load()
  await loadBatches()
})
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.sub { font-size: 12px; color: #909399; }
:deep(.warn-row) { background: #fef0f0; }
</style>
