import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, fmtTime } from '../api.js'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { Empty, PageHead } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

/*
 * Connectors, redesign phase 5: the catalog grouped by phase (core first, planned last), each phase with its
 * progress and its switch in the header, a search box and kind / status filter chips over the loaded catalog,
 * and one card per connector with its health in words, its versions and the same actions as before.
 */

// status -> [tone, label]. Tone picks the colour; the label always says it in words.
const STATUS = {
  HEALTHY: ['ok', 'Healthy'], PROGRESSING: ['info', 'Installing'], DEGRADED: ['bad', 'Degraded'],
  NOT_INSTALLED: ['off', 'Not installed'], PLANNED: ['off', 'Planned'], NOT_FOR_THIS_CLOUD: ['off', 'Not for this cloud'],
  OFF: ['off', 'Off'], UNKNOWN: ['warn', 'Unknown'],
}
const KIND = { CORE: 'Core', MANAGED: 'Managed', PLANNED: 'Planned' }
const KIND_HINT = { CORE: 'installed by the bootstrap scripts', MANAGED: 'installed from here through Argo CD', PLANNED: 'not installable yet' }

// status filter chips: each groups one or more backend statuses
const STATUS_FILTERS = [
  { id: 'healthy', label: 'Healthy', match: ['HEALTHY'] },
  { id: 'attention', label: 'Needs attention', match: ['DEGRADED', 'UNKNOWN'] },
  { id: 'installing', label: 'Installing', match: ['PROGRESSING'] },
  { id: 'off', label: 'Not enabled', match: ['NOT_INSTALLED', 'OFF'] },
  { id: 'unavailable', label: 'Planned / other cloud', match: ['PLANNED', 'NOT_FOR_THIS_CLOUD'] },
]
const statusOf = c => (STATUS[c.status] ? c.status : 'UNKNOWN')
const SYNC = { Synced: 'in sync', OutOfSync: 'out of sync', Unknown: 'sync unknown' }

// same order as the backend: numeric phases first, then the rest by name
const phaseOrder = (a, b) => {
  const n = p => (/^\d+$/.test(p) ? Number(p) : Number.MAX_SAFE_INTEGER)
  return n(a) - n(b) || a.localeCompare(b)
}

const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

// Connectors projects can attach (Project → Services). The API marks them (binding / bindable); until it does,
// the first wave from docs/architecture/PROJECT-SERVICES.md stands in.
const FIRST_WAVE = ['cloudnative-pg', 'valkey', 'strimzi', 'opensearch', 'opentelemetry-collector', 'temporal', 'dapr', 'langfuse', 'milvus']
const isInstalled = c => c.kind === 'MANAGED' && !['NOT_INSTALLED', 'NOT_FOR_THIS_CLOUD', 'PLANNED'].includes(c.status)
function bindableIds(connectors) {
  const flagged = connectors.some(c => 'bindable' in c || 'binding' in c)
  return new Set(connectors.filter(c => (flagged ? c.bindable === true || !!c.binding : FIRST_WAVE.includes(c.id))).map(c => c.id))
}

/** /usage -> [{ id, name, envs }] whatever the exact shape (list of rows, or { projects: [...] }). */
function normUsage(raw) {
  const rows = Array.isArray(raw) ? raw : Array.isArray(raw?.projects) ? raw.projects : Array.isArray(raw?.usage) ? raw.usage : []
  const byProject = new Map()
  for (const r of rows) {
    const p = r.project && typeof r.project === 'object' ? r.project : null
    const id = r.projectId ?? p?.id ?? r.id
    const name = r.projectName ?? p?.name ?? r.name ?? (typeof r.project === 'string' ? r.project : String(id))
    const key = String(id ?? name)
    if (!byProject.has(key)) byProject.set(key, { id, name, envs: [] })
    const envs = r.envs || (r.env ? [r.env] : [])
    for (const e of envs) if (!byProject.get(key).envs.includes(e)) byProject.get(key).envs.push(e)
  }
  return [...byProject.values()].sort((a, b) => String(a.name).localeCompare(String(b.name)))
}

