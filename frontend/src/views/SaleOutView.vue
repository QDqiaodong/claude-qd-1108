<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const orders = ref([])
const wines = ref([])
const members = ref([])
const batches = ref([])
const wineMap = computed(() => Object.fromEntries(wines.value.map(w => [w.id, w])))
const memberMap = computed(() => Object.fromEntries(members.value.map(m => [m.id, m])))
const loading = ref(false)

// FEFO 可售批次（入窖日最早优先）
const sellableByWine = computed(() => {
  const m = {}
  for (const b of batches.value) {
    if (b.status === '可售' && (b.availableBottles ?? 0) > 0) (m[b.wineId] ||= []).push(b)
  }
  for (const k of Object.keys(m)) {
    m[k].sort((x, y) => (x.cellarDate < y.cellarDate ? -1 : x.cellarDate > y.cellarDate ? 1 : x.id - y.id))
  }
  return m
})
function availableOf(wineId) {
  return (sellableByWine.value[wineId] || []).reduce((s, b) => s + b.availableBottles, 0)
}

async function load() {
  loading.value = true
  try {
    const [o, w, m, b] = await Promise.all([
      api.listSaleOuts(), api.listWines(), api.listMembers(), api.listCellarBatches()
    ])
    orders.value = o; wines.value = w; members.value = m; batches.value = b
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// 同一酒款多行合计
function mergedQty() {
  const m = {}
  for (const it of items.value) {
    if (it.wineId) m[it.wineId] = (m[it.wineId] || 0) + (it.qty || 0)
  }
  return m
}

// ---------------- 出窖收银台 ----------------
const drawer = ref(false)
const memberId = ref(null)
const items = ref([])
const submitting = ref(false)

// 仅「在售且有可售批次可用量」的酒款可被选择（放行资格联动）
const sellableWines = computed(() =>
  wines.value.filter(w => w.status === '在售' && availableOf(w.id) > 0)
)

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

/** 模拟 FEFO：返回某酒款本次将依次扣减的批次 */
function planFor(wineId, qty) {
  const plan = []
  let remain = qty
  for (const b of sellableByWine.value[wineId] || []) {
    if (remain <= 0) break
    const take = Math.min(b.availableBottles, remain)
    if (take > 0) { plan.push({ batchNo: b.batchNo, qty: take }); remain -= take }
  }
  return plan
}

// 提交前的逐酒款校验：任一行库存不足整单不提交（无半成功）
const shortage = computed(() => {
  const m = mergedQty()
  for (const [wineId, qty] of Object.entries(m)) {
    if (availableOf(Number(wineId)) < qty) {
      const w = wineMap.value[wineId]
      return `酒款「${w?.name || wineId}」可售批次仅剩 ${availableOf(Number(wineId))} 瓶，不足 ${qty} 瓶`
    }
  }
  return ''
})

async function submit() {
  const payload = {
    memberId: memberId.value || null,
    items: items.value.filter(it => it.wineId).map(it => ({ wineId: it.wineId, qty: it.qty }))
  }
  if (payload.items.length === 0) { ElMessage.warning('请至少选择一款酒'); return }
  if (shortage.value) { ElMessage.error(shortage.value); return }
  if (submitting.value) return
  submitting.value = true
  try {
    await api.createSaleOut(payload)
    ElMessage.success('出窖成功，已按最早入窖批次扣减并写入出窖明细')
    drawer.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
  finally { submitting.value = false }
}

async function refund(o) {
  try {
    await ElMessageBox.confirm(
      `确认退窖订单 #${o.id}？将按出窖明细的原批次逐项回补可用瓶数并恢复批次/库存状态`
      + (o.memberId ? '，同时扣回本单累计的会员积分' : '（散客单，不涉及积分）'),
      '退窖确认', { type: 'warning' }
    )
    await api.refundSaleOut(o.id)
    ElMessage.success('已退窖，批次与库存已按原批次回补')
    await load(); emit('changed')
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <span class="hint">仅已放行（可售）批次的酒款可选；同一酒款多批次按入窖日最早优先扣减，批次来源逐行留痕</span>
      <el-button type="primary" @click="openCreate">新建出窖</el-button>
    </div>

    <el-table :data="orders" border stripe class="orders" row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div class="detail-title">出窖明细（含实际扣减批次，退窖按此逐项回补）</div>
            <el-table :data="row.items" border size="small">
              <el-table-column label="酒款" min-width="180">
                <template #default="{ row: it }">
                  {{ it.wineCode }} {{ it.wineName || ('酒款#' + it.wineId) }}
                </template>
              </el-table-column>
              <el-table-column label="扣减批次（FEFO）" min-width="160">
                <template #default="{ row: it }">
                  <el-tag size="small" :type="row.status === '已退窖' ? 'info' : 'warning'">
                    {{ it.batchNo || ('批次#' + it.batchId) }}
                  </el-tag>
                  <span v-if="row.status === '已退窖'" class="restored">已回补</span>
                </template>
              </el-table-column>
              <el-table-column prop="qty" label="数量" width="90" />
              <el-table-column label="单价" width="100">
                <template #default="{ row: it }">¥{{ it.price ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="小计" width="110">
                <template #default="{ row: it }">¥{{ it.amount ?? '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="单号" width="80" />
      <el-table-column label="会员" min-width="120">
        <template #default="{ row }">
          <span v-if="row.memberId">{{ row.memberName || memberMap[row.memberId]?.name || '会员' }}</span>
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

    <el-drawer v-model="drawer" title="出窖收银台" size="460px">
      <el-form label-width="70px">
        <el-form-item label="关联会员">
          <el-select v-model="memberId" clearable placeholder="散客（不选）" style="width:100%">
            <el-option v-for="m in members" :key="m.id" :label="`${m.name}（${m.level}）`" :value="m.id" />
          </el-select>
        </el-form-item>

        <el-divider>出窖明细</el-divider>
        <div v-for="(it, i) in items" :key="i" class="item-block">
          <div class="item-row">
            <el-select v-model="it.wineId" filterable placeholder="仅可售酒款" style="flex:1">
              <el-option
                v-for="w in sellableWines" :key="w.id"
                :label="`${w.code} ${w.name}（可售 ${availableOf(w.id)} 瓶 / ¥${w.price}）`"
                :value="w.id"
              />
            </el-select>
            <el-input-number v-model="it.qty" :min="1" :max="999" />
            <el-button v-if="items.length > 1" text type="danger" @click="removeItem(i)">删</el-button>
          </div>
          <div v-if="it.wineId" class="batch-plan">
            <template v-if="availableOf(it.wineId) >= (it.qty || 0)">
              <span class="plan-label">将按 FEFO 扣减：</span>
              <el-tag v-for="(p, pi) in planFor(it.wineId, it.qty || 0)" :key="pi" size="small" class="plan-tag">
                {{ p.batchNo }} × {{ p.qty }}
              </el-tag>
            </template>
            <span v-else class="plan-bad">可售批次仅剩 {{ availableOf(it.wineId) }} 瓶，不足 {{ it.qty || 0 }} 瓶</span>
          </div>
        </div>
        <el-button link type="primary" @click="addItem">+ 增加一行</el-button>

        <el-alert
          v-if="shortage" class="plan-alert" type="error" :closable="false" show-icon
          :title="`存在明细库存不足，整单不可提交：${shortage}`"
        />

        <div class="total-line">合计：<strong>¥{{ total }}</strong></div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button
          type="primary" :loading="submitting"
          :disabled="!!shortage || items.every(it => !it.wineId)"
          @click="submit"
        >确认出窖</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; gap: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.orders { border-radius: 10px; }
.total { color: var(--primary); font-weight: 700; }
.muted { color: var(--muted); font-size: 12px; }
.detail { padding: 8px 16px 14px; background: var(--primary-wash, #faf2f5); }
.detail-title { font-size: 13px; color: var(--muted); margin-bottom: 8px; }
.restored { margin-left: 8px; font-size: 12px; color: var(--muted); }
.item-block { margin-bottom: 12px; }
.item-row { display: flex; gap: 8px; align-items: center; }
.batch-plan { margin-top: 6px; padding: 6px 8px; background: #faf6f8; border-radius: 6px; font-size: 12px; }
.plan-label { color: var(--muted); }
.plan-tag { margin: 2px 4px 2px 0; }
.plan-bad { color: #b42318; font-weight: 700; }
.plan-alert { margin-top: 10px; }
.total-line { margin-top: 14px; font-size: 15px; text-align: right; color: var(--ink); }
.total-line strong { color: var(--primary); }
</style>
