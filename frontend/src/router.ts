import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { navigation, platformTitle } from './navigation'
import { ensureSession, isAdmin } from './auth'
import AppLayout from './layouts/AppLayout.vue'

const workbenchPath = '/'
const pendingPage = () => import('./views/ModuleView.vue')

/** 仅系统管理员可进入的模块；其余页面由后端按角色过滤数据。 */
const adminOnly: ReadonlySet<string> = new Set([
  '/cleaning',
  '/settings',
  '/answer-templates',
  '/assistant-config',
])

/** 每个导航项都指向真实页面；未覆盖的路径仍回退到占位页。 */
const pageFor: Record<string, () => Promise<unknown>> = {
  '/knowledge': () => import('./views/KnowledgeView.vue'),
  '/cleaning': () => import('./views/CleaningView.vue'),
  '/assistant': () => import('./views/AssistantView.vue'),
  '/assistant-config': () => import('./views/AssistantConfigView.vue'),
  '/answer-templates': () => import('./views/AnswerTemplatesView.vue'),
  '/publishing': () => import('./views/PublishingView.vue'),
  '/statistics': () => import('./views/StatisticsView.vue'),
  '/settings': () => import('./views/SettingsView.vue'),
}

const modules = navigation
  .flatMap(({ items }) => [...items])
  .filter(({ path }) => path !== workbenchPath)

const appPages: RouteRecordRaw[] = [
  {
    path: '',
    component: () => import('./views/WorkbenchView.vue'),
    meta: { title: '工作台' },
  },
  {
    path: '/documents/:id',
    name: 'document',
    component: () => import('./views/DocumentView.vue'),
    props: true,
    meta: { title: '文档详情' },
  },
  {
    path: '/import',
    redirect: (to) => ({
      path: '/knowledge',
      query:
        typeof to.query.space === 'string'
          ? { uploadSpace: to.query.space }
          : {},
    }),
  },
  ...modules.map(({ path, label }) => ({
    path,
    component: pageFor[path] ?? pendingPage,
    props: { title: label },
    meta: { title: label, requiresAdmin: adminOnly.has(path) },
  })),
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('./views/ForbiddenView.vue'),
    meta: { title: '无权访问', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    component: pendingPage,
    props: { title: '找不到该页面', message: '请返回工作台。' },
    meta: { title: '页面不存在' },
  },
]

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('./views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  { path: '/', component: AppLayout, children: appPages },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

/** 全站路由守卫：未登录去登录页；管理员模块校验角色。 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const user = await ensureSession()
  if (!user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !isAdmin()) {
    return { path: '/403' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title =
    typeof title === 'string' ? `${title} · ${platformTitle}` : platformTitle
})
