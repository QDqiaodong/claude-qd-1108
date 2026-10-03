<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const STATUSES = ['窖藏中', '可售', '已售罄']
const STATUS_TAG = { '窖藏中': 'warning', '可售': 'success', '已售罄': 'info' }
const FILTERS = ['全部', ...STATUSES]

const batches = ref([])
const wines = ref([])
const wineMap = computed(() => Object.fromEntries(wines.value.map(w => [w.id, w])))
const loading = ref(false)
const filter = ref('全部')

const shown = computed(() =>
  filter.value === '全部' ? batches.value : batches.value.filter(b => b.status === filter.value)
)

async function load() {
  loading.value = true
  try {
    const [b, w] = await Promise.all([api.listCellarBatches(), api.listWines()])
    batches.value = b; wines.value = w
  } catch (e) { ElMessage.error(e.message) }
  finally { loading.value = false }
}

// ---------------- 新增 ----------------
const dialog = ref(false)
const form = reactive({ wineId: null, batchNo: '', bottleCount: 1, cellarDate: '', status: '窖藏中' })

function openCreate() {
  Object.assign(form, { wineId: null, batchNo: '', bottleCount: 1, cellarDate: '', status: '窖藏中' })
  dialog.value = true
}
async function submit() {
  try {
    await api.createCellarBatch({ ...form })
    ElMessage.success('已新建窖藏批次')
    dialog.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function release(b) {
  try {
    await api.releaseCellarBatch(b.id)
    ElMessage.success(`批次 ${b.batchNo} 已放行可售`)
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

const emit = defineEmits(['changed'])
onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <el-radio-group v-model="filter">
        <el-radio-button v-for="f in FILTERS" :key="f" :value="f">{{ f }}</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openCreate">新增批次</el-button>
    </div>

    <div class="grid">
      <el-card v-for="b in shown" :key="b.id" shadow="hover" class="card">
        <div class="card-head">
          <span class="batch-no">{{ b.batchNo }}</span>
          <el-tag size="small" :type="STATUS_TAG[b.status]">{{ b.status }}</el-tag>
        </div>
        <div class="card-body">
          <div class="row"><span class="k">酒款</span><span class="v">{{ wineMap[b.wineId]?.name || '—' }}</span></div>
          <div class="row"><span class="k">瓶数</span><span class="v">{{ b.bottleCount }} 瓶</span></div>
          <div class="row"><span class="k">入窖日</span><span class="v">{{ b.cellarDate }}</span></div>
        </div>
        <div class="card-foot">
          <el-button
            v-if="b.status === '窖藏中'"
            type="success" size="small"
            @click="release(b)"
          >放行可售</el-button>
          <span v-else class="muted">已流转</span>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && shown.length === 0" description="暂无批次" />

    <el-dialog v-model="dialog" title="新增窖藏批次" width="440px">
      <el-form label-width="80px">
        <el-form-item label="关联酒款">
          <el-select v-model="form.wineId" filterable placeholder="选择酒款">
            <el-option v-for="w in wines" :key="w.id" :label="`${w.code} ${w.name}`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次号"><el-input v-model="form.batchNo" placeholder="如 B-2025-05" /></el-form-item>
        <el-form-item label="瓶数"><el-input-number v-model="form.bottleCount" :min="1" /></el-form-item>
        <el-form-item label="入窖日"><el-date-picker v-model="form.cellarDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
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
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.card { border-radius: 12px; }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.batch-no { font-weight: 700; color: var(--primary-deep); }
.card-body { font-size: 13px; }
.row { display: flex; justify-content: space-between; padding: 3px 0; }
.k { color: var(--muted); }
.v { color: var(--ink); }
.card-foot { margin-top: 12px; }
.muted { color: var(--muted); font-size: 12px; }
</style>
