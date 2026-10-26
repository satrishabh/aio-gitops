import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Skeleton, Tabs, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'
import { useToast } from '../components/Toast.jsx'

const ENVS = ['dev', 'staging', 'prod']
// Argo resource kinds in the order a deploy flows through them, shown like CI pipeline stages
const STAGES = ['Service', 'Deployment', 'Rollout', 'ReplicaSet', 'Pod', 'Ingress']
const KIND_ICON = { Service: 'zap', Deployment: 'rocket', Rollout: 'layers', ReplicaSet: 'layers', Pod: 'box', Ingress: 'external' }

/** Argo health -> the CI node states the pipeline CSS already knows. */
const nodeState = h => h === 'Healthy' ? 'SUCCEEDED' : h === 'Degraded' || h === 'Missing' ? 'FAILED'
  : h === 'Progressing' ? 'RUNNING' : 'WAITING'

/** Worst health of a group of resources (one stage). */
function worst(nodes) {
  const hs = nodes.map(n => n.health).filter(Boolean)
  for (const h of ['Degraded', 'Missing', 'Progressing', 'Suspended']) if (hs.includes(h)) return h
  return hs.length ? 'Healthy' : null
}

function colorLine(line, i) {
  const l = line.toLowerCase()
  const cls = /\b(error|failed|fatal|exception|refused)\b/.test(l) ? 'ln-err'
    : /\b(warn|warning)\b/.test(l) ? 'ln-warn'
      : /\b(started|ready|listening|success)\b/.test(l) ? 'ln-ok' : ''
  return <span key={i} className={cls}>{line}{'\n'}</span>
}

