import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge from './Badge.jsx'
import Dialog from './Dialog.jsx'
import Icon from './Icon.jsx'
import { Empty, Loading, Skeleton } from './ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

/*
 * Project → Services (docs/architecture/PROJECT-SERVICES.md, section 4): connectors this project uses, per environment.
 * AIO writes each one into the env's values file (services: key) in git; Remove takes it out again.
 * API: GET/POST /api/projects/{id}/services, DELETE /api/projects/{id}/services/{connector}?env=&confirm=.
 * The response is normalised below so small shape differences (ids vs objects, per-env lists vs maps) don't break it.
 */

/** Fallback until the API lists the environments (the list itself comes from GET /services, i.e. the environments table). */
export const DEFAULT_ENVS = ['dev', 'staging', 'prod']
const SCOPE = {
  instance: { label: 'Own copy', tone: 'blue', hint: 'The app gets its own instance in its namespace' },
  shared: { label: 'Shared service', tone: 'amber', hint: 'The app uses the connector’s one shared service with copied credentials' },
  config: { label: 'Settings only', tone: 'gray', hint: 'Only environment variables or pod annotations' },
}
const scopeOf = s => SCOPE[String(s || '').toLowerCase()] ? String(s).toLowerCase() : 'config'
const REASON = {
  ENV_NOT_ALLOWED: 'not offered for this environment', OTHER_CLUSTER: 'runs on another cluster',
  NOT_INSTALLED: 'an admin must switch it on first', NOT_HEALTHY: 'the connector is not healthy',
}
const STATUS_WORD = { ATTACHED: 'Attached', FAILED: 'Failed', NOT_IN_GIT: 'Removed from git', UNTRACKED: 'In git, not added here' }
const isNotFound = msg => /^404\b|not found/i.test(msg || '')
const str = v => (v == null ? '' : String(v))

/** env list -> { dev: { env, ok, reason, cluster } } */
function normEnvs(raw) {
  const out = {}
  const list = Array.isArray(raw) ? raw : raw && typeof raw === 'object' ? Object.entries(raw).map(([env, v]) => ({ env, ...(v || {}) })) : []
  for (const item of list) {
    if (typeof item === 'string') { out[item] = { env: item, ok: true }; continue }
    const env = item.env || item.name || item.id
    if (!env) continue
    const ok = [item.available, item.bindable, item.onPlatformCluster, item.platformCluster, item.allowed].find(v => typeof v === 'boolean')
    const cluster = item.cluster || item.clusterName
    out[env] = { env, ok: ok !== false, cluster, reason: item.reason || item.message || (ok === false && cluster ? `not available on ${cluster}` : '') }
  }
  if (!Object.keys(out).length) for (const e of DEFAULT_ENVS) out[e] = { env: e, ok: true }
  return out
}

function normOptions(raw) {
  return (Array.isArray(raw) ? raw : []).filter(o => o?.id).map(o => ({
    id: str(o.id), label: o.label || o.id, def: str(o.default ?? o.defaultValue ?? ''), choices: Array.isArray(o.choices) ? o.choices.map(str) : [],
  }))
}

