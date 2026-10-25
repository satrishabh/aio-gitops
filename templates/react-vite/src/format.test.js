import test from 'node:test'
import assert from 'node:assert/strict'
import { compact, percent } from './format.js'

test('compact formats thousands and millions', () => {
  assert.equal(compact(999), '999')
  assert.equal(compact(12840), '12.8K')
  assert.equal(compact(2000000), '2M')
})

test('percent formats ratios', () => {
  assert.equal(percent(0.124), '12.4%')
  assert.equal(percent(0.5), '50%')
})