export default function CdDetail({ me }) {
  const { id, env } = useParams()
  const [tab, setTab] = useState('resources')
  const [pod, setPod] = useState(null)
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}/environments/${env}/cd`), 5000, [id, env])
  const { busy, run } = useAction(refresh)

  useEffect(() => { setPod(null) }, [id, env])

  const nodes = useMemo(() => data?.nodes || [], [data])
  const pods = nodes.filter(n => n.kind === 'Pod')
  // Default to the first unhealthy pod (that's where the answer to "why 503?" usually is)
  useEffect(() => {
    if (pod && pods.some(p => p.name === pod)) return
    const pick = pods.find(p => p.health && p.health !== 'Healthy') || pods[0]
    setPod(pick ? pick.name : null)
  }, [pods, pod])

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={160} /><Skeleton h={90} /><Skeleton h={400} /></>
  const { project: p, app, operation: op, syncPolicy, history = [], conditions = [], resources = [] } = data
  const base = `/api/projects/${id}/environments/${env}/cd`
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })
  const locked = env === 'prod' && !me?.approver
  const stages = STAGES.map(k => ({ kind: k, nodes: nodes.filter(n => n.kind === k) })).filter(s => s.nodes.length)

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />

      <div className="card" style={{ padding: '22px 24px' }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap' }}>
          <Avatar name={p.name} size={46} />
          <div style={{ flex: 1, minWidth: 220 }}>
            <h1 style={{ margin: 0, fontSize: '1.4rem', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <Link to={`/projects/${p.id}`} style={{ color: 'var(--text)' }}>{p.name}</Link>
              <span className="muted">· {env}</span>
              {app && <><Badge value={app.syncStatus} /><Badge value={app.healthStatus} /></>}
              {op?.phase === 'Running' && <span className="live-dot">syncing</span>}
            </h1>
            <div className="muted" style={{ marginTop: 4 }}>
              Argo CD app <code>{data.appName}</code> · cluster <Link to="/clusters"><code>{data.currentCluster || data.cluster}</code></Link> · namespace <code>{data.namespace}</code>
              {app?.reconciledAt && <> · checked {ago(app.reconciledAt)}</>}
            </div>
          </div>
          <div className="row inline" style={{ margin: 0 }} role="group" aria-label="Environment">
            {ENVS.map(e => <Link key={e} to={`/projects/${id}/cd/${e}`} className={`btn small${e === env ? ' primary' : ' ghost'}`}
                                 aria-current={e === env ? 'page' : undefined}>{e}</Link>)}
          </div>
          {data.exists && <div style={{ display: 'flex', gap: 8 }}>
            <button {...btn('sync', 'btn primary')} disabled={!!busy || locked} title={locked ? 'Only approvers can sync prod' : 'Apply the GitOps state now'}
                    onClick={() => run('sync', () => api(`${base}/sync`, { method: 'POST' }), 'Sync started')}>
              <Icon name="refresh" size={14} />Sync</button>
            <button {...btn('refresh')} title="Re-read Git and the cluster"
                    onClick={() => run('refresh', () => api(`${base}/refresh`, { method: 'POST' }), 'Refreshed')}>
              <Icon name="search" size={14} />Refresh</button>
          </div>}
        </div>

        {data.exists && data.currentCluster && data.currentCluster !== data.cluster && (
          <div className="banner" style={{ marginTop: 14, marginBottom: 0 }}><Icon name="layers" size={15} />
            {env} is mapped to <b>&nbsp;{data.cluster}&nbsp;</b> but still runs on <b>&nbsp;{data.currentCluster}</b>.
            {env === 'prod' ? ' An approver can move it with Repair environments in project settings.' : ' It moves on the next Repair or platform restart.'}
          </div>
        )}

        {!data.exists ? <p className="muted" style={{ marginTop: 16 }}>No Argo CD application for {env} yet — use <b>Repair environments</b> in project settings.</p> : <>
          <div className="facts" style={{ marginTop: 18 }}>
            <div><span>Image</span><code>{(app.images || []).map(i => i.split('/').pop()).join(', ') || '—'}</code></div>
            <div><span>Revision</span><code>{(app.revision || '').slice(0, 10) || '—'}</code></div>
            <div><span>Tracks</span><code>{app.targetRevision}</code></div>
            <div><span>Auto-sync</span>{syncPolicy.automated ? `on${syncPolicy.prune ? ' · prune' : ''}${syncPolicy.selfHeal ? ' · self-heal' : ''}` : 'off (manual)'}</div>
            <div><span>Last sync</span>{op ? <>{op.phase} · {duration(op.startedAt, op.finishedAt)}</> : '—'}</div>
            <div><span>Synced by</span>{op?.initiatedBy || '—'}</div>
            <div><span>Started</span>{fmtTime(op?.startedAt)}</div>
            <div><span>Pods</span>{pods.length ? `${pods.filter(x => x.health === 'Healthy').length}/${pods.length} healthy` : 'none'}</div>
          </div>
          {[app.healthMessage, ...conditions.map(c => `${c.type}: ${c.message}`), op?.phase === 'Failed' || op?.phase === 'Error' ? op.message : null]
            .filter(Boolean).map((m, i) => (
              <div key={i} className="banner" style={{ background: 'var(--red-bg)', color: 'var(--red)', marginTop: 14, marginBottom: 0 }}>
                <Icon name="x" size={15} />{m}</div>
            ))}
        </>}
      </div>

      {data.exists && <>
        <div className="card">
          <div className="card-head"><h3>Deployment</h3>
            <span className="muted">{stages.filter(s => worst(s.nodes) === 'Healthy').length}/{stages.length} healthy</span></div>
          {stages.length === 0 ? <p className="muted">Nothing deployed yet — Argo CD has no live resources for this app (replicas may be 0).</p> : (
            <div className="pipeline">
              {stages.map(s => {
                const h = worst(s.nodes), st = nodeState(h)
                return (
                  <div key={s.kind} className={`stage ${st === 'SUCCEEDED' ? 'done' : ''}`}>
                    <button className={`node ${st} ${s.kind === 'Pod' && tab === 'logs' ? 'active' : ''}`}
                            onClick={() => { if (s.kind === 'Pod') setTab('logs'); else setTab('resources') }}>
                      <span className="ring"><Icon name={st === 'SUCCEEDED' ? 'check' : st === 'FAILED' ? 'x' : KIND_ICON[s.kind]} size={14} stroke={2.5} /></span>
                      <span className="label"><b>{s.kind}{s.nodes.length > 1 ? ` ×${s.nodes.length}` : ''}</b><span>{h || 'n/a'}</span></span>
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <Tabs label="CD sections" value={tab} onChange={setTab} tabs={[
          { id: 'resources', label: 'Resources', icon: 'layers', count: nodes.length },
          { id: 'logs', label: 'Pod logs', icon: 'terminal', count: pods.length },
          { id: 'sync', label: 'Last sync', icon: 'refresh', count: op?.resources?.length },
          { id: 'history', label: 'History', icon: 'clock', count: history.length },
          { id: 'config', label: 'Config', icon: 'settings' },
        ]} />

        {tab === 'resources' && (
          <div className="card flush">
            <table>
              <thead><tr><th>Kind</th><th>Name</th><th>Sync</th><th>Health</th><th>Details</th></tr></thead>
              <tbody>
                {nodes.map(n => {
                  const r = resources.find(x => x.kind === n.kind && x.name === n.name)
                  return (
                    <tr key={n.uid || `${n.kind}/${n.name}`}>
                      <td><Icon name={KIND_ICON[n.kind] || 'box'} size={13} /> {n.kind}</td>
                      <td><code>{n.name}</code>{n.createdAt && <div className="muted" style={{ fontSize: '0.75rem' }}>created {ago(n.createdAt)}</div>}</td>
                      <td>{r ? <Badge value={r.status} /> : <span className="muted">—</span>}</td>
                      <td><Badge value={n.health} /></td>
                      <td style={{ fontSize: '0.8rem' }}>
                        {n.healthMessage && <div style={{ color: n.health === 'Healthy' ? undefined : 'var(--red)' }}>{n.healthMessage}</div>}
                        {(n.info || []).map(i => <span key={i.name} className="muted">{i.name}: {i.value} </span>)}
                        {n.kind === 'Pod' && <button className="btn small ghost" onClick={() => { setPod(n.name); setTab('logs') }}>
                          <Icon name="terminal" size={12} />Logs</button>}
                      </td>
                    </tr>
                  )
                })}
                {nodes.length === 0 && <tr><td colSpan="5" className="empty">No live resources.</td></tr>}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'logs' && <PodLogs base={base} pods={pods} pod={pod} setPod={setPod} name={`${p.name}-${env}`} />}

        {tab === 'sync' && (
          <div className="card flush">
            {op && <div style={{ padding: '14px 18px' }} className="muted">
              <Badge value={op.phase === 'Succeeded' ? 'SUCCEEDED' : op.phase === 'Running' ? 'RUNNING' : 'FAILED'} label={op.phase} /> {op.message}
            </div>}
            <table>
              <thead><tr><th>Kind</th><th>Name</th><th>Result</th><th>Phase</th><th>Message</th></tr></thead>
              <tbody>
                {(op?.resources || []).map((r, i) => (
                  <tr key={i}>
                    <td>{r.kind}</td><td><code>{r.name}</code></td>
                    <td><Badge value={r.status === 'Synced' ? 'Synced' : r.status === 'SyncFailed' ? 'FAILED' : r.status} label={r.status} /></td>
                    <td className="muted">{r.hookPhase || r.syncPhase || '—'}</td>
                    <td style={{ fontSize: '0.8rem' }}>{r.message}</td>
                  </tr>
                ))}
                {!op?.resources?.length && <tr><td colSpan="5" className="empty">No sync operation recorded yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'history' && (
          <div className="card flush">
            <table>
              <thead><tr><th>#</th><th>GitOps revision</th><th>Deployed</th><th>Took</th></tr></thead>
              <tbody>
                {history.map(h => (
                  <tr key={h.id}>
                    <td>{h.id}</td>
                    <td><code>{(h.revision || '').slice(0, 10)}</code></td>
                    <td>{fmtTime(h.deployedAt)} <span className="muted">({ago(h.deployedAt)})</span></td>
                    <td className="mono">{duration(h.deployStartedAt, h.deployedAt)}</td>
                  </tr>
                ))}
                {history.length === 0 && <tr><td colSpan="4" className="empty">No deployments yet.</td></tr>}
              </tbody>
            </table>
          </div>
        )}

        {tab === 'config' && <Config base={base} env={env} policy={syncPolicy} locked={locked} run={run} btn={btn} app={app} />}
      </>}
    </>
  )
}

function PodLogs({ base, pods, pod, setPod, name }) {
  const toast = useToast()
  const [logs, setLogs] = useState('')
  const [previous, setPrevious] = useState(false)
  const [follow, setFollow] = useState(true)
  const ref = useRef(null)

  useEffect(() => {
    if (!pod) { setLogs(''); return }
    let stop = false
    const load = () => api(`${base}/pods/${encodeURIComponent(pod)}/logs?previous=${previous}`, { text: true })
      .then(t => { if (!stop) setLogs(t) }).catch(e => { if (!stop) setLogs(`(logs unavailable: ${e.message})`) })
    load()
    const t = previous ? null : setInterval(load, 3000)
    return () => { stop = true; if (t) clearInterval(t) }
  }, [base, pod, previous])

  useEffect(() => { if (follow && ref.current) ref.current.scrollTop = ref.current.scrollHeight }, [logs, follow])

  function download() {
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([logs], { type: 'text/plain' }))
    a.download = `${name}-${pod}${previous ? '-previous' : ''}.log`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return (
    <div className="run-grid">
      <div className="card">
        <div className="card-head"><h3>Pods</h3></div>
        {pods.map(x => (
          <button key={x.name} className={`step ${pod === x.name ? 'active' : ''}`} onClick={() => setPod(x.name)}>
            <Badge value={x.health} label={x.name.split('-').slice(-2).join('-')} />
            <span className="dur">{(x.info || []).find(i => i.name === 'Restart Count')?.value ?? ''}</span>
          </button>
        ))}
        {pods.length === 0 && <p className="muted">No pods running.</p>}
        <label className="check" style={{ marginTop: 12 }}>
          <input type="checkbox" checked={previous} onChange={e => setPrevious(e.target.checked)} />
          Previous container (after a crash / restart)
        </label>
      </div>
      <div className="terminal">
        <div className="terminal-bar">
          <i style={{ background: '#ff5f57' }} /><i style={{ background: '#febc2e' }} /><i style={{ background: '#28c840' }} />
          <span className="title">{pod ? `${pod}${previous ? ' (previous)' : ''}` : 'logs'}</span>
          <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
            <input type="checkbox" checked={follow} onChange={e => setFollow(e.target.checked)} style={{ width: 14, height: 14, margin: 0, accentColor: 'var(--accent)' }} />follow
          </label>
          <button className="icon-btn" title="Copy" aria-label="Copy logs" disabled={!logs} onClick={() => navigator.clipboard.writeText(logs).then(() => toast('ok', 'Logs copied'))}><Icon name="list" size={14} /></button>
          <button className="icon-btn" title="Download" aria-label="Download logs" disabled={!logs} onClick={download}><Icon name="arrow" size={14} style={{ transform: 'rotate(90deg)' }} /></button>
        </div>
        <pre ref={ref}>{pod ? (logs ? logs.split('\n').map(colorLine) : 'Waiting for output…') : 'Select a pod to view its logs.'}</pre>
      </div>
    </div>
  )
}

/** Editable CD config: Argo CD sync policy + the GitOps values file for this environment. */
function Config({ base, env, policy, locked, run, btn, app }) {
  const [pol, setPol] = useState(policy)
  const [values, setValues] = useState(null)   // { path, yaml, sha } as last loaded from Git
  const [yaml, setYaml] = useState('')
  const [message, setMessage] = useState('')
  const [loadError, setLoadError] = useState('')

  const load = () => api(`${base}/values`).then(v => { setValues(v); setYaml(v.yaml); setLoadError('') }).catch(e => setLoadError(e.message))
  useEffect(() => { load() }, [base])   // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setPol(policy) }, [policy.automated, policy.prune, policy.selfHeal])   // eslint-disable-line react-hooks/exhaustive-deps

  const polDirty = pol.automated !== policy.automated || (pol.automated && (pol.prune !== policy.prune || pol.selfHeal !== policy.selfHeal))
  const dirty = values && yaml !== values.yaml

  async function saveValues(e) {
    e.preventDefault()
    const v = await run('values', () => api(`${base}/values`, { method: 'PUT', body: { yaml, sha: values.sha, message } }),
      env === 'prod' && !policy.automated ? 'Committed — press Sync to apply it to prod' : 'Committed — Argo CD will apply it on the next sync')
    if (v) { setValues(v); setYaml(v.yaml); setMessage('') }
  }

  return (
    <>
      {locked && <div className="banner"><Icon name="lock" size={15} />Prod is gated — only approvers can change its CD config.</div>}

      <div className="card form" style={{ maxWidth: 'none' }}>
        <div className="card-head"><h3>Sync policy</h3>{polDirty && <span className="badge amber quiet" role="status">Unsaved changes</span>}</div>
        <fieldset className="fieldset" disabled={locked}>
          <legend>Argo CD</legend>
          <label className="check"><input type="checkbox" role="switch" checked={pol.automated}
                                          onChange={e => setPol({ ...pol, automated: e.target.checked })} />
            Auto-sync — apply every GitOps commit automatically</label>
          <label className="check"><input type="checkbox" role="switch" checked={pol.prune} disabled={!pol.automated}
                                          onChange={e => setPol({ ...pol, prune: e.target.checked })} />
            Prune — delete resources that were removed from Git</label>
          <label className="check"><input type="checkbox" role="switch" checked={pol.selfHeal} disabled={!pol.automated}
                                          onChange={e => setPol({ ...pol, selfHeal: e.target.checked })} />
            Self-heal — undo manual changes made in the cluster</label>
          {env === 'prod' && pol.automated && <p className="hint" style={{ color: 'var(--amber)' }}>
            Auto-sync on prod applies commits without waiting for a manual sync. Promotions still need approval.</p>}
          <p className="hint">Source: <code>{app.repoUrl}</code> · chart <code>{app.path}</code> · tracks <code>{app.targetRevision}</code></p>
        </fieldset>
        <div style={{ display: 'flex', gap: 10 }}>
          <button {...btn('policy', 'btn primary')} disabled={!!btn('policy').disabled || !polDirty || locked}
                  onClick={() => run('policy', () => api(`${base}/sync-policy`, { method: 'PUT', body: pol }), 'Sync policy updated')}>
            <Icon name="check" size={14} />Save policy</button>
          <button className="btn ghost" disabled={!polDirty} onClick={() => setPol(policy)}>Discard</button>
        </div>
      </div>

      <form className="card form" style={{ maxWidth: 'none' }} onSubmit={saveValues} aria-labelledby="values-h">
        <div className="card-head"><h3 id="values-h">Helm values</h3>
          {values && <code className="muted">{values.path}</code>}
          {dirty && <span className="badge amber quiet" role="status">Unsaved changes</span>}</div>
        {loadError ? <p className="error">{loadError}</p> : !values ? <Skeleton h={260} /> : <>
          <p className="hint" style={{ marginTop: 0 }}>
            The file Argo CD deploys for <b>{env}</b>. Saving commits it to the GitOps repo. <code>image.tag</code> is also updated by CI,
            promotions and rollbacks, and env vars / CPU / memory by project settings — edit those there so all environments stay in step.
            Values are committed in plain text — no secrets.
          </p>
          <textarea className="mono" aria-label={`values-${env}.yaml`} rows={Math.min(30, Math.max(12, yaml.split('\n').length + 1))}
                    value={yaml} onChange={e => setYaml(e.target.value)} spellCheck="false" readOnly={locked}
                    onKeyDown={e => {
                      if (e.key !== 'Tab') return
                      e.preventDefault()   // indent with two spaces instead of leaving the field
                      const t = e.currentTarget, s = t.selectionStart
                      setYaml(yaml.slice(0, s) + '  ' + yaml.slice(t.selectionEnd))
                      requestAnimationFrame(() => { t.selectionStart = t.selectionEnd = s + 2 })
                    }}
                    style={{ width: '100%', fontSize: '0.85rem', lineHeight: 1.5, tabSize: 2 }} />
          <label>Commit message <span className="muted">(optional)</span>
            <input value={message} onChange={e => setMessage(e.target.value)} placeholder="e.g. raise replicas to 2" maxLength={200} disabled={locked} />
          </label>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <button type="submit" {...btn('values', 'btn primary')} disabled={!!btn('values').disabled || !dirty || locked}>
              <Icon name="git" size={14} />Commit values</button>
            <button type="button" className="btn ghost" disabled={!dirty} onClick={() => setYaml(values.yaml)}>Discard</button>
            <button type="button" className="btn ghost" onClick={load} title="Load the latest version from Git"><Icon name="refresh" size={14} />Reload</button>
          </div>
        </>}
      </form>
    </>
  )
}
