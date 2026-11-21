import { useEffect, useState } from 'react'
import { api } from './api.js'

/*
 * The delivery environments, in order, from GET /api/environments (dev → staging → prod → …).
 * One shared fetch for every page; until it answers (or if it fails) the classic three are used, which is
 * exactly what the platform seeds, so nothing jumps on first paint.
 */

export const DEFAULT_ENVIRONMENTS = [
  { name: 'dev', promoteFrom: null, approvalRequired: false, prodLike: false, cluster: null, namespace: 'dev' },
  { name: 'staging', promoteFrom: 'dev', approvalRequired: false, prodLike: false, cluster: null, namespace: 'staging' },
  { name: 'prod', promoteFrom: 'staging', approvalRequired: true, prodLike: true, cluster: null, namespace: 'prod' },
]

let cache = null
let inflight = null
const listeners = new Set()

function load(force = false) {
  if (inflight && !force) return inflight
  inflight = api('/api/environments')
    .then(list => {
      if (Array.isArray(list) && list.length) {
        cache = list
        listeners.forEach(fn => fn(list))
      }
      return cache
    })
    .catch(() => cache)
    .finally(() => { setTimeout(() => { inflight = null }, 30000) })
  return inflight
}

/** Re-read after an admin adds or removes an environment. */
export function refreshEnvironments() {
  inflight = null
  return load(true)
}

/** Helpers over an environment list. */
export function envHelpers(list) {
  const envs = list && list.length ? list : DEFAULT_ENVIRONMENTS
  const byName = Object.fromEntries(envs.map(e => [e.name, e]))
  const names = envs.map(e => e.name)
  // next stop(s): environments promoted from this one (the first is the main path)
  const next = {}
  envs.forEach(e => { if (e.promoteFrom && !next[e.promoteFrom]) next[e.promoteFrom] = e.name })
  return {
    envs,
    names,
    next,
    byName,
    prev: name => byName[name]?.promoteFrom || null,
    /** Changes need an approver (prod and anything after it). */
    gated: name => !!(byName[name]?.approvalRequired || byName[name]?.prodLike),
    prodLike: name => !!byName[name]?.prodLike,
    cluster: name => byName[name]?.cluster || null,
    many: envs.length > 3,
    /** "dev → staging → prod → edge" */
    path: names.join(' → '),
  }
}

export function useEnvironments() {
  const [list, setList] = useState(cache)
  useEffect(() => {
    listeners.add(setList)
    load()
    return () => { listeners.delete(setList) }
  }, [])
  return envHelpers(list)
}
