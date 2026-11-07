import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'

const source = (await readFile(new URL('../src/appearance.js', import.meta.url), 'utf8')).replaceAll('export ', '')
function setup(saved, legacy = false, blocked = false) {
  let stored = saved
  const events = {}
  const meta = {}
  const media = { matches: false }
  media[legacy ? 'addListener' : 'addEventListener'] = (...args) => { events.system = args.at(-1) }
  const document = { documentElement: { dataset: {}, style: {} }, querySelectorAll: () => [{ setAttribute: (key, value) => { meta[key] = value } }] }
  const context = vm.createContext({ document, window: { matchMedia: () => media, addEventListener: (key, fn) => { events[key] = fn } }, localStorage: {
    getItem: () => { if (blocked) throw Error('blocked'); return stored },
    setItem: (_, value) => { if (blocked) throw Error('blocked'); stored = value },
    removeItem: () => { if (blocked) throw Error('blocked'); stored = null },
  } })
  vm.runInContext(source + '\nglobalThis.api = { getAppearance, setAppearance, subscribeAppearance }', context)
  return { ...context.api, document, media, events, meta, save: value => { stored = value } }
}
test('manual modes apply immediately, notify subscribers, and survive system changes', () => {
  const app = setup('invalid')
  assert.equal(app.getAppearance(), 'system')
  let updates = 0
  const unsubscribe = app.subscribeAppearance(() => updates++)
  app.setAppearance('dark')
  assert.equal(app.document.documentElement.dataset.theme, 'dark')
  assert.equal(app.document.documentElement.style.colorScheme, 'dark')
  assert.equal(app.meta.content, '#000000')
  app.events.system()
  assert.equal(app.document.documentElement.dataset.theme, 'dark')
  app.setAppearance('light')
  assert.equal(app.document.documentElement.dataset.theme, 'light')
  assert.equal(updates, 2)
  unsubscribe()
  app.setAppearance('dark')
  assert.equal(updates, 2)
})
test('Auto follows live device changes including legacy media listeners', () => {
  for (const legacy of [false, true]) {
    const app = setup('light', legacy)
    app.setAppearance('system')
    app.media.matches = true
    app.events.system()
    assert.equal(app.document.documentElement.dataset.theme, 'dark')
    app.media.matches = false
    app.events.system()
    assert.equal(app.document.documentElement.dataset.theme, 'light')
  }
})
test('cross-tab updates and cleared preferences stay synchronized', () => {
  const app = setup('light')
  app.save('dark')
  app.events.storage({ key: 'aio-theme' })
  assert.equal(app.getAppearance(), 'dark')
  assert.equal(app.document.documentElement.dataset.theme, 'dark')
  app.save(null)
  app.events.storage({ key: null })
  assert.equal(app.getAppearance(), 'system')
})
test('manual appearance works when browser storage is blocked', () => {
  const app = setup(null, false, true)
  app.setAppearance('dark')
  assert.equal(app.document.documentElement.dataset.theme, 'dark')
})