/** Groups: core platform (no phase), each phase, then planned (no phase). */
function groupConnectors(connectors) {
  const byPhase = new Map()
  const core = [], later = []
  for (const c of connectors) {
    if (c.phase) {
      if (!byPhase.has(c.phase)) byPhase.set(c.phase, [])
      byPhase.get(c.phase).push(c)
    } else (c.kind === 'CORE' ? core : later).push(c)
  }
  const groups = []
  if (core.length) groups.push({ key: 'core', title: 'Core platform', sub: 'Installed with AIO and always on.', list: core })
  for (const p of [...byPhase.keys()].sort(phaseOrder)) groups.push({ key: `phase-${p}`, phase: p, title: `Phase ${p}`, list: byPhase.get(p) })
  if (later.length) groups.push({ key: 'later', title: 'Planned', sub: 'In scope, not installable from here yet.', list: later })
  return groups
}

function phaseState(p) {
  if (!p) return ['none', 'Nothing to switch on this cloud']
  if (p.on === 0) return ['off', 'Switched off']
  if (p.on === p.total) return ['on', 'Switched on']
  return ['part', 'Partly on']
}

function scrollToSection(e, id) {
  const el = document.getElementById(id)
  if (!el) return
  e.preventDefault()
  const smooth = !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' })
  el.querySelector('h2')?.focus({ preventScroll: true })
}

function LoadingState() {
  return (
    <div role="status" aria-busy="true" aria-label="Loading connectors">
      <div className="cn-phase-nav">{Array.from({ length: 7 }, (_, i) => <div key={i} className="skeleton cn-skel-tile" />)}</div>
      <div className="skeleton cn-skel-bar" />
      <div className="cn-grid">{Array.from({ length: 6 }, (_, i) => <div key={i} className="skeleton cn-skel-card" />)}</div>
    </div>
  )
}

