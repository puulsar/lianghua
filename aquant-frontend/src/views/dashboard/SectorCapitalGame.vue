<template>
  <div class="sector-capital-page">
    <div class="sc-header">
      <div class="sc-title-box">
        <span class="sc-title">板块资金博弈</span>
        <span class="sc-sub-text">(今日净流入)</span>
        <span class="sc-meta" v-if="updatedAt">更新于 {{ updatedAt }}</span>
      </div>
      <div class="sc-actions">
        <span class="sc-stat">
          板块 <strong>{{ nodeCount }}</strong> 个 · 关联 <strong>{{ linkCount }}</strong> 条
        </span>
        <a-switch
          v-model:checked="autoRefresh"
          checked-children="自动"
          un-checked-children="手动"
          size="small"
        />
        <a-button size="small" :loading="loading" @click="loadData(true)">
          <template #icon><reload-outlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <div class="sc-body">
      <FundFlowGraph :data="graphData" :loading="loading" min-height="640px" />
    </div>

    <div class="sc-tip">
      提示：滚轮缩放 / 按住左键拖拽平移 / 右下角按钮可放大、缩小、重置视角。拖拽后如需回到初始布局，点「重置视角」。
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ReloadOutlined } from '@ant-design/icons-vue';
import { getFundFlowGraph, type FundFlowGraphData } from '@/api/fundFlow';
import FundFlowGraph from './components/FundFlowGraph.vue';

const AUTO_REFRESH_MS = 60 * 1000;

const loading = ref(false);
const graphData = ref<FundFlowGraphData | null>(null);
const updatedAt = ref('');
const autoRefresh = ref(true);
let timer: number | null = null;

const nodeCount = computed(() => graphData.value?.nodes?.length ?? 0);
const linkCount = computed(() => graphData.value?.links?.length ?? 0);

const loadData = (manual = false) => {
  if (loading.value && !manual) return;
  loading.value = true;
  getFundFlowGraph()
    .then(res => {
      if (res.data?.data) {
        graphData.value = res.data.data;
        const now = new Date();
        updatedAt.value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
      }
    })
    .catch(error => {
      console.error('加载资金博弈关系图失败:', error);
    })
    .finally(() => {
      loading.value = false;
    });
};

const syncTimer = () => {
  if (timer !== null) {
    window.clearInterval(timer);
    timer = null;
  }
  if (autoRefresh.value) {
    timer = window.setInterval(() => loadData(), AUTO_REFRESH_MS);
  }
};

watch(autoRefresh, syncTimer);

onMounted(() => {
  loadData(true);
  syncTimer();
});

onUnmounted(() => {
  if (timer !== null) {
    window.clearInterval(timer);
    timer = null;
  }
});
</script>

<style scoped>
.sector-capital-page {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  /* 撑满可视区域：减去顶部导航(64) + 内容区上下 padding(64) + 面包屑(约 46) */
  min-height: calc(100vh - 174px);
}

.sc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 14px 18px;
  background: #ffffff;
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}

.sc-title-box {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.sc-title {
  font-size: 18px;
  font-weight: 600;
  color: #0f172a;
}

.sc-sub-text {
  font-size: 13px;
  color: #94a3b8;
}

.sc-meta {
  font-size: 12px;
  color: #94a3b8;
  margin-left: 8px;
}

.sc-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sc-stat {
  font-size: 13px;
  color: #64748b;
}

.sc-stat strong {
  color: #0f172a;
}

.sc-body {
  width: 100%;
  flex: 1 1 auto;
  min-height: 640px;
  padding: 8px 12px 12px;
  background: #ffffff;
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  display: flex;
  flex-direction: column;
}

.sc-tip {
  font-size: 12px;
  color: #94a3b8;
  padding: 0 4px 0;
}
</style>
