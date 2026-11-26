<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECBasicOption } from 'echarts/types/dist/shared'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
])

/**
 * 图表库封装：唯一允许直接接触 echarts 的地方。
 * 页面只传 option（普通对象）与高度，其余由本组件负责：
 * 初始化、更新（不合并旧配置）、自适应容器尺寸、卸载时销毁实例。
 */
const props = withDefaults(
  defineProps<{
    option: ECBasicOption
    height?: string
  }>(),
  { height: '260px' },
)

const container = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
let resizeFrame: number | null = null

function apply(): void {
  if (chart) {
    chart.setOption(props.option, { notMerge: true })
  }
}

onMounted(() => {
  if (!container.value) return
  chart = echarts.init(container.value)
  apply()
  observer = new ResizeObserver(() => {
    if (resizeFrame !== null) return
    resizeFrame = globalThis.requestAnimationFrame(() => {
      resizeFrame = null
      chart?.resize()
    })
  })
  observer.observe(container.value)
})

watch(
  () => props.option,
  () => {
    apply()
  },
  { deep: true },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  if (resizeFrame !== null) globalThis.cancelAnimationFrame(resizeFrame)
  resizeFrame = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="container" class="base-chart" :style="{ height }" role="img"></div>
</template>

<style scoped>
.base-chart {
  width: 100%;
}
</style>