/** One bindable connector, with what it says per env. */
function normAvailable(item) {
  const b = item.binding || {}
  const id = item.id || item.connector || item.connectorId
  const status = item.status || item.connectorStatus
  const off = ['NOT_INSTALLED', 'OFF', 'PLANNED', 'NOT_FOR_THIS_CLOUD'].includes(status)
  const installed = typeof item.installed === 'boolean' ? item.installed : status ? !off : true
  const healthy = typeof item.usable === 'boolean' ? (item.usable || off) : typeof item.healthy === 'boolean' ? item.healthy : status ? status !== 'DEGRADED' : true
  const envEntry = v => {
    if (typeof v === 'boolean') return { ok: v }
    if (v?.state) return { ok: v.state !== 'unavailable', attached: v.state === 'attached', reason: v.message || REASON[v.reason] || v.reason || '' }
    return { ok: v?.available !== false && v?.ok !== false && v?.allowed !== false, reason: v?.reason || v?.message || '', attached: !!v?.attached }
  }
  const envs = {}
  const rawEnvs = item.envs ?? b.envs
  if (Array.isArray(rawEnvs)) {
    for (const e of rawEnvs) {
      if (typeof e === 'string') envs[e] = { ok: true }
      else if (e && (e.env || e.name)) envs[e.env || e.name] = { ok: e.available !== false && e.ok !== false && e.allowed !== false, reason: e.reason || e.message || '', attached: !!e.attached }
    }
  } else if (rawEnvs && typeof rawEnvs === 'object') {
    for (const [env, v] of Object.entries(rawEnvs)) envs[env] = envEntry(v)
  }
  const allowedListed = Object.keys(envs).length > 0
  return {
    id, name: item.name || id, label: item.label || b.label || item.name || id, summary: item.summary || b.summary || '',
    scope: scopeOf(item.scope || b.scope), deletesData: typeof item.deletesData === 'boolean' ? item.deletesData : scopeOf(item.scope || b.scope) === 'instance',
    options: normOptions(item.options || b.options),
    installed, healthy, status, reason: item.reason || item.message || '',
    env: e => {
      if (!allowedListed) return { ok: true }
      return envs[e] || { ok: false, reason: `not offered for ${e}` }
    },
  }
}

function normAttached(item) {
  return {
    id: item.connector || item.connectorId || item.id, env: item.env || item.environment,
    options: item.options && typeof item.options === 'object' ? item.options : (() => { try { return JSON.parse(item.options || '{}') } catch { return {} } })(),
    label: item.label, name: item.name, scope: item.scope, deletesData: item.deletesData,
    status: item.status || 'ATTACHED', detail: item.detail || '', createdBy: item.createdBy, createdAt: item.createdAt,
  }
}

export function normalizeServices(data) {
  const envs = normEnvs(data?.envs || data?.environments)
  const available = (Array.isArray(data?.available) ? data.available : []).map(normAvailable).filter(c => c.id)
  const attached = (Array.isArray(data?.attached) ? data.attached : []).map(normAttached).filter(a => a.id && a.env)
  return { envs, available, attached }
}

/** Can connector c be added to env e right now? -> { ok, reason } */
function canAdd(c, e, envs, attached) {
  if (attached.some(a => a.id === c.id && a.env === e)) return { ok: false, reason: 'already added', attached: true }
  const info = envs[e]
  if (info && !info.ok) return { ok: false, reason: info.reason || `not available on ${info.cluster || 'this cluster'}` }
  if (!c.installed) return { ok: false, reason: 'an admin must switch it on first' }
  if (!c.healthy) return { ok: false, reason: 'the connector is degraded' }
  const own = c.env(e)
  if (!own.ok) return { ok: false, reason: own.reason || `not offered for ${e}` }
  return { ok: true }
}

