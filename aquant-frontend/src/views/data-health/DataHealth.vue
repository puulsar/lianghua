<template>
  <div class="data-health-page">
    <!-- 顶部：总体结论 -->
    <div class="health-summary-card" :class="`health-summary-card--${statusClass(latest?.status)}`">
      <div class="health-summary-left">
        <div class="health-status-badge" :class="`health-status-badge--${statusClass(latest?.status)}`">
          <component :is="statusIcon(latest?.status)" />
          <span>{{ statusText(latest?.status) }}</span>
        </div>
        <div class="health-summary-text">
          <div class="health-summary-title">
            {{ latest ? `${latest.tradeDate} 当日数据体检` : '暂无体检记录' }}
          </div>
          <div class="health-summary-desc">
            {{ latest ? latest.summary : '收盘作业（每交易日 15:40）跑完后会自动体检，也可以点右上角手动体检' }}
          </div>
        </div>
      </div>
      <div class="health-summary-right">
        <div class="health-meta">
          <span class="health-meta-label">体检时间</span>
          <span class="health-meta-value">{{ formatTime(latest?.checkTime) }}</span>
        </div>
        <div class="health-meta">
          <span class="health-meta-label">耗时</span>
          <span class="health-meta-value">{{ formatDuration(latest?.durationMillis) }}</span>
        </div>
        <div class="health-meta">
          <span class="health-meta-label">触发方式</span>
          <span class="health-meta-value">{{ triggerText(latest?.triggerType) }}</span>
        </div>
        <a-tooltip title="重新统计当日全部数据（收盘快照、日K、板块历史、指标、策略快照），完成后自动体检，需几分钟到十几分钟" placement="bottom">
          <a-button
            type="primary"
            class="health-run-btn"
            :loading="closing"
            @click="handleDailyClose"
          >
            <template #icon><play-circle-outlined /></template>
            手动触发数据统计
          </a-button>
        </a-tooltip>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="health-count-grid">
      <div class="health-count-card">
        <div class="health-count-value health-count-value--pass">{{ latest?.passCount ?? 0 }}</div>
        <div class="health-count-label">通过项</div>
      </div>
      <div class="health-count-card">
        <div class="health-count-value health-count-value--warn">{{ latest?.warnCount ?? 0 }}</div>
        <div class="health-count-label">告警项</div>
      </div>
      <div class="health-count-card">
        <div class="health-count-value health-count-value--fail">{{ latest?.failCount ?? 0 }}</div>
        <div class="health-count-label">异常项</div>
      </div>
    </div>

    <!-- 明细 -->
    <div class="health-section">
      <div class="health-section-title">检查明细</div>
      <a-table
        :columns="columns"
        :data-source="items"
        :pagination="false"
        :loading="loading"
        row-key="itemKey"
        size="middle"
        class="health-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="tagColor(record.status)">{{ statusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'itemName'">
            <span class="health-item-name">{{ record.itemName }}</span>
            <span class="health-item-key">{{ record.itemKey }}</span>
          </template>
          <template v-else-if="column.key === 'message'">
            <span class="health-item-message">{{ record.message }}</span>
          </template>
        </template>
        <template #emptyText>
          <a-empty description="暂无体检明细" />
        </template>
      </a-table>
    </div>

    <!-- 历史 -->
    <div class="health-section">
      <div class="health-section-title">历史体检记录</div>
      <div class="health-history-list">
        <div v-for="item in history" :key="item.id" class="health-history-row">
          <span class="health-history-date">{{ item.tradeDate }}</span>
          <a-tag :color="tagColor(item.status)">{{ statusText(item.status) }}</a-tag>
          <span class="health-history-summary">{{ item.summary }}</span>
          <span class="health-history-time">{{ formatTime(item.checkTime) }}</span>
        </div>
        <a-empty v-if="!history.length" description="暂无历史记录" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import {
  CheckCircleFilled,
  ExclamationCircleFilled,
  CloseCircleFilled,
  QuestionCircleFilled,
  PlayCircleOutlined
} from '@ant-design/icons-vue';
import {
  getLatestHealthCheck,
  getRecentHealthChecks,
  triggerDailyCloseJob,
  type DataHealthCheckItemVO,
  type DataHealthCheckVO,
  type HealthStatus
} from '@/api/dataHealth';
import { registerRefreshHandler } from '@/utils/refreshBus';

const loading = ref(false);
const latest = ref<DataHealthCheckVO | null>(null);
const history = ref<DataHealthCheckVO[]>([]);
const closing = ref(false);

/** 手动触发收盘数据统计（异步作业，完成后会自动写入新的体检记录） */
const handleDailyClose = async () => {
  closing.value = true;
  try {
    const res = await triggerDailyCloseJob();
    if (res.data.success) {
      message.success('数据统计已开始，需几分钟到十几分钟，完成后体检结果会自动更新');
    } else {
      message.error(res.data.message || '触发失败');
    }
  } catch (error) {
    console.error('Failed to trigger daily close job:', error);
  } finally {
    closing.value = false;
  }
};

