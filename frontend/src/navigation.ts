import {
  BookOpen,
  ChartNoAxesColumn,
  LayoutDashboard,
  MessageSquare,
  Radio,
  Settings,
  Sparkles,
  SlidersHorizontal,
  Bot,
} from '@lucide/vue'

export const platformTitle = 'AI 智能问答平台'

export const navigation = [
  {
    label: '工作区',
    items: [
      { path: '/', label: '工作台', icon: LayoutDashboard },
      { path: '/knowledge', label: '知识库', icon: BookOpen },
      { path: '/cleaning', label: '数据清洗', icon: Sparkles },
      { path: '/assistant', label: 'AI 助手', icon: MessageSquare },
    ],
  },
  {
    label: '管理',
    items: [
      { path: '/assistant-config', label: '助手配置', icon: Bot },
      { path: '/answer-templates', label: '回答模板', icon: SlidersHorizontal },
      { path: '/publishing', label: '发布管理', icon: Radio },
      { path: '/statistics', label: '问答统计', icon: ChartNoAxesColumn },
      { path: '/settings', label: '系统设置', icon: Settings },
    ],
  },
] as const
