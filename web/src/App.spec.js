import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import { ensureIdentity } from './stores/session'

vi.mock('./stores/session', () => ({
  session: { user: null },
  ensureIdentity: vi.fn(),
  signOut: vi.fn()
}))

const passThrough = { template: '<div><slot /></div>' }
const stubs = {
  'el-container': passThrough,
  'el-header': passThrough,
  'el-main': passThrough,
  'el-menu': passThrough,
  'el-menu-item': passThrough,
  'el-button': passThrough,
  'el-alert': {
    props: ['title'],
    template: '<div role="alert">{{ title }}</div>'
  }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', meta: { public: true }, component: { template: '<div data-test="login">登录页</div>' } },
      { path: '/share', name: 'share', meta: { public: true }, component: { template: '<div data-test="share">分享页</div>' } },
      { path: '/media', name: 'media', component: { template: '<div data-test="media">素材页</div>' } }
    ]
  })
}

describe('application entry when identity cannot be checked', () => {
  let wrapper

  beforeEach(() => {
    vi.clearAllMocks()
    ensureIdentity.mockRejectedValue(new Error('network unavailable'))
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it.each([
    ['/login', 'login'],
    ['/share', 'share']
  ])('renders the public %s page without an identity request', async (path, page) => {
    const router = makeRouter()
    await router.push(path)
    await router.isReady()

    wrapper = mount(App, { global: { plugins: [router], stubs } })
    await flushPromises()

    expect(wrapper.find(`[data-test="${page}"]`).exists()).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(ensureIdentity).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe(path)
  })

  it('keeps a private page in the offline state without treating it as signed out', async () => {
    const router = makeRouter()
    await router.push('/media')
    await router.isReady()

    wrapper = mount(App, { global: { plugins: [router], stubs } })
    await flushPromises()

    expect(ensureIdentity).toHaveBeenCalledOnce()
    expect(wrapper.find('[role="alert"]').text()).toContain('无法连接服务器')
    expect(wrapper.find('[data-test="media"]').exists()).toBe(false)
    expect(router.currentRoute.value.path).toBe('/media')
  })

  it('redirects a confirmed signed-out visitor to login without an offline warning', async () => {
    ensureIdentity.mockResolvedValue(null)
    const router = makeRouter()
    await router.push('/media')
    await router.isReady()

    wrapper = mount(App, { global: { plugins: [router], stubs } })
    await flushPromises()

    expect(ensureIdentity).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="login"]').exists()).toBe(true)
  })
})
