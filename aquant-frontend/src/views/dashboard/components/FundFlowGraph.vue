<template>
  <div class="fund-flow-graph" :style="{ '--graph-min-height': minHeight }">
    <div ref="chartRef" class="graph-chart-container"></div>
    <div v-if="loading" class="graph-loading-mask">
      <a-spin />
    </div>
    <div class="floating-zoom-toolbar">
      <a-tooltip title="放大视图" placement="left">
        <a-button type="text" class="zoom-btn" @click="handleZoomIn">
          <template #icon><plus-outlined /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip title="缩小视图" placement="left">
        <a-button type="text" class="zoom-btn" @click="handleZoomOut">
          <template #icon><minus-outlined /></template>
        </a-button>
      </a-tooltip>
      <a-tooltip title="重置视角" placement="left">
        <a-button type="text" class="zoom-btn" @click="handleResetView">
          <template #icon><redo-outlined /></template>
        </a-button>
      </a-tooltip>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { PlusOutlined, MinusOutlined, RedoOutlined } from '@ant-design/icons-vue';
import type { FundFlowGraphData } from '@/api/fundFlow';

/**
 * 板块资金博弈气泡图（可复用）。
 * 仪表盘卡片与新页面全屏视图共用同一份实现，避免两处逻辑分叉。
 */
const props = withDefaults(defineProps<{
  data: FundFlowGraphData | null;
  loading?: boolean;
  /** 图表最小高度，卡片里 330px，全屏页可给更大值 */
  minHeight?: string;
}>(), {
  loading: false,
  minHeight: '330px'
});

const chartRef = ref<HTMLDivElement | null>(null);
let chartInstance: echarts.ECharts | null = null;
let chartResizeObserver: ResizeObserver | null = null;
let chartResizeFrame: number | null = null;

/**
 * 板块资金流数值格式化。
 * 后端 /stockMarket/fundFlow/graph 返回的 netInflow 单位**已经是「亿元」**，
 * 不要再当成「元」去除以 1e8。
 */
const formatAmount = (val: number | null | undefined): string => {
  if (val === null || val === undefined) return '--';
  const abs = Math.abs(val);
  const sign = val > 0 ? '+' : val < 0 ? '-' : '';
  if (abs >= 10000) {
    return sign + (abs / 10000).toFixed(2) + '万亿';
  }
  return sign + abs.toFixed(2) + '亿';
};

const renderChart = () => {
  if (!chartRef.value || !props.data) return;

  const rect = chartRef.value.getBoundingClientRect();
  if (!chartInstance) {
    // 容器还不可见（例如卡片处于隐藏的分页）时先不初始化，等 ResizeObserver 触发后再渲染
    if (rect.width <= 0 || rect.height <= 0) return;
    chartInstance = echarts.init(chartRef.value);
  }

  const nodes = (props.data.nodes || []).map(node => {
    let color = '#94a3b8';
    if (node.changePercent !== null && node.changePercent !== undefined) {
      if (node.changePercent > 0) {
        color = node.changePercent > 3 ? '#c53030' : '#e05454';
      } else if (node.changePercent < 0) {
        color = node.changePercent < -3 ? '#15803d' : '#1ea55b';
      }
    }

    return {
      id: node.id,
      name: node.name,
      symbolSize: node.symbolSize || 40,
      itemStyle: {
        color: color,
        shadowBlur: 8,
        shadowColor: 'rgba(0, 0, 0, 0.15)'
      },
      label: {
        show: true,
        fontSize: 11,
        color: '#ffffff',
        fontWeight: 'bold' as const,
        textShadowBlur: 3,
        textShadowColor: 'rgba(0, 0, 0, 0.5)',
        textShadowOffsetX: 1,
        textShadowOffsetY: 1
      },
      raw: node
    };
  });

  const links = (props.data.links || []).map(link => ({
    source: link.source,
    target: link.target,
    lineStyle: {
      width: link.weight || 2,
      curveness: 0.2,
      color: '#cbd5e1',
      opacity: 0.6
    }
  }));

  // 布局用固定的紧凑参数（力导向的展开范围基本与容器无关）；
  // 画布变大时不放大斥力（否则节点被推散），而是把整体视图缩放调大来填满画布。
  const canvasWidth = chartRef.value.clientWidth || 800;
  const canvasHeight = chartRef.value.clientHeight || 600;
  const baseZoom = 0.9;
  const fitZoom = baseZoom * Math.sqrt((canvasWidth * canvasHeight) / (900 * 600));
  const viewZoom = Math.max(baseZoom, Math.min(fitZoom, 1.7));

  const option: echarts.EChartsOption = {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          const raw = params.data.raw;
          const netInflowStr = raw.netInflow === null || raw.netInflow === undefined ? '--' : formatAmount(raw.netInflow);
          const pctStr = raw.changePercent !== null ? (raw.changePercent > 0 ? '+' : '') + raw.changePercent + '%' : '--';
          return `
            <div style="font-weight:bold;margin-bottom:4px;">${raw.name}</div>
            <div>涨跌幅: <span style="font-weight:bold;color:${raw.changePercent >= 0 ? '#e05454' : '#1ea55b'}">${pctStr}</span></div>
            <div>主力净流入: <span style="font-weight:bold;">${netInflowStr}</span></div>
          `;
        }
        return '';
      }
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        data: nodes,
        links: links,
        roam: true,
        label: {
          position: 'inside',
          formatter: '{b}'
        },
        force: {
          repulsion: 180,
          gravity: 0.08,
          edgeLength: [50, 120],
          friction: 0.6
        },
        center: ['50%', '50%'],
        zoom: viewZoom,
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [4, 8],
        cursor: 'pointer'
      }
    ]
  };

  chartInstance.setOption(option);
  chartInstance.resize();

  if (chartRef.value) {
    chartRef.value.removeEventListener('mousedown', handleGraphMouseDown);
    chartRef.value.addEventListener('mousedown', handleGraphMouseDown);
    chartRef.value.removeEventListener('wheel', handleGraphWheel);
    chartRef.value.addEventListener('wheel', handleGraphWheel, { passive: false });
    chartRef.value.style.cursor = 'grab';
  }
};