export default function Connectors() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/connectors'), 15000)
  const toast = useToast()
  const [busy, setBusy] = useState({})
  const [tests, setTests] = useState({})
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState('')
  const [status, setStatus] = useState('')
  // connector id -> [{ id, name, envs }]; missing = not loaded / not available (404 hides it)
  const [usage, setUsage] = useState({})
  const [usageGen, setUsageGen] = useState(0)

  const connectors = data?.connectors || []
  const usageKey = connectors.filter(isInstalled).map(c => c.id).join(',')
  useEffect(() => {
    if (!usageKey) return undefined
    let alive = true
    const ids = bindableIds(connectors)
    for (const c of connectors.filter(x => isInstalled(x) && ids.has(x.id))) {
      // GET /api/connectors carries usedBy for bindable connectors; ask /usage only when it does not
      if (Array.isArray(c.usedBy)) { setUsage(u => ({ ...u, [c.id]: normUsage(c.usedBy) })); continue }
      api(`/api/connectors/${encodeURIComponent(c.id)}/usage`)
        .then(r => { if (alive) setUsage(u => ({ ...u, [c.id]: normUsage(r) })) })
        .catch(() => { if (alive) setUsage(u => { const n = { ...u }; delete n[c.id]; return n }) })
    }
    return () => { alive = false }
    // connectors is read through usageKey: refetch only when the installed set changes or after an action
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usageKey, usageGen])
  const phases = useMemo(() => new Map((data?.phases || []).map(p => [String(p.phase), p])), [data])
  const q = query.trim().toLowerCase()
  const filtered = useMemo(() => connectors.filter(c => {
    if (kind && c.kind !== kind) return false
    if (status && !STATUS_FILTERS.find(f => f.id === status).match.includes(statusOf(c))) return false
    if (!q) return true
    return [c.name, c.id, c.category, c.description, c.license, c.foundation, c.origin, KIND[c.kind], STATUS[statusOf(c)][1], ...(c.requires || [])]
      .some(v => v && String(v).toLowerCase().includes(q))
  }), [connectors, kind, status, q])
  const groups = useMemo(() => groupConnectors(filtered), [filtered])
  const filtering = !!(q || kind || status)

  const head = <PageHead title="Connectors"
    sub={data ? `Open-source tools AIO works with: ${count('CORE')} core, ${count('MANAGED')} managed from here, ${count('PLANNED')} planned. Cloud: ${data.cloud}.`
      : 'Open-source tools AIO works with.'} />
  function count(k) { return connectors.filter(c => c.kind === k).length }

  if (!data) return <>{head}{error
    ? <div className="cn-load-error" role="alert"><Icon name="x" size={16} /><span>Couldn't load the connectors: {error}</span>
        <button className="btn small" onClick={refresh}><Icon name="refresh" size={13} />Try again</button></div>
    : <LoadingState />}</>

  async function act(c, action, confirmText) {
    if (confirmText && !window.confirm(confirmText)) return
    setBusy(b => ({ ...b, [c.id]: action }))
    try {
      const r = await api(`/api/connectors/${c.id}/${action}`, { method: 'POST' })
      if (action === 'test') {
        setTests(t => ({ ...t, [c.id]: r }))
        toast(r.ok ? 'ok' : 'error', `${c.name}: ${r.ok ? 'all checks passed' : 'some checks failed'}`)
      } else {
        toast('ok', `${c.name}: ${r.status}${r.version ? ` (${r.version})` : ''}`)
        setUsageGen(g => g + 1)
      }
      refresh()
    } catch (err) {
      toast('error', `${c.name}: ${err.message}`)
    } finally {
      setBusy(b => ({ ...b, [c.id]: null }))
    }
  }

  async function switchPhase(p, state) {
    const text = state === 'on'
      ? `Switch on phase ${p.phase}? This installs ${p.connectors.join(', ')} (dependencies first). Do it once every system they need is ready (see docs/architecture/CONNECTORS.md).`
      : `Switch off phase ${p.phase}? This removes ${p.connectors.join(', ')} and everything they installed, stored data included.`
    if (!window.confirm(text)) return
    const key = `phase-${p.phase}`
    setBusy(b => ({ ...b, [key]: state }))
    try {
      const r = await api(`/api/connectors/phases/${encodeURIComponent(p.phase)}/${state}`, { method: 'POST' })
      const failed = r.steps.filter(s => !s.ok)
      toast(failed.length ? 'error' : 'ok', failed.length
        ? `Phase ${p.phase}: ${failed.map(s => `${s.name}: ${s.detail}`).join('; ')}`
        : `Phase ${p.phase} switched ${state}: ${r.steps.map(s => `${s.name} ${s.detail}`).join(', ')}`)
      refresh()
    } catch (err) {
      toast('error', `Phase ${p.phase}: ${err.message}`)
    } finally {
      setBusy(b => ({ ...b, [key]: null }))
    }
  }

  const clearFilters = () => { setQuery(''); setKind(''); setStatus('') }
  const kindCount = k => connectors.filter(c => c.kind === k).length
  const statusCount = f => connectors.filter(c => f.match.includes(statusOf(c))).length

  return (
    <>
      {head}
      <Stale stale={stale} error={error} onRetry={refresh} />
      {data.catalog && <p className="cn-catalog muted">
        Catalog: <span className="mono">{data.catalog.location}</span>{data.catalog.revision ? ` @ ${data.catalog.revision}` : ''}
        {data.catalog.loadedAt ? ` · loaded ${fmtTime(data.catalog.loadedAt)}` : ''}{data.site ? ` · site ${data.site}` : ''}
      </p>}
      {data.catalog?.lastError && <p className="error cn-catalog-error" role="alert">
        <Icon name="x" size={14} />Catalog refresh failed, showing the last good catalog: {data.catalog.lastError}</p>}
      <div className="banner cn-note"><Icon name="plug" size={15} />
        <span>Managed connectors install through Argo CD from a pinned chart. <b>Test</b> checks they answer, <b>Roll back</b> returns to the
        previous pinned version, <b>Disable</b> removes them. If one is down, AIO keeps working without it.
        A phase stays off until you switch it on: do that once every system it needs is ready. Switching off is the way back.</span>
      </div>

      {connectors.length === 0 ? <Empty icon="plug" title="No connectors in the catalog" /> : <>
        {phases.size > 0 && <nav className="cn-phase-nav" aria-label="Phases">
          {[...phases.values()].map(p => {
            const [st, label] = phaseState(p)
            return (
              <a key={p.phase} href={`#phase-${p.phase}`} className={`cn-phase-tile st-${st}`} onClick={e => scrollToSection(e, `phase-${p.phase}`)}>
                <span className="cn-phase-tile-name">Phase {p.phase}</span>
                <span className="cn-phase-tile-count"><b>{p.on}</b> of {p.total} enabled</span>
                <span className="cn-progress" aria-hidden="true"><span style={{ width: `${p.total ? (p.on / p.total) * 100 : 0}%` }} /></span>
                <span className="cn-phase-tile-state">{label}</span>
              </a>
            )
          })}
        </nav>}

        <div className="cn-toolbar" role="search">
          <label className="search cn-search">
            <span className="sr-only">Search connectors</span>
            <Icon name="search" size={15} />
            <input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search by name, category, licence…" />
          </label>
          <div className="cn-filters">
            <div className="cn-chip-group" role="group" aria-label="Filter by kind">
              <span className="cn-chip-label" aria-hidden="true">Kind</span>
              <button type="button" className={`chip${!kind ? ' active' : ''}`} aria-pressed={!kind} onClick={() => setKind('')}>All</button>
              {Object.entries(KIND).map(([k, label]) => (
                <button key={k} type="button" className={`chip${kind === k ? ' active' : ''}`} aria-pressed={kind === k}
                        title={KIND_HINT[k]} onClick={() => setKind(kind === k ? '' : k)}>
                  {label} <span className="cn-chip-n">{kindCount(k)}</span>
                </button>
              ))}
            </div>
            <div className="cn-chip-group" role="group" aria-label="Filter by status">
              <span className="cn-chip-label" aria-hidden="true">Status</span>
              <button type="button" className={`chip${!status ? ' active' : ''}`} aria-pressed={!status} onClick={() => setStatus('')}>All</button>
              {STATUS_FILTERS.map(f => (
                <button key={f.id} type="button" className={`chip cn-chip-${f.id}${status === f.id ? ' active' : ''}`} aria-pressed={status === f.id}
                        onClick={() => setStatus(status === f.id ? '' : f.id)}>
                  <span className="cn-chip-dot" aria-hidden="true" />{f.label} <span className="cn-chip-n">{statusCount(f)}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
        <p className="cn-result muted" role="status" aria-live="polite">
          {filtering ? <>Showing {plural(filtered.length, 'connector')} of {connectors.length}.{' '}
            <button type="button" className="link cn-clear" onClick={clearFilters}>Clear filters</button></>
            : `${plural(connectors.length, 'connector')} in ${plural(groups.length, 'group')}.`}
        </p>

        {filtered.length === 0
          ? <Empty icon="search" title="No connectors match" action={<button className="btn" onClick={clearFilters}>Clear filters</button>}>
              Nothing matches {q ? <>“{query.trim()}”</> : 'these filters'}{q && (kind || status) ? ' with these filters' : ''}. Try another word or clear the filters.
            </Empty>
          : groups.map(g => {
            const p = g.phase != null ? phases.get(String(g.phase)) : null
            const [st, stLabel] = phaseState(p)
            const b = p ? busy[`phase-${p.phase}`] : null
            const cats = [...new Set(g.list.map(c => c.category).filter(Boolean))]
            const headingId = `${g.key}-title`
            return (
              <section key={g.key} id={g.key} className={`cn-group${g.phase != null ? ` st-${st}` : ''}`} aria-labelledby={headingId}>
                <header className="cn-group-head">
                  <div className="cn-group-title">
                    <h2 id={headingId} tabIndex={-1}>{g.title}</h2>
                    {g.phase != null && <span className={`cn-phase-state st-${st}`}><span className="cn-state-dot" aria-hidden="true" />{stLabel}</span>}
                    <p className="cn-group-sub">
                      {g.sub || cats.join(' · ')}
                      {filtering && ` · ${plural(g.list.length, 'match', 'matches')}`}
                    </p>
                  </div>
                  {p && <div className="cn-group-progress">
                    <span className="cn-progress-text"><b>{p.on} of {p.total}</b> enabled</span>
                    <span className="cn-progress" aria-hidden="true"><span style={{ width: `${p.total ? (p.on / p.total) * 100 : 0}%` }} /></span>
                  </div>}
                  {p && <div className="cn-group-actions">
                    {p.on < p.total && <button className={`btn primary${b === 'on' ? ' busy' : ''}`} disabled={!!b} onClick={() => switchPhase(p, 'on')}>
                      <Icon name="play" size={13} />{b === 'on' ? 'Switching on…' : `Switch on phase ${p.phase}`}</button>}
                    {p.on > 0 && <button className={`btn danger${b === 'off' ? ' busy' : ''}`} disabled={!!b} onClick={() => switchPhase(p, 'off')}>
                      <Icon name="trash" size={13} />{b === 'off' ? 'Switching off…' : `Switch off phase ${p.phase}`}</button>}
                  </div>}
                </header>
                <ul className="cn-grid">
                  {g.list.map(c => <ConnectorCard key={c.id} c={c} test={tests[c.id] || c.lastTest} busy={busy[c.id]} act={act} links={data.links} usage={Array.isArray(c.usedBy) ? normUsage(c.usedBy) : usage[c.id]} />)}
                </ul>
              </section>
            )
          })}
      </>}
    </>
  )
}

/** "Used by N projects" with the list (each project and its environments). */
function Usage({ c, usage }) {
  if (!usage) return null
  if (usage.length === 0) return <p className="cn-usage muted"><Icon name="grid" size={13} />Not used by any project</p>
  return (
    <details className="cn-usage">
      <summary><Icon name="grid" size={13} />Used by {plural(usage.length, 'project')}</summary>
      <ul aria-label={`Projects using ${c.name}`}>
        {usage.map(u => <li key={String(u.id ?? u.name)}>
          {u.id != null ? <Link to={`/projects/${u.id}?tab=services`}>{u.name}</Link> : <b>{u.name}</b>}
          {u.envs.length > 0 && <span className="muted"> · {u.envs.join(', ')}</span>}
        </li>)}
      </ul>
    </details>
  )
}

function ConnectorCard({ c, test, busy: b, act, links, usage }) {
  const [tone, label] = STATUS[statusOf(c)]
  const installed = c.kind === 'MANAGED' && c.status !== 'NOT_INSTALLED' && c.status !== 'NOT_FOR_THIS_CLOUD'
  const version = c.installedVersion || c.pinnedVersion
  const offPin = c.installedVersion && c.installedVersion !== c.pinnedVersion
  const titleId = `cn-${c.id}`
  return (
    <li className={`cn-card tone-${tone}`}>
      <div className="cn-card-head">
        <h3 id={titleId}>{c.name}</h3>
        <span className={`cn-status tone-${tone}`}><span className="cn-state-dot" aria-hidden="true" />{label}</span>
      </div>
      <div className="cn-card-tags">
        <span className={`cn-kind kind-${(c.kind || '').toLowerCase()}`}>{KIND[c.kind] || c.kind}</span>
        {c.category && <span className="cn-cat">{c.category}</span>}
        {installed && c.sync && <span className={`cn-sync${c.sync === 'OutOfSync' ? ' warn' : ''}`}>Argo CD: {SYNC[c.sync] || c.sync}</span>}
      </div>
      {c.message && <p className={`cn-message tone-${tone}`}>{c.message}</p>}
      {c.description && <p className="cn-desc">{c.description}</p>}

      {(version || c.previousVersion || c.requires?.length > 0) && <dl className="cn-facts">
        {version && <div><dt>{c.installedVersion ? 'Running' : 'Pinned'}</dt><dd className="mono">{version}</dd></div>}
        {offPin && <div><dt>Pinned</dt><dd className="mono">{c.pinnedVersion}</dd></div>}
        {c.previousVersion && <div><dt>Previous</dt><dd className="mono">{c.previousVersion}</dd></div>}
        {c.requires?.length > 0 && <div className="cn-needs"><dt>Needs</dt><dd>{c.requires.join(', ')}</dd></div>}
      </dl>}
      {offPin && <p className="cn-offpin"><Icon name="clock" size={13} />Not on the pinned version</p>}

      <div className="cn-meta">
        {c.license && <span className="cn-tag" title="License">{c.license}</span>}
        {c.foundation && <span className="cn-tag" title="Governance">{c.foundation}</span>}
        {c.origin && <span className="cn-tag" title="Project origin">{c.origin}</span>}
        {c.sso && c.sso !== 'none' && <span className="cn-tag" title="Single sign-on">SSO: {c.sso === 'oidc' ? 'Keycloak' : 'gate'}</span>}
        {c.homepage && <a className="cn-home" href={c.homepage} target="_blank" rel="noreferrer">Project site<span className="sr-only"> of {c.name} (opens in a new tab)</span> <Icon name="external" size={11} /></a>}
      </div>

      <Usage c={c} usage={usage} />

      {test && <details className={`cn-test${test.ok ? '' : ' failed'}`} open={!test.ok}>
        <summary>
          <span className={`cn-test-result ${test.ok ? 'tone-ok' : 'tone-bad'}`}><Icon name={test.ok ? 'check' : 'x'} size={13} />
            Last test {test.ok ? 'passed' : 'failed'}</span>
          <span className="muted"> · {fmtTime(test.at)}</span>
        </summary>
        <ul>
          {test.probes.map(p => <li key={p.name}><span className={p.ok ? 'tone-ok' : 'tone-bad'} aria-hidden="true">{p.ok ? '✓' : '✗'}</span>
            <span className="sr-only">{p.ok ? 'Passed: ' : 'Failed: '}</span>{p.name} <span className="muted">({p.detail}, {p.millis} ms)</span></li>)}
        </ul>
      </details>}

      {installed && c.accessHint && <details className="cn-open"><summary>How to open</summary>
        {c.id === 'prometheus' && links?.prometheus
          ? <a href={links.prometheus} target="_blank" rel="noreferrer">Open Grafana <Icon name="external" size={12} /></a>
          : <code>{c.accessHint}</code>}
      </details>}

      <div className="cn-actions">
        {(c.kind === 'CORE' || installed) && c.status !== 'NOT_FOR_THIS_CLOUD' &&
          <button className={`btn small${b === 'test' ? ' busy' : ''}`} disabled={!!b} onClick={() => act(c, 'test')} aria-label={`Test ${c.name}`}>
            <Icon name="play" size={13} />{b === 'test' ? 'Testing…' : 'Test'}</button>}
        {c.kind === 'MANAGED' && c.status === 'NOT_INSTALLED' &&
          <button className={`btn small primary${b === 'enable' ? ' busy' : ''}`} disabled={!!b} aria-label={`Enable ${c.name}`}
                  onClick={() => act(c, 'enable', `Install ${c.name} ${c.pinnedVersion} through Argo CD?`)}>
            <Icon name="plus" size={13} />{b === 'enable' ? 'Installing…' : 'Enable'}</button>}
        {installed && c.previousVersion && c.installedVersion !== c.previousVersion &&
          <button className={`btn small${b === 'rollback' ? ' busy' : ''}`} disabled={!!b} aria-label={`Roll back ${c.name} to ${c.previousVersion}`}
                  onClick={() => act(c, 'rollback', `Roll ${c.name} back to ${c.previousVersion}?`)}>
            <Icon name="undo" size={13} />Roll back</button>}
        {installed && offPin &&
          <button className="btn small" disabled={!!b} aria-label={`Use ${c.pinnedVersion} for ${c.name} (the pinned version)`}
                  onClick={() => act(c, 'enable', `Move ${c.name} back to the pinned ${c.pinnedVersion}?`)}>
            <Icon name="refresh" size={13} />Use {c.pinnedVersion}</button>}
        {installed &&
          <button className={`btn small danger cn-disable${b === 'disable' ? ' busy' : ''}`} disabled={!!b} aria-label={`Disable ${c.name}`}
                  onClick={() => act(c, 'disable', `Disable ${c.name}? This deletes everything it installed (dashboards and stored data included).`
                    + (usage?.length ? ` It is used by ${usage.map(u => `${u.name}${u.envs.length ? ` (${u.envs.join(', ')})` : ''}`).join(', ')}; AIO refuses while projects use it. Remove it from their Services tab first.` : ''))}>
            <Icon name="trash" size={13} />Disable</button>}
        {c.kind === 'PLANNED' && <span className="cn-noaction muted">No actions: planned</span>}
        {c.status === 'NOT_FOR_THIS_CLOUD' && c.kind !== 'PLANNED' && <span className="cn-noaction muted">No actions on this cloud</span>}
        {c.status === 'OFF' && <span className="cn-noaction muted">Switch on phase {c.phase} to use it</span>}
      </div>
    </li>
  )
}
