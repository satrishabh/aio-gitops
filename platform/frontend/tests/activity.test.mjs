// node --test platform/frontend/tests/activity.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { createActivity, IDLE_MS } from '../src/activity.js'

function fake() {
  const state = { t: 0, hidden: false, resumed: 0 }
  const a = createActivity({ now: () => state.t, hidden: () => state.hidden })
  a.onResume(() => state.resumed++)
  return { a, state }
}

test('polls while the user is active and the tab is visible', () => {
  const { a, state } = fake()
  state.t = IDLE_MS - 1
  assert.equal(a.paused(), false)
})

test('pauses after the idle window without input', () => {
  const { a, state } = fake()
  state.t = IDLE_MS + 1
  assert.equal(a.paused(), true)
})

test('input after idling resumes polling once', () => {
  const { a, state } = fake()
  state.t = IDLE_MS + 1
  a.touch()
  assert.equal(a.paused(), false)
  assert.equal(state.resumed, 1)
  a.touch() // already active: no extra resume
  assert.equal(state.resumed, 1)
})

test('hidden tab pauses even with recent input, and showing it resumes', () => {
  const { a, state } = fake()
  state.hidden = true
  assert.equal(a.paused(), true)
  a.touch()
  assert.equal(state.resumed, 0, 'no resume while hidden')
  state.hidden = false
  a.visible()
  assert.equal(a.paused(), false)
  assert.equal(state.resumed, 1)
})

test('unsubscribe stops resume notifications', () => {
  const { a, state } = fake()
  let extra = 0
  const off = a.onResume(() => extra++)
  off()
  state.t = IDLE_MS + 1
  a.touch()
  assert.equal(extra, 0)
  assert.equal(state.resumed, 1)
})