let isDraggingGraph = false;
let startGraphX = 0;
let startGraphY = 0;

const handleGraphMouseDown = (e: MouseEvent) => {
  if (e.button !== 0 || !chartInstance) return;
  isDraggingGraph = true;
  startGraphX = e.clientX;
  startGraphY = e.clientY;
  if (chartRef.value) {
    chartRef.value.style.cursor = 'grabbing';
  }
};

const handleGraphMouseMove = (e: MouseEvent) => {
  if (!isDraggingGraph || !chartInstance) return;
  const dx = e.clientX - startGraphX;
  const dy = e.clientY - startGraphY;
  startGraphX = e.clientX;
  startGraphY = e.clientY;

  chartInstance.dispatchAction({
    type: 'graphRoam',
    dx: dx,
    dy: dy
  });
};

const handleGraphMouseUp = () => {
  if (isDraggingGraph) {
    isDraggingGraph = false;
    if (chartRef.value) {
      chartRef.value.style.cursor = 'grab';
    }
  }
};

const handleZoomIn = () => {
  if (!chartInstance) return;
  const width = chartInstance.getWidth();
  const height = chartInstance.getHeight();
  chartInstance.dispatchAction({
    type: 'graphRoam',
    zoom: 1.25,
    originX: width / 2,
    originY: height / 2
  });
};

const handleZoomOut = () => {
  if (!chartInstance) return;
  const width = chartInstance.getWidth();
  const height = chartInstance.getHeight();
  chartInstance.dispatchAction({
    type: 'graphRoam',
    zoom: 0.8,
    originX: width / 2,
    originY: height / 2
  });
};

const handleResetView = () => {
  if (!chartInstance) return;
  renderChart();
};

const handleGraphWheel = (e: WheelEvent) => {
  if (!chartInstance) return;
  e.preventDefault();
  const zoom = e.deltaY < 0 ? 1.1 : 0.9;
  const rect = chartRef.value?.getBoundingClientRect();
  const originX = rect ? e.clientX - rect.left : 0;
  const originY = rect ? e.clientY - rect.top : 0;

  chartInstance.dispatchAction({
    type: 'graphRoam',
    zoom: zoom,
    originX: originX,
    originY: originY
  });
};

const handleResize = () => {
  if (chartInstance) {
    chartInstance.resize();
  }
};

const observeChartSize = () => {
  if (!chartRef.value || typeof ResizeObserver === 'undefined') return;
  chartResizeObserver?.disconnect();
  chartResizeObserver = new ResizeObserver(entries => {
    const entry = entries[0];
    if (!entry || entry.contentRect.width <= 0 || entry.contentRect.height <= 0) return;
    if (chartResizeFrame !== null) {
      cancelAnimationFrame(chartResizeFrame);
    }
    chartResizeFrame = requestAnimationFrame(() => {
      chartResizeFrame = null;
      if (chartInstance) {
        chartInstance.resize();
      } else {
        renderChart();
      }
    });
  });
  chartResizeObserver.observe(chartRef.value);
};

watch(() => props.data, () => {
  nextTick(() => renderChart());
});

onMounted(() => {
  nextTick(() => {
    observeChartSize();
    renderChart();
  });
  window.addEventListener('resize', handleResize);
  window.addEventListener('mousemove', handleGraphMouseMove);
  window.addEventListener('mouseup', handleGraphMouseUp);
});

onUnmounted(() => {
  chartResizeObserver?.disconnect();
  chartResizeObserver = null;
  if (chartResizeFrame !== null) {
    cancelAnimationFrame(chartResizeFrame);
    chartResizeFrame = null;
  }
  window.removeEventListener('resize', handleResize);
  window.removeEventListener('mousemove', handleGraphMouseMove);
  window.removeEventListener('mouseup', handleGraphMouseUp);
  if (chartRef.value) {
    chartRef.value.removeEventListener('wheel', handleGraphWheel);
  }
  if (chartInstance) {
    chartInstance.dispose();
    chartInstance = null;
  }
});
</script>

<style scoped>
.fund-flow-graph {
  width: 100%;
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* 图表容器直接作为 flex 子项撑满父级（不要再套 a-spin 包装层，否则高度传不下来） */
.graph-chart-container {
  width: 100%;
  flex: 1 1 auto;
  height: 100%;
  min-height: var(--graph-min-height, 330px);
}

.graph-loading-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.55);
  z-index: 3;
  border-radius: 8px;
}

.floating-zoom-toolbar {
  position: absolute;
  bottom: 8px;
  right: 8px;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.zoom-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
}
</style>
