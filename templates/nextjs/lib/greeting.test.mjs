import test from 'node:test'
import assert from 'node:assert/strict'
import { greeting } from './greeting.mjs'

test('greets by name', () => {
  assert.equal(greeting('Ada'), 'Hello, Ada!')
  assert.equal(greeting('  '), 'Hello, world!')
})
