import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * Routes.
 *
 * The share landing page uses the URL fragment, so the raw share token never reaches the server in
 * a path or a query string. It is read from the fragment, exchanged once for a session cookie, and
 * then removed from the address bar - which is also why history mode is used with a fragment rather
 * than a path parameter.
 */
const routes = [
  { path: '/', redirect: '/media' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/media',
    name: 'media',
    component: () => import('../views/MediaLibraryView.vue'),
    meta: { title: '素材库' }
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('../views/TasksView.vue'),
    meta: { title: '处理任务' }
  },
  {
    path: '/tasks/:taskId',
    name: 'task-detail',
    component: () => import('../views/TaskDetailView.vue'),
    meta: { title: '任务详情' }
  },
  {
    path: '/workspace',
    name: 'workspace',
    component: () => import('../views/WorkspaceView.vue'),
    meta: { title: '共享空间' }
  },
  {
    path: '/share',
    name: 'share',
    component: () => import('../views/ShareView.vue'),
    meta: { public: true, title: '分享的素材' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