export default function ServicesTab({ id, project }) {
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}/services`), 20000, [id])
  const { busy, run } = useAction(refresh)
  const [adding, setAdding] = useState(null)       // { preset?: { id, env } }
  const [removing, setRemoving] = useState(null)   // { c, a }
  const model = useMemo(() => (data ? normalizeServices(data) : null), [data])

  if (!data) {
    if (error && isNotFound(error)) return (
      <div className="card"><Empty icon="box" title="Services are not available yet">
        This AIO version cannot attach connectors to projects. Ask an admin to update the platform.</Empty></div>
    )
    if (error) return (
      <div className="card"><Empty icon="alert" tone="bad" title="Couldn't load the services"
        action={<button type="button" className="btn" onClick={refresh}><Icon name="refresh" size={14} />Try again</button>}>{error}</Empty></div>
    )
    return <Loading label="Loading services" className="svc-loading"><Skeleton bare h={64} /><Skeleton bare h={150} count={2} /></Loading>
  }

  const { envs, available, attached } = model
  const ENVS = Object.keys(envs)
  const byId = new Map(available.map(c => [c.id, c]))
  const groups = [...new Set(attached.map(a => a.id))].map(cid => ({
    c: byId.get(cid) || normAvailable({ id: cid, ...(attached.find(a => a.id === cid) || {}), status: undefined }), rows: attached.filter(a => a.id === cid),
  }))
  const addable = available.filter(c => ENVS.some(e => canAdd(c, e, envs, attached).ok))

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <section className="card svc-head" aria-labelledby="svc-h">
        <div className="svc-head-text">
          <h3 id="svc-h">Services</h3>
          <p className="muted">Databases, caches, queues and tracing for {project.name}, per environment. AIO writes the wiring
            (the service, a copied secret, env vars such as <code>DATABASE_URL</code>) into each environment's values file in git.</p>
        </div>
        <button type="button" className="btn primary" disabled={!!busy || addable.length === 0} onClick={() => setAdding({})}
                title={addable.length === 0 ? 'No connector can be added right now' : undefined}>
          <Icon name="plus" size={14} />Add service</button>
      </section>

      {groups.length === 0
        ? <div className="card"><Empty icon="plug" title="No services yet"
            action={addable.length > 0 && <button type="button" className="btn primary" onClick={() => setAdding({})}><Icon name="plus" size={14} />Add service</button>}>
            {available.length === 0
              ? <>No connector can be used by projects yet. An admin switches connectors on first, on the <Link to="/connectors">Connectors</Link> page; then they appear here.</>
              : addable.length === 0
                ? <>The connectors projects can use are not switched on, or not available for this project's environments. An admin switches them on first.</>
                : <>Add a database, cache or other service. You choose the environments; each one gets its own wiring.</>}
          </Empty></div>
        : <ul className="svc-list">
            {groups.map(({ c, rows }) => <ServiceCard key={c.id} c={c} rows={rows} envs={envs} attached={attached} busy={busy}
              onAdd={env => setAdding({ preset: { id: c.id, env } })} onRemove={a => setRemoving({ c, a })} />)}
          </ul>}

      {adding && <AddDialog available={available} envs={envs} attached={attached} preset={adding.preset} projectId={id}
        onClose={() => setAdding(null)} onDone={() => { setAdding(null); refresh() }} />}
      {removing && <RemoveDialog project={project} c={removing.c} a={removing.a} busy={busy === 'remove'}
        onClose={() => setRemoving(null)}
        onConfirm={async confirm => {
          const r = await run('remove', () => api(`/api/projects/${id}/services/${encodeURIComponent(removing.c.id)}?env=${encodeURIComponent(removing.a.env)}`
            + (confirm ? `&confirm=${encodeURIComponent(confirm)}` : ''), { method: 'DELETE' }), res => `${removing.c.label} removed from ${removing.a.env}${res?.leftBehind?.length
            ? ` · left behind (delete by hand if no longer needed): ${res.leftBehind.map(x => `kubectl -n ${x.split('/')[0]} delete secret ${x.split('/').slice(1).join('/')}`).join('; ')}` : ''}`)
          if (r !== undefined) setRemoving(null)
        }} />}
    </>
  )
}

function ScopeBadge({ scope }) {
  const s = SCOPE[scope] || SCOPE.config
  return <Badge tone={s.tone} label={s.label} title={s.hint} quiet />
}

function ServiceCard({ c, rows, envs, attached, busy, onAdd, onRemove }) {
  const ENVS = Object.keys(envs)
  const headingId = `svc-${c.id}`
  return (
    <li className="card svc-card" aria-labelledby={headingId}>
      <div className="svc-card-head">
        <h4 id={headingId}>{c.label}</h4>
        <ScopeBadge scope={c.scope} />
        {c.name !== c.label && <span className="muted svc-conn">{c.name}</span>}
      </div>
      {c.summary && <p className="svc-summary">{c.summary}</p>}
      <ul className="svc-envs" aria-label={`${c.label} per environment`}>
        {ENVS.map(e => {
          const a = rows.find(r => r.env === e)
          if (a) {
            const failed = a.status === 'FAILED' || a.status === 'NOT_IN_GIT'
            const opts = Object.entries(a.options || {})
            return (
              <li key={e} className={`svc-env ${failed ? 'is-bad' : 'is-on'}`}>
                <span className="svc-env-name">{e}</span>
                <span className="svc-env-state"><Icon name={failed ? 'alert' : 'check'} size={13} stroke={2.25} />{STATUS_WORD[a.status] || a.status}</span>
                {opts.length > 0 && <span className="svc-env-opts">{opts.map(([k, v]) => `${k} ${v}`).join(' · ')}</span>}
                {a.detail && <span className="svc-env-detail">{a.detail}</span>}
                <button type="button" className="btn small danger" disabled={!!busy} onClick={() => onRemove(a)} aria-label={`Remove ${c.label} from ${e}`}>
                  <Icon name="trash" size={12} />Remove</button>
              </li>
            )
          }
          const can = canAdd(c, e, envs, attached)
          return (
            <li key={e} className={`svc-env ${can.ok ? 'is-free' : 'is-off'}`}>
              <span className="svc-env-name">{e}</span>
              <span className="svc-env-state">{can.ok ? 'Available' : 'Not available'}</span>
              {!can.ok && <span className="svc-env-detail">{can.reason}</span>}
              {can.ok && <button type="button" className="btn small" disabled={!!busy} onClick={() => onAdd(e)} aria-label={`Add ${c.label} to ${e}`}>
                <Icon name="plus" size={12} />Add</button>}
            </li>
          )
        })}
      </ul>
    </li>
  )
}

function AddDialog({ available, envs, attached, preset, projectId, onClose, onDone }) {
  const ENVS = Object.keys(envs)
  const first = preset?.id || available.find(c => ENVS.some(e => canAdd(c, e, envs, attached).ok))?.id || ''
  const [pick, setPick] = useState(first)
  const c = available.find(x => x.id === pick)
  const defaultsFor = conn => Object.fromEntries((conn?.options || []).map(o => [o.id, o.def || o.choices[0] || '']))
  const envsFor = (conn, only) => Object.fromEntries(ENVS.map(e => [e, !!conn && canAdd(conn, e, envs, attached).ok && (!only || e === only)]))
  const [chosen, setChosen] = useState(() => envsFor(c, preset?.env))
  const [options, setOptions] = useState(() => defaultsFor(c))
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState([])
  const choose = conn => { setPick(conn.id); setChosen(envsFor(conn)); setOptions(defaultsFor(conn)); setErrors([]) }
  const targets = ENVS.filter(e => chosen[e])

  async function submit(e) {
    e.preventDefault()
    if (!c || targets.length === 0) return
    setBusy(true)
    const failed = []
    for (const env of targets) {
      try {
        await api(`/api/projects/${projectId}/services`, { method: 'POST', body: { connector: c.id, env, options } })
      } catch (err) {
        failed.push(`${env}: ${err.message}`)
      }
    }
    setBusy(false)
    if (failed.length === 0) onDone()
    else { setErrors(failed); if (failed.length < targets.length) setChosen(Object.fromEntries(ENVS.map(x => [x, failed.some(f => f.startsWith(`${x}:`))]))) }
  }

  return (
    <Dialog title="Add a service" busy={busy} onClose={onClose} wide
      sub="AIO commits the wiring to each environment's values file; Argo CD then creates it."
      footer={<>
        <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
        <button type="submit" form="svc-add" className={`btn primary${busy ? ' busy' : ''}`} disabled={busy || !c || targets.length === 0}>
          <Icon name="plus" size={14} />{busy ? 'Adding…' : targets.length ? `Add to ${targets.join(', ')}` : 'Choose an environment'}</button>
      </>}>
      <form id="svc-add" className="form svc-form" onSubmit={submit}>
        <fieldset className="fieldset">
          <legend>Service</legend>
          <div className="svc-picks" role="radiogroup" aria-label="Service">
            {available.map(conn => {
              const any = ENVS.some(e => canAdd(conn, e, envs, attached).ok)
              const why = !conn.installed ? 'An admin must switch it on first' : !conn.healthy ? 'The connector is degraded' : !any ? 'Already added everywhere it can go' : ''
              return (
                <label key={conn.id} className={`svc-pick${pick === conn.id ? ' on' : ''}${any ? '' : ' off'}`}>
                  <input type="radio" name="svc" value={conn.id} checked={pick === conn.id} disabled={!any || busy}
                         onChange={() => choose(conn)} data-autofocus={pick === conn.id ? '' : undefined} />
                  <span className="svc-pick-body">
                    <span className="svc-pick-title"><b>{conn.label}</b><ScopeBadge scope={conn.scope} /></span>
                    {conn.summary && <span className="svc-pick-sum">{conn.summary}</span>}
                    {why && <span className="svc-pick-why"><Icon name="info" size={12} />{why}</span>}
                  </span>
                </label>
              )
            })}
          </div>
        </fieldset>

        {c && <fieldset className="fieldset">
          <legend>Environments</legend>
          <div className="svc-env-checks">
            {ENVS.map(e => {
              const can = canAdd(c, e, envs, attached)
              return (
                <label key={e} className={`check svc-env-check${can.ok ? '' : ' off'}`}>
                  <input type="checkbox" checked={!!chosen[e]} disabled={!can.ok || busy}
                         onChange={ev => setChosen({ ...chosen, [e]: ev.target.checked })} />
                  <span><b>{e}</b>{!can.ok && <span className="svc-why"> · {can.reason}</span>}</span>
                </label>
              )
            })}
          </div>
        </fieldset>}

        {c && c.options.length > 0 && <fieldset className="fieldset">
          <legend>Options</legend>
          <div className="row svc-options">
            {c.options.map(o => (
              <label key={o.id} htmlFor={`svc-opt-${o.id}`}>{o.label}
                {o.choices.length > 0
                  ? <select id={`svc-opt-${o.id}`} value={options[o.id] ?? ''} disabled={busy} onChange={ev => setOptions({ ...options, [o.id]: ev.target.value })}>
                      {o.choices.map(ch => <option key={ch} value={ch}>{ch}</option>)}
                    </select>
                  : <input id={`svc-opt-${o.id}`} value={options[o.id] ?? ''} disabled={busy} spellCheck="false" onChange={ev => setOptions({ ...options, [o.id]: ev.target.value })} />}
              </label>
            ))}
          </div>
        </fieldset>}

        {c?.scope === 'shared' && <p className="hint">This uses the connector's shared service: AIO copies its credentials into the app's namespace as a Secret and never writes them to git.</p>}
        {errors.length > 0 && <div className="error svc-errors" role="alert">
          <b>Not added:</b><ul>{errors.map(x => <li key={x}>{x}</li>)}</ul></div>}
      </form>
    </Dialog>
  )
}

function RemoveDialog({ project, c, a, busy, onClose, onConfirm }) {
  const destroys = typeof a.deletesData === 'boolean' ? a.deletesData : c.deletesData
  const [typed, setTyped] = useState('')
  const ok = !destroys || typed === project.name
  return (
    <Dialog title={`Remove ${c.label} from ${a.env}?`} busy={busy} onClose={onClose}
      footer={<>
        <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
        <button type="button" className={`btn danger${busy ? ' busy' : ''}`} disabled={busy || !ok}
                onClick={() => onConfirm(destroys ? typed : project.name)} data-autofocus={destroys ? undefined : ''}>
          <Icon name="trash" size={14} />{busy ? 'Removing…' : `Remove from ${a.env}`}</button>
      </>}>
      <p>AIO takes the <code>services.{c.id}</code> block out of <code>values-{a.env}.yaml</code>
        {c.scope === 'shared' ? ' and deletes the copied credentials Secret' : ''}. The app loses its env vars on the next sync.</p>
      {destroys && <>
        <p className="svc-warn" role="note"><Icon name="alert" size={15} />
          <span><b>Its data is deleted.</b> This is the app's own {c.label} in {a.env}: Argo CD deletes it, with its disks, when it prunes.</span></p>
        <label className="svc-confirm"><span>Type <code>{project.name}</code> to confirm</span>
          <input value={typed} onChange={e => setTyped(e.target.value)} autoComplete="off" spellCheck="false" data-autofocus="" disabled={busy} />
        </label>
      </>}
    </Dialog>
  )
}
