import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from './LoginView.vue'
import MediaLibraryView from './MediaLibraryView.vue'

const fixtures = vi.hoisted(() => ({
  session: { user: null, spaceId: null },
  browseAsGuest: vi.fn(), signIn: vi.fn(), rememberSpace: vi.fn(),
  spaces: { list: vi.fn(), create: vi.fn() },
  media: { list: vi.fn(), posterUrl: vi.fn(), remove: vi.fn(), rename: vi.fn() }
}))
vi.mock('../stores/session', () => ({
  session: fixtures.session, browseAsGuest: fixtures.browseAsGuest,
  signIn: fixtures.signIn, rememberSpace: fixtures.rememberSpace
}))
vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {}, spaces: fixtures.spaces, media: fixtures.media
}))
vi.mock('../components/UploadQueue.vue', () => ({ default: { template: '<div data-test="upload-panel">上传来源</div>' } }))

const passthrough = { template: '<div><slot /><slot name="header" /></div>' }
const button = { props: ['loading', 'disabled'], template: '<button :disabled="loading || disabled"><slot /></button>' }
const stubs = {
  'el-card': passthrough, 'el-form': passthrough, 'el-form-item': passthrough,
  'el-button': button, 'el-input': { template: '<input>' },
  'el-alert': { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  'el-select': passthrough, 'el-option': true, 'el-empty': true,
  'el-table': true, 'el-table-column': true, 'el-tag': passthrough,
  'el-dialog': true, 'el-pagination': true
}

async function router() {
  const result = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/media', name: 'media', component: { template: '<div />' } }]
  })
  await result.push('/login')
  await result.isReady()
  return result
}

describe('visitor entry and read-only controls', () => {
  let wrapper
  beforeEach(() => {
    vi.clearAllMocks()
    fixtures.session.user = null
    fixtures.session.spaceId = null
    fixtures.browseAsGuest.mockResolvedValue({ userId: 'viewer-id', username: 'viewer', guest: true })
  })
  afterEach(() => { wrapper?.unmount(); wrapper = null })

  it('enters guest browsing without collecting account credentials', async () => {
    const navigation = await router()
    wrapper = mount(LoginView, { global: { plugins: [navigation], stubs } })
    const entry = wrapper.findAll('button').find((item) => item.text() === '游客浏览')
    expect(entry).toBeDefined()
    await entry.trigger('click')
    await flushPromises()
    expect(fixtures.browseAsGuest).toHaveBeenCalledOnce()
    expect(fixtures.browseAsGuest).toHaveBeenCalledWith()
    expect(fixtures.signIn).not.toHaveBeenCalled()
    expect(navigation.currentRoute.value.name).toBe('media')
    expect(wrapper.text()).toContain('账号登录')
  })

  it('keeps a failed guest session on the entry page without assuming read access', async () => {
    fixtures.browseAsGuest.mockRejectedValue(new Error('guest unavailable'))
    const navigation = await router()
    wrapper = mount(LoginView, { global: { plugins: [navigation], stubs } })
    await wrapper.findAll('button').find((item) => item.text() === '游客浏览').trigger('click')
    await flushPromises()
    expect(navigation.currentRoute.value.name).toBe('login')
    expect(wrapper.find('[role="alert"]').text()).toContain('无法进入游客浏览')
  })

  it('hides writes for a server-marked guest even if a workspace role is later elevated', async () => {
    fixtures.session.user = { userId: 'viewer-id', username: 'viewer', guest: true }
    fixtures.spaces.list.mockResolvedValue([{ spaceId: 'demo', name: '游客演示', role: 'OWNER' }])
    fixtures.media.list.mockResolvedValue({ items: [{ mediaId: 'clip', title: '演示片段', status: 'READY', createdAt: '2026-10-09T00:00:00Z', durationMs: 1000, width: 320, height: 240 }], page: 1, pageSize: 20, total: 1 })
    fixtures.media.posterUrl.mockReturnValue('/poster')
    wrapper = mount(MediaLibraryView, { global: { plugins: [await router()], stubs, directives: { loading: {} } } })
    await flushPromises()
    const labels = wrapper.findAll('button').map((item) => item.text())
    expect(labels).toContain('播放')
    for (const write of ['新建空间', '重命名', '删除']) expect(labels).not.toContain(write)
    expect(wrapper.find('[data-test="upload-panel"]').exists()).toBe(false)
    expect(fixtures.spaces.create).not.toHaveBeenCalled()
    expect(fixtures.media.remove).not.toHaveBeenCalled()
  })
})