const items = computed<DataHealthCheckItemVO[]>(() => latest.value?.items ?? []);

const columns = [
  { title: '检查项', key: 'itemName', dataIndex: 'itemName', width: 220 },
  { title: '结论', key: 'status', dataIndex: 'status', width: 100 },
  { title: '期望', key: 'expected', dataIndex: 'expected', width: 200 },
  { title: '实际', key: 'actual', dataIndex: 'actual', width: 240 },
  { title: '说明', key: 'message', dataIndex: 'message' }
];

const statusClass = (status?: HealthStatus) => {
  if (status === 'PASS') return 'pass';
  if (status === 'WARN') return 'warn';
  if (status === 'FAIL') return 'fail';
  return 'unknown';
};

const statusText = (status?: HealthStatus) => {
  if (status === 'PASS') return '正常';
  if (status === 'WARN') return '告警';
  if (status === 'FAIL') return '异常';
  return '未知';
};

const statusIcon = (status?: HealthStatus) => {
  if (status === 'PASS') return CheckCircleFilled;
  if (status === 'WARN') return ExclamationCircleFilled;
  if (status === 'FAIL') return CloseCircleFilled;
  return QuestionCircleFilled;
};

const tagColor = (status: HealthStatus) => {
  if (status === 'PASS') return 'green';
  if (status === 'WARN') return 'orange';
  if (status === 'FAIL') return 'red';
  return 'default';
};

const triggerText = (type?: string) => {
  if (type === 'SCHEDULED') return '收盘作业自动';
  if (type === 'MANUAL') return '手动触发';
  return '-';
};

const formatTime = (value?: string) => {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 19);
};

const formatDuration = (millis?: number) => {
  if (millis == null) return '-';
  if (millis < 1000) return `${millis} ms`;
  return `${(millis / 1000).toFixed(1)} s`;
};

const loadData = async () => {
  loading.value = true;
  try {
    const [latestRes, historyRes] = await Promise.all([
      getLatestHealthCheck(),
      getRecentHealthChecks(10)
    ]);
    latest.value = latestRes.data.data ?? null;
    history.value = historyRes.data.data ?? [];
  } catch (error) {
    message.error('加载数据体检结果失败');
  } finally {
    loading.value = false;
  }
};

let unregister: (() => void) | null = null;

onMounted(() => {
  loadData();
  unregister = registerRefreshHandler(loadData);
});

onUnmounted(() => {
  if (unregister) {
    unregister();
  }
});
</script>

<style scoped>
.data-health-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.health-summary-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 24px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-2xl);
  background: var(--color-bg-surface);
}

.health-summary-card--pass {
  border-color: var(--color-success-light);
  background: var(--color-success-light);
}

.health-summary-card--warn {
  border-color: var(--color-warning-light);
  background: var(--color-warning-light);
}

.health-summary-card--fail {
  border-color: var(--color-error-light);
  background: var(--color-error-light);
}

.health-summary-left {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.health-status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--radius-full);
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-semibold);
  white-space: nowrap;
}

.health-status-badge--pass {
  color: var(--color-success);
  background: rgba(30, 165, 91, 0.14);
}

.health-status-badge--warn {
  color: var(--color-warning);
  background: rgba(201, 146, 47, 0.16);
}

.health-status-badge--fail {
  color: var(--color-error);
  background: rgba(224, 84, 84, 0.14);
}

.health-status-badge--unknown {
  color: var(--color-text-tertiary);
  background: var(--color-bg-surface-hover);
}

.health-summary-text {
  min-width: 0;
}

.health-summary-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  letter-spacing: -0.2px;
}

.health-summary-desc {
  margin-top: 4px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.health-summary-right {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-shrink: 0;
}

.health-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.health-meta-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.health-meta-value {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.health-run-btn {
  border-radius: var(--radius-full);
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
}

.health-count-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.health-count-card {
  padding: 18px 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-bg-secondary);
}

.health-count-value {
  font-size: var(--font-size-3xl);
  font-weight: var(--font-weight-semibold);
  line-height: 1.1;
}

.health-count-value--pass {
  color: var(--color-success);
}

.health-count-value--warn {
  color: var(--color-warning);
}

.health-count-value--fail {
  color: var(--color-error);
}

.health-count-label {
  margin-top: 6px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.health-section-title {
  margin-bottom: 12px;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.health-table {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  overflow: hidden;
}

.health-item-name {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.health-item-key {
  display: block;
  margin-top: 2px;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.health-item-message {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.health-history-list {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  overflow: hidden;
}

.health-history-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-divider);
  font-size: var(--font-size-sm);
}

.health-history-row:last-child {
  border-bottom: none;
}

.health-history-date {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  width: 100px;
  flex-shrink: 0;
}

.health-history-summary {
  flex: 1;
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.health-history-time {
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

@media (max-width: 900px) {
  .health-summary-card {
    flex-direction: column;
    align-items: flex-start;
  }

  .health-summary-right {
    gap: 18px;
    flex-wrap: wrap;
  }

  .health-count-grid {
    grid-template-columns: 1fr;
  }
}
</style>
