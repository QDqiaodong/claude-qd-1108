<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const orders = ref([])
const wines = ref([])
const members = ref([])
const wineMap = computed(() => Object.fromEntries(wines.value.map(w => [w.id, w])))
const memberMap = computed(() => Object.fromEntries(members.value.map(m => [m.id, m])))
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [o, w, m] = await Promise.all([api.listSaleOuts(), api.listWines(), api.listMembers()])
    orders.value = o; wines.value = w; members.value = m
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 出窖收银台 ----------------
const drawer = ref(false)
const memberId = ref(null)
const items = ref([])

function openCreate() {
  memberId.value = null
  items.value = [{ wineId: null, qty: 1 }]
  drawer.value = true
}
function addItem() { items.value.push({ wineId: null, qty: 1 }) }
function removeItem(i) { items.value.splice(i, 1) }

const total = computed(() =>
  items.value.reduce((sum, it) => {
    const w = wineMap.value[it.wineId]
    return sum + (w ? Number(w.price) * (it.qty || 0) : 0)
  }, 0)
)

async function submit() {
  const payload = {
    memberId: memberId.value || null,
    items: items.value.filter(it => it.wineId).map(it => ({ wineId: it.wineId, qty: it.qty }))
  }
  if (payload.items.length === 0) { ElMessage.warning('请至少选择一款酒'); return }
  try {
    await api.createSaleOut(payload)
    ElMessage.success('出窖成功，库存已扣减')
    drawer.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function refund(o) {
  try {
    await ElMessageBox.confirm(`确认退窖订单 #${o.id}？将回滚库存`, '退窖确认', { type: 'warning' })
    await api.refundSaleOut(o.id)
    ElMessage.success('已退窖，库存已回滚')
    await load(); emit('changed')
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <span class="hint">新建出窖单即扣减库存，散客可不关联会员</span>
      <el-button type="primary" @click="openCreate">新建出窖</el-button>
    </div>

    <el-table :data="orders" border stripe class="orders">
      <el-table-column prop="id" label="单号" width="80" />
      <el-table-column label="会员" min-width="120">
        <template #default="{ row }">
          <span v-if="row.memberId">{{ memberMap[row.memberId]?.name || '会员' }}</span>
          <el-tag v-else size="small" type="info">散客</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="总额" width="120">
        <template #default="{ row }"><span class="total">¥{{ row.total }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === '已出窖' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.status === '已出窖'" size="small" type="danger" @click="refund(row)">退窖</el-button>
          <span v-else class="muted">已退窖</span>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" title="出窖收银台" size="420px">
      <el-form label-width="70px">
        <el-form-item label="关联会员">
          <el-select v-model="memberId" clearable placeholder="散客（不选）" style="width:100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.level}）`" :value="m.id" />
          </el-select>
        </el-form-item>

        <el-divider>出窖明细</el-divider>
        <div v-for="(it, i) in items" :key="i" class="item-row">
          <el-select v-model="it.wineId" filterable placeholder="酒款" style="flex:1">
            <el-option v-for="w in wines" :key="w.id" :label="`${w.code} ${w.name}（¥${w.price}/库存${w.stock}）`" :value="w.id" />
          </el-select>
          <el-input-number v-model="it.qty" :min="1" :max="999" />
          <el-button v-if="items.length > 1" text type="danger" @click="removeItem(i)">删</el-button>
        </div>
        <el-button link type="primary" @click="addItem">+ 增加一行</el-button>

        <div class="total-line">合计：<strong>¥{{ total }}</strong></div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" @click="submit">确认出窖</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.hint { color: var(--muted); font-size: 13px; }
.orders { border-radius: 10px; }
.total { color: var(--primary); font-weight: 700; }
.muted { color: var(--muted); font-size: 12px; }
.item-row { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.total-line { margin-top: 14px; font-size: 15px; text-align: right; color: var(--ink); }
.total-line strong { color: var(--primary); }
</style>
