<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const REGIONS = ['国产', '法国', '智利', '澳洲', '其他']
const TYPES = ['红', '白', '起泡', '甜']
const STATUSES = ['在售', '停售']
const STATUS_TAG = { '在售': 'success', '停售': 'info' }
const BATCH_TAG = { '窖藏中': 'warning', '可售': 'success', '已售罄': 'info' }

const all = ref([])
const batches = ref([])
const activeNames = ref([...REGIONS])

const batchesByWine = computed(() => {
  const m = {}
  for (const b of batches.value) (m[b.wineId] ||= []).push(b)
  for (const k of Object.keys(m)) {
    m[k].sort((x, y) => (x.cellarDate < y.cellarDate ? -1 : x.cellarDate > y.cellarDate ? 1 : x.id - y.id))
  }
  return m
})

function batchSum(wineId) {
  return (batchesByWine.value[wineId] || [])
    .filter(b => b.status === '可售')
    .reduce((s, b) => s + (b.availableBottles ?? 0), 0)
}
function isInconsistent(w) { return w.stock !== batchSum(w.id) }

const grouped = computed(() =>
  REGIONS.map(r => ({
    region: r,
    wines: all.value.filter(w => w.region === r)
  })).filter(g => g.wines.length > 0)
)

const loading = ref(false)
async function load() {
  loading.value = true
  try {
    const [w, b] = await Promise.all([api.listWines(), api.listCellarBatches()])
    all.value = w; batches.value = b
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 新增 / 编辑（库存联动只读，不可绕过批次直改） ----------------
const dialog = ref(false)
const editing = ref(null)
const form = reactive({ code: '', name: '', region: '国产', type: '红', price: 0, status: '在售' })

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', region: '国产', type: '红', price: 0, status: '在售' })
  dialog.value = true
}
function openEdit(w) {
  editing.value = w.id
  Object.assign(form, { code: w.code, name: w.name, region: w.region, type: w.type, price: w.price, status: w.status })
  dialog.value = true
}
async function submit() {
  try {
    if (editing.value) {
      await api.updateWine(editing.value, { ...form })
      ElMessage.success('已更新酒款')
    } else {
      await api.createWine({ ...form, stock: 0 })
      ElMessage.success('已新增酒款，初始可售库存为 0，请前往窖藏批次放行')
    }
    dialog.value = false
    await load()
    emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <span class="hint">按产区分组折叠查看酒款 · 可售库存由放行批次联动</span>
      <el-button type="primary" @click="openCreate">新增酒款</el-button>
    </div>

    <el-collapse v-model="activeNames" class="groups">
      <el-collapse-item v-for="g in grouped" :key="g.region" :name="g.region" :title="`${g.region}（${g.wines.length}）`">
        <ul class="wine-list">
          <li v-for="w in g.wines" :key="w.id" class="wine-item">
            <div class="wine-main">
              <div class="wine-name">
                <span class="code">{{ w.code }}</span>
                <strong>{{ w.name }}</strong>
              </div>
              <div class="wine-meta">
                <el-tag size="small" effect="plain">{{ w.type }}</el-tag>
                <span class="price">¥{{ w.price }}</span>
                <el-popover placement="bottom" :width="280" trigger="hover">
                  <template #reference>
                    <span class="stock-link" :class="{ bad: isInconsistent(w) }">
                      可售库存 {{ w.stock }}
                      <span class="qmark">?</span>
                    </span>
                  </template>
                  <div class="pop-title">{{ w.name }} · 批次库存构成</div>
                  <div v-if="(batchesByWine[w.id] || []).length === 0" class="pop-empty">暂无窖藏批次</div>
                  <div v-for="b in batchesByWine[w.id]" :key="b.id" class="pop-row">
                    <span>{{ b.batchNo }}（{{ b.cellarDate }} 入窖）</span>
                    <span>
                      <el-tag size="small" :type="BATCH_TAG[b.status]">{{ b.status }}</el-tag>
                      <b>{{ b.status === '窖藏中' ? '未放行' : `${b.availableBottles ?? 0} 瓶` }}</b>
                    </span>
                  </div>
                  <div class="pop-sum">
                    批次可用合计 {{ batchSum(w.id) }} / 台账 {{ w.stock }}
                    <span v-if="isInconsistent(w)" class="pop-bad">⚠ 不一致，请核对批次</span>
                  </div>
                </el-popover>
                <el-tag size="small" :type="STATUS_TAG[w.status]">{{ w.status }}</el-tag>
              </div>
              <div v-if="isInconsistent(w)" class="inconsistent">
                ⚠ 台账库存({{ w.stock }}) 与各可售批次可用瓶数合计({{ batchSum(w.id) }}) 不一致，该酒款的放行/出窖/退窖将被暂停
              </div>
            </div>
            <el-button size="small" @click="openEdit(w)">编辑</el-button>
          </li>
        </ul>
      </el-collapse-item>
    </el-collapse>

    <el-empty v-if="!loading && grouped.length === 0" description="暂无酒款" />

    <el-dialog v-model="dialog" :title="editing ? '编辑酒款' : '新增酒款'" width="460px">
      <el-form label-width="80px">
        <el-form-item label="酒款编号"><el-input v-model="form.code" placeholder="如 W-1001" /></el-form-item>
        <el-form-item label="酒名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="产区">
          <el-select v-model="form.region"><el-option v-for="r in REGIONS" :key="r" :label="r" :value="r" /></el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type"><el-option v-for="t in TYPES" :key="t" :label="t" :value="t" /></el-select>
        </el-form-item>
        <el-form-item label="可售库存">
          <el-input :model-value="editing ? '由窖藏批次联动，不可直改' : '新酒款固定 0，放行批次后产生'" disabled />
        </el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.price" :min="0" :precision="2" :step="10" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.groups { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 0 8px; }
.wine-list { list-style: none; margin: 0; padding: 0; }
.wine-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 8px; border-bottom: 1px dashed var(--line);
}
.wine-item:last-child { border-bottom: none; }
.wine-name { display: flex; align-items: center; gap: 10px; }
.code { color: var(--muted); font-size: 12px; font-family: monospace; }
.wine-meta { margin-top: 4px; display: flex; align-items: center; gap: 14px; color: var(--muted); font-size: 12px; }
.price { color: var(--primary); font-weight: 700; }
.stock-link {
  cursor: pointer; border-bottom: 1px dashed var(--muted);
  display: inline-flex; align-items: center; gap: 3px;
}
.stock-link.bad { color: #b42318; border-color: #b42318; font-weight: 700; }
.dot { font-size: 12px; }
.qmark { display: inline-block; width: 13px; height: 13px; line-height: 13px; text-align: center; border: 1px solid currentColor; border-radius: 50%; font-size: 10px; }
.inconsistent {
  margin-top: 6px; font-size: 12px; color: #b42318;
  background: #fef3f2; border: 1px solid #fecdca; border-radius: 6px; padding: 4px 8px;
}
.pop-title { font-weight: 700; margin-bottom: 6px; color: var(--primary-deep); }
.pop-row { display: flex; justify-content: space-between; align-items: center; font-size: 12px; padding: 3px 0; }
.pop-row b { margin-left: 6px; color: var(--primary); }
.pop-empty { color: var(--muted); font-size: 12px; padding: 4px 0; }
.pop-sum { margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line); font-size: 12px; }
.pop-bad { color: #b42318; margin-left: 6px; font-weight: 700; }
</style>
