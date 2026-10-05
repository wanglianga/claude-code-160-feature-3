<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="13">
        <el-card>
          <template #header>
            <div class="head">
              <span>耗材库存</span>
              <el-button size="small" :icon="Plus" @click="openEdit(null)">新增耗材</el-button>
            </div>
          </template>
          <el-table :data="list" v-loading="loading" border stripe size="small"
                    :row-class-name="rowClass">
            <el-table-column prop="code" label="编码" width="110" />
            <el-table-column prop="name" label="名称" min-width="130" />
            <el-table-column prop="spec" label="规格" width="110" />
            <el-table-column label="库存" width="130" align="center">
              <template #default="{ row }">
                <b :style="row.stock <= row.safeStock ? 'color:#f56c6c' : ''">{{ row.stock }}</b>
                <span class="sub"> / {{ row.safeStock }} {{ row.unit }}</span>
                <div v-if="row.frozenQty > 0" class="frozen">冻结 {{ row.frozenQty }}</div>
              </template>
            </el-table-column>
            <el-table-column label="自费" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.selfPay" size="small" type="warning">自费</el-tag>
                <span v-else class="sub">医保</span>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="80">
              <template #default="{ row }">{{ row.selfPay ? '¥' + row.price : '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openTxn(row)">出入库</el-button>
                <el-button size="small" text type="success" @click="openBatches(row)">批次</el-button>
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
            <el-table-column prop="consumableName" label="耗材" width="110" show-overflow-tooltip />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.qty < 0 ? 'warning' : 'success'">
                  {{ label('TxnType', row.type) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" width="60" align="right" />
            <el-table-column label="批号" width="105">
              <template #default="{ row }">
                <span v-if="row.batchNo">{{ row.batchNo }}</span>
                <span v-else class="sub">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="patientName" label="患者" width="75">
              <template #default="{ row }">{{ row.patientName || '-' }}</template>
            </el-table-column>
            <el-table-column prop="operatorName" label="操作人" width="75" />
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="note" label="备注" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 出入库 -->
    <el-dialog v-model="txnDialog" :title="`出入库 - ${txnTarget?.name || ''}`" width="480px">
      <el-form :model="txnForm" label-width="90px">
        <el-form-item label="类型">
          <el-radio-group v-model="txnForm.type" @change="onTxnTypeChange">
            <el-radio-button value="USE">使用</el-radio-button>
            <el-radio-button value="WASTE">损耗</el-radio-button>
            <el-radio-button value="RETURN">归还</el-radio-button>
            <el-radio-button value="RESTOCK">入库</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="txnForm.qty" :min="1" style="width:100%" />
        </el-form-item>
        <!-- 出库：批次选择（默认 FEFO 自动分配正常批次；冻结/召回批次不可选） -->
        <el-form-item v-if="isOutbound" label="出库批次">
          <el-select v-model="txnForm.batchId" style="width:100%" clearable placeholder="自动分配（最早失效期先出）">
            <el-option v-for="b in outboundBatches" :key="b.id" :value="b.id"
                       :label="`${b.batchNo}（剩余 ${b.remainingQty}，效期 ${b.expiryDate || '-'}）`" />
          </el-select>
          <div v-if="frozenBatches.length" class="frozen-tip">
            冻结/召回批次不可发放：{{ frozenBatches.map(b => `${b.batchNo}(剩${b.remainingQty})`).join('、') }}
          </div>
        </el-form-item>
        <!-- 入库：批号+供应商+效期，自动查找或创建批次 -->
        <template v-if="txnForm.type === 'RESTOCK'">
          <el-form-item label="批号">
            <el-input v-model="txnForm.batchNo" placeholder="厂家批号，留空则不关联批次" />
          </el-form-item>
          <el-form-item label="供应商">
            <el-input v-model="txnForm.supplier" placeholder="新批次必填" />
          </el-form-item>
          <el-form-item label="失效期">
            <el-date-picker v-model="txnForm.expiryDate" type="date" value-format="YYYY-MM-DD"
                            style="width:100%" />
          </el-form-item>
        </template>
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

    <!-- 批次列表 -->
    <el-drawer v-model="batchDrawer" :title="`批次管理 - ${batchTarget?.name || ''}`" size="46%">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="出库默认按最早失效期（FEFO）分配正常批次；召回后批次冻结，处理期间禁止发放" />
      <el-table :data="batches" size="small" border>
        <el-table-column prop="batchNo" label="批号" width="120" />
        <el-table-column prop="supplier" label="供应商" width="100" />
        <el-table-column label="失效期" width="100">
          <template #default="{ row }">{{ row.expiryDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="receivedQty" label="累计入库" width="80" align="center" />
        <el-table-column label="剩余" width="70" align="center">
          <template #default="{ row }">
            <b :style="row.status !== 'NORMAL' && row.remainingQty > 0 ? 'color:#e6a23c' : ''">
              {{ row.remainingQty }}
            </b>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('BatchStatus', row.status)">
              {{ label('BatchStatus', row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'NORMAL'" size="small" text type="danger"
                       @click="goRecall(row)">发起召回</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!batches.length" description="暂无批次，入库时填写批号自动创建" :image-size="60" />
    </el-drawer>

    <!-- 编辑耗材 -->
    <el-dialog v-model="editDialog" :title="editForm.id ? '编辑耗材' : '新增耗材'" width="440px">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="编码"><el-input v-model="editForm.code" :disabled="!!editForm.id" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="editForm.spec" /></el-form-item>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, fmtTime } from '../utils/labels'

const router = useRouter()
const list = ref([])
const txns = ref([])
const loading = ref(false)
const txnDialog = ref(false)
const editDialog = ref(false)
const txnTarget = ref(null)
const txnForm = reactive({ type: 'USE', qty: 1, patientName: '', note: '', batchId: null, batchNo: '', supplier: '', expiryDate: null })
const editForm = reactive({})
const batchDrawer = ref(false)
const batchTarget = ref(null)
const batches = ref([])

const rowClass = ({ row }) => (row.stock <= row.safeStock ? 'warn-row' : '')
const isOutbound = computed(() => ['USE', 'WASTE'].includes(txnForm.type))
const outboundBatches = computed(() => batches.value.filter(b => b.status === 'NORMAL' && b.remainingQty > 0))
const frozenBatches = computed(() => batches.value.filter(b => b.status !== 'NORMAL' && b.remainingQty > 0))

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

async function openTxn(row) {
  txnTarget.value = row
  Object.assign(txnForm, { type: 'USE', qty: 1, patientName: '', note: '', batchId: null, batchNo: '', supplier: '', expiryDate: null })
  batches.value = await api.consumableBatches(row.id)
  txnDialog.value = true
}

function onTxnTypeChange() {
  txnForm.batchId = null
}

async function saveTxn() {
  const payload = { type: txnForm.type, qty: txnForm.qty, patientName: txnForm.patientName, note: txnForm.note }
  if (isOutbound.value && txnForm.batchId) payload.batchId = txnForm.batchId
  if (txnForm.type === 'RESTOCK') {
    Object.assign(payload, { batchNo: txnForm.batchNo, supplier: txnForm.supplier, expiryDate: txnForm.expiryDate })
  }
  await api.consumableTxn(txnTarget.value.id, payload)
  ElMessage.success('已记录')
  txnDialog.value = false
  await load()
}

async function openBatches(row) {
  batchTarget.value = row
  batches.value = await api.consumableBatches(row.id)
  batchDrawer.value = true
}

function goRecall() {
  batchDrawer.value = false
  router.push('/recalls')
}

function openEdit(row) {
  if (row) {
    Object.assign(editForm, JSON.parse(JSON.stringify(row)))
  } else {
    Object.keys(editForm).forEach(k => delete editForm[k])
    Object.assign(editForm, { code: '', name: '', spec: '', unit: '支', safeStock: 10, selfPay: false, price: 0 })
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

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.sub { font-size: 12px; color: #909399; }
.frozen { font-size: 12px; color: #e6a23c; font-weight: 600; }
.frozen-tip { font-size: 12px; color: #e6a23c; margin-top: 4px; }
:deep(.warn-row) { background: #fef0f0; }
</style>
