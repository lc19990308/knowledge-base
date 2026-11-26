import { resolvedTheme } from './theme'

/**
 * 设计 token 读取工具：让 echarts 等画布渲染也能取用 CSS 变量里的主题色。
 * 放在 .ts 模块中，页面按需导入；不引入任何图表库类型。
 */
export function designToken(name: string): string {
  // 读取响应式主题状态：让引用 designToken 的 computed 在切换主题后重新求值。
  // 少了这一行，图表会一直停留在切换前的颜色（getComputedStyle 本身不参与响应式）。
  // eslint-disable-next-line @typescript-eslint/no-meaningless-void-operator -- 读取 .value 是为了注册响应式依赖，返回值无意义
  void resolvedTheme.value
  return getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim()
}

/** 十六进制颜色转 rgba，透明度与主题色同源。 */
export function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace('#', '').trim()
  if (value.length !== 6) {
    return hex
  }
  const red = Number.parseInt(value.slice(0, 2), 16)
  const green = Number.parseInt(value.slice(2, 4), 16)
  const blue = Number.parseInt(value.slice(4, 6), 16)
  return `rgba(${String(red)}, ${String(green)}, ${String(blue)}, ${String(alpha)})`
}
