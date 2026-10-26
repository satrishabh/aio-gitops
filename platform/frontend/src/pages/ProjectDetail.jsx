import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, Skeleton, Tabs, ago, envTone } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'
import { PRESETS, SEVERITIES } from '../projectOptions.js'
import RuntimeFields, { RUNTIME_DEFAULTS, cleanEnv } from '../components/RuntimeFields.jsx'

const ENVS = ['dev', 'staging', 'prod']
const NEXT = { dev: 'staging', staging: 'prod' }
/** Something real has been deployed to this env (not the 0.0.0 placeholder). */
const isDeployed = s => !!s.desiredTag && s.desiredTag !== '0.0.0'

export default function ProjectDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const [ref, setRef] = useState('')
  const [tab, setTab] = useState('runs')
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}`), 8000, [id])
  const { busy, run } = useAction(refresh)

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={130} /><Skeleton h={220} /><Skeleton h={300} /></>
  const { project: p, environments, runs, deployments, promotions, failedSteps = {}, namespaces = {}, rollout = {} } = data
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })
  const envOf = env => environments.find(e => e.environment === env) || {}

  async function startRun() {
    const r = await run('run', () => api(`/api/projects/${id}/runs`, { method: 'POST', body: { ref } }), r => `CI run #${r.id} started`)
    if (r) nav(`/runs/${r.id}`)
  }
  const promote = env => run(`promote-${env}`,
    () => api(`/api/projects/${id}/promote`, { method: 'POST', body: { from: env, to: NEXT[env] } }),
    NEXT[env] === 'prod' ? 'Prod promotion requested — approve it on the Approvals page' : `Promoted to ${NEXT[env]}`)
  const scale = (env, n) => run(`scale-${env}`,
    () => api(`/api/projects/${id}/environments/${env}/scale`, { method: 'POST', body: { replicas: n } }),
    n === 0 ? `${env} scaled down — the app is stopped` : `${env} scaling to ${n} replica${n > 1 ? 's' : ''}`)
  const deployedEnvs = ENVS.filter(e => isDeployed(envOf(e)))
  const anyRunning = deployedEnvs.some(e => (envOf(e).replicas || 0) > 0)
  function scaleAll(n) {
    if (n === 0 && !window.confirm(`Scale down ${p.name}? Every deployed environment stops (0 pods). New builds keep updating the image, and you can start it again any time.`)) return
    run('scale-all', () => api(`/api/projects/${id}/scale`, { method: 'POST', body: { replicas: n } }),
      r => `${n === 0 ? 'Stopped' : 'Started'} ${r.applied.join(', ') || 'nothing'}${Object.keys(r.skipped).length ? ` · skipped ${Object.entries(r.skipped).map(([k, v]) => `${k} (${v})`).join(', ')}` : ''}`)
  }

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />

      <div className="card" style={{ padding: '22px 24px' }}>
        <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
          <Avatar name={p.name} size={54} />
          <div style={{ flex: 1, minWidth: 240 }}>
            <h1 style={{ margin: 0, fontSize: '1.55rem' }}>{p.name}</h1>
            <div className="muted" style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginTop: 4 }}>
              <a href={p.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer"><Icon name="github" size={13} /> {p.repoOwner}/{p.repoName}</a>
              <span><Icon name="git" size={13} /> {p.defaultBranch}</span>
              <span><Icon name="box" size={13} /> <code>{p.imageRepo}</code></span>
              <span><Icon name="shield" size={13} /> gate <code>{p.scanSeverity}</code></span>
              <span><Icon name="zap" size={13} /> webhook {p.webhookId ? 'on' : 'off'}</span>
            </div>
          </div>
          <div className="row inline" style={{ margin: 0 }}>
            <input aria-label="Branch or commit to build" placeholder={`branch or SHA (default ${p.defaultBranch})`} value={ref} onChange={e => setRef(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && startRun()} style={{ width: 240 }} />
            <button {...btn('run', 'btn primary')} onClick={startRun}><Icon name="play" size={14} />Run CI</button>
            <button className="btn" onClick={() => setTab('settings')} aria-label={`Edit ${p.name} settings`}><Icon name="settings" size={14} />Edit</button>
            {deployedEnvs.length > 0 && (anyRunning
              ? <button {...btn('scale-all', 'btn')} onClick={() => scaleAll(0)} title="Stop every environment (0 pods)"><Icon name="stop" size={13} />Stop app</button>
              : <button {...btn('scale-all', 'btn')} onClick={() => scaleAll(1)} title="Start every deployed environment"><Icon name="play" size={13} />Start app</button>)}
            <button {...btn('refresh', 'btn ghost')} title="Refresh" aria-label="Refresh" onClick={() => run('refresh', refresh)}><Icon name="refresh" size={15} /></button>
          </div>
        </div>
      </div>

      <div className="flow">
        {ENVS.map((env, i) => {
          const s = envOf(env)
          const tone = envTone(s)
          const deployed = isDeployed(s)
          const parked = !deployed
          const stopped = deployed && s.replicas === 0
          const n = s.replicas || 0
          const rolling = !stopped && s.desiredTag && s.imageTag && s.desiredTag !== s.imageTag
          return [
            <div key={env} className={`card env-card ${tone}`}>
              <div className="env-title">
                <span className={`dot ${tone}`} /><h3>{env}</h3>
                {stopped && <span className="badge gray">Scaled down</span>}
                {env === 'prod' && <span className="badge amber"><Icon name="lock" size={11} />gated</span>}
              </div>
              <div className="muted" style={{ fontSize: '0.75rem' }}>{parked ? 'NOT DEPLOYED' : stopped ? 'STOPPED · IMAGE' : 'RUNNING IMAGE'}</div>
              <div className="big-tag">{parked ? <span className="muted">—</span> : (s.desiredTag || s.imageTag || '—')}</div>
              {rolling && <div style={{ fontSize: '0.8rem' }}><Badge value="Progressing" label="Rolling out" /> <span className="muted">live {s.imageTag}</span></div>}
              {env === 'prod' && rollout.enabled && <BlueGreen ro={rollout} appUrl={s.appUrl} p={p} btn={btn} run={run} id={id} />}
              <div className="meta">
                <span>Sync</span><span><Badge value={s.syncStatus} /></span>
                <span>Health</span><span><Badge value={s.healthStatus} /></span>
                <span>Namespace</span><span><code>{namespaces[env] || env}</code></span>
                <span>Replicas</span>
                <span>{deployed
                  ? <span className="stepper">
                      <button aria-label={`Fewer ${env} replicas`} disabled={!!busy || n === 0} onClick={() => scale(env, n - 1)}>−</button>
                      <b aria-live="polite" aria-label={`${n} replicas`}>{n}</b>
                      <button aria-label={`More ${env} replicas`} disabled={!!busy || n >= 5} onClick={() => scale(env, n + 1)}>+</button>
                    </span>
                  : (s.replicas ?? '—')}</span>
                <span>Updated</span><span>{ago(s.updatedAt)}</span>
              </div>
              <div className="actions">
                {s.appUrl && deployed && !stopped && <a className="btn small" href={s.appUrl} target="_blank" rel="noreferrer"><Icon name="external" size={13} />Open app</a>}
                {deployed && (stopped
                  ? <button {...btn(`scale-${env}`, 'btn small primary')} onClick={() => scale(env, 1)}><Icon name="play" size={12} />Start</button>
                  : <button {...btn(`scale-${env}`, 'btn small')} onClick={() => scale(env, 0)} title="Scale down to 0 pods"><Icon name="stop" size={12} />Stop</button>)}
                {parked && <span className="muted" style={{ fontSize: '0.8rem' }}>{env === 'dev' ? 'Run CI to deploy here' : `Promote from ${ENVS[i - 1]} to deploy`}</span>}
              </div>
            </div>,
            NEXT[env] && (
              <div key={`${env}-c`} className="connector">
                <button className="arrow" title={`Promote ${env} → ${NEXT[env]}`} aria-label={`Promote ${env} to ${NEXT[env]}`} disabled={!!busy || parked}
                        onClick={() => promote(env)}>
                  {busy === `promote-${env}` ? <span className="btn busy" style={{ padding: 0, border: 0, background: 'none' }} /> : <Icon name="arrow" size={16} />}
                </button>
                {NEXT[env] === 'prod' && <span className="lock">approval</span>}
              </div>
            ),
          ]
        })}
      </div>

      <Tabs label="Project sections" value={tab} onChange={setTab} tabs={[
        { id: 'runs', label: 'CI runs', icon: 'play', count: runs.length },
        { id: 'deploys', label: 'Deployments', icon: 'rocket', count: deployments.length },
        { id: 'promos', label: 'Promotions', icon: 'shield', count: promotions.length },
        { id: 'settings', label: 'Settings', icon: 'settings' },
      ]} />

      {tab === 'runs' && (
        <div className="card flush">
          <table>
            <thead><tr><th>Run</th><th>Status</th><th>Commit</th><th>Image</th><th>Vulnerabilities</th><th>Trigger</th><th>Duration</th><th></th></tr></thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.id} style={{ cursor: 'pointer' }} onClick={e => { if (!e.target.closest('button,a')) nav(`/runs/${r.id}`) }}>
                  <td><Link to={`/runs/${r.id}`}><b>#{r.id}</b></Link><div className="muted" style={{ fontSize: '0.75rem' }}>{ago(r.createdAt)}</div></td>
                  <td>
                    <Badge value={r.status} />{r.gateOverridden && <> <Badge value="RISK ACCEPTED" /></>}
                    {failedSteps[r.id] && <div className="failed-step"><Icon name="x" size={11} /> {failedSteps[r.id]}</div>}
                    {r.status === 'FAILED' && !failedSteps[r.id] && r.message && <div className="failed-step">{r.message.slice(0, 80)}</div>}
                  </td>
                  <td><code>{(r.commitSha || r.gitRef).slice(0, 10)}</code></td>
                  <td><code>{r.imageUrl ? r.imageUrl.split(':').pop().slice(0, 12) : '—'}</code></td>
                  <td><Vulns run={r} /></td>
                  <td><span className="muted">{r.trigger.toLowerCase()} · {r.triggeredBy}</span></td>
                  <td className="mono">{duration(r.startedAt, r.finishedAt)}</td>
                  <td style={{ textAlign: 'right' }}>
                    {['FAILED', 'SUCCEEDED', 'CANCELLED'].includes(r.status) && (
                      <button {...btn(`rerun-${r.id}`, 'btn small')} onClick={async () => {
                        const n = await run(`rerun-${r.id}`, () => api(`/api/runs/${r.id}/rerun`, { method: 'POST', body: {} }), n => `Re-run #${n.id} started`)
                        if (n) nav(`/runs/${n.id}`)
                      }}><Icon name="refresh" size={12} />Re-run</button>
                    )}
                  </td>
                </tr>
              ))}
              {runs.length === 0 && <tr><td colSpan="8" className="empty">No runs yet — click <b>Run CI</b>.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'deploys' && (
        <div className="card flush">
          <table>
            <thead><tr><th>Environment</th><th>Image tag</th><th>GitOps revision</th><th>Deployed</th><th></th></tr></thead>
            <tbody>
              {deployments.map(d => (
                <tr key={d.id}>
                  <td><span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}><span className="dot green" />{d.environment}</span>
                    <span className="muted"> · #{d.argoHistoryId}</span></td>
                  <td><code>{d.imageTag || '?'}</code></td>
                  <td><code>{(d.revision || '').slice(0, 10)}</code></td>
                  <td>{fmtTime(d.deployedAt)} <span className="muted">({ago(d.deployedAt)})</span></td>
                  <td style={{ textAlign: 'right' }}>
                    {d.imageTag && d.imageTag !== '0.0.0' && (
                      <button {...btn(`rb-${d.id}`, 'btn small')} onClick={() => {
                        if (!confirm(`Roll back ${d.environment} to ${d.imageTag}?`)) return
                        run(`rb-${d.id}`, () => api(`/api/projects/${id}/environments/${d.environment}/rollback`,
                          { method: 'POST', body: { deploymentId: d.id } }),
                          d.environment === 'prod' ? 'Prod rollback requested — waiting for approval' : 'Rollback committed')
                      }}><Icon name="undo" size={12} />Roll back</button>
                    )}
                  </td>
                </tr>
              ))}
              {deployments.length === 0 && <tr><td colSpan="5" className="empty">No deployments recorded yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'promos' && (
        <div className="card flush">
          <table>
            <thead><tr><th>#</th><th>Kind</th><th>Target</th><th>Tag</th><th>Status</th><th>Requested</th><th>Decided</th></tr></thead>
            <tbody>
              {promotions.map(pr => (
                <tr key={pr.id}>
                  <td>{pr.id}</td>
                  <td><Icon name={pr.kind === 'ROLLBACK' ? 'undo' : 'rocket'} size={13} /> {pr.kind.toLowerCase()}</td>
                  <td>{pr.fromEnv ? <>{pr.fromEnv} <Icon name="arrow" size={11} /> </> : ''}<b>{pr.toEnv}</b></td>
                  <td><code>{pr.imageTag}</code></td>
                  <td><Badge value={pr.status} /> {pr.message && <div className="muted" style={{ fontSize: '0.75rem' }}>{pr.message}</div>}</td>
                  <td>{pr.requestedBy} <div className="muted" style={{ fontSize: '0.75rem' }}>{ago(pr.requestedAt)}</div></td>
                  <td>{pr.decidedBy || '—'} <div className="muted" style={{ fontSize: '0.75rem' }}>{pr.decidedAt ? ago(pr.decidedAt) : ''}</div></td>
                </tr>
              ))}
              {promotions.length === 0 && <tr><td colSpan="7" className="empty">No promotions yet. Use the arrows above to promote.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'settings' && <>
        <Settings p={p} run={run} btn={btn} />
        <DangerZone p={p} btn={btn} run={run} onDeleted={() => nav('/projects')} />
      </>}
    </>
  )
}

/** Prod blue-green (Argo Rollouts): live vs preview version, preview URL, promote / abort. */
function BlueGreen({ ro, appUrl, p, btn, run, id }) {
  const previewUrl = appUrl ? appUrl.replace(`//${p.name}.`, `//${p.name}-preview.`) : null
  const act = (action, msg) => run(`ro-${action}`, () => api(`/api/projects/${id}/rollout`, { method: 'POST', body: { action } }), msg)
  return (
    <div className="bluegreen" role="group" aria-label="Blue-green deployment">
      <div className="bg-title"><Icon name="layers" size={13} />Blue-green
        {ro.awaitingPromotion && <span className="badge blue">Preview ready</span>}
        {ro.aborted && <span className="badge red">Aborted</span>}
        {!ro.awaitingPromotion && !ro.aborted && ro.phase && <Badge value={ro.phase === 'Healthy' ? 'Healthy' : 'Progressing'} label={ro.phase} />}
      </div>
      <dl className="bg-versions">
        <dt>Live</dt><dd><code>{ro.activeImage || '—'}</code></dd>
        {ro.previewImage && <><dt>Preview</dt><dd><code>{ro.previewImage}</code>
          {previewUrl && <a href={previewUrl} target="_blank" rel="noreferrer" className="bg-link">open <Icon name="external" size={11} /></a>}</dd></>}
      </dl>
      {ro.awaitingPromotion && (
        <div className="actions">
          <button {...btn('ro-promote', 'btn small primary')} onClick={() => {
            if (window.confirm(`Switch prod traffic from ${ro.activeImage} to ${ro.previewImage}? The old version keeps running for 10 minutes so you can abort.`))
              act('promote', `Traffic switched to ${ro.previewImage}`)
          }}><Icon name="check" size={12} />Promote</button>
          <button {...btn('ro-abort', 'btn small danger')} onClick={() => {
            if (window.confirm(`Discard preview ${ro.previewImage}? Prod stays on ${ro.activeImage}.`))
              act('abort', 'Preview discarded — prod stays on the live version')
          }}><Icon name="undo" size={12} />Abort</button>
        </div>
      )}
      {ro.aborted && <div className="actions"><button {...btn('ro-retry', 'btn small')} onClick={() => act('retry', 'Retrying the preview')}>
        <Icon name="refresh" size={12} />Retry preview</button></div>}
    </div>
  )
}

const parseEnv = s => { try { return JSON.parse(s || '[]') } catch { return [] } }
const settingsOf = p => ({
  dockerfile: p.dockerfile || './Dockerfile', contextDir: p.contextDir || '.', defaultBranch: p.defaultBranch || '',
  testImage: p.testImage || '', testCommand: p.testCommand || '', scanSeverity: p.scanSeverity, webhook: !!p.webhookId,
  envVars: parseEnv(p.envVars), cpuRequest: p.cpuRequest || RUNTIME_DEFAULTS.cpuRequest, cpuLimit: p.cpuLimit || RUNTIME_DEFAULTS.cpuLimit,
  memoryRequest: p.memoryRequest || RUNTIME_DEFAULTS.memoryRequest, memoryLimit: p.memoryLimit || RUNTIME_DEFAULTS.memoryLimit,
})

function Settings({ p, run, btn }) {
  const initial = settingsOf(p)
  const [form, setForm] = useState(initial)
  const set = k => e => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const dirty = JSON.stringify({ ...form, envVars: cleanEnv(form.envVars) }) !== JSON.stringify(initial)
  async function save(e) {
    e.preventDefault()
    await run('settings', () => api(`/api/projects/${p.id}`, { method: 'PATCH', body: { ...form, envVars: cleanEnv(form.envVars) } }),
      'Settings saved — env vars and resources are committed to dev, staging and prod; build settings apply from the next run')
  }
  return (
    <form className="card form" style={{ maxWidth: 'none' }} onSubmit={save} aria-labelledby="settings-h">
      <div className="card-head"><h3 id="settings-h">Project settings</h3>
        {dirty && <span className="badge amber quiet" role="status">Unsaved changes</span>}</div>

      <fieldset className="fieldset">
        <legend>Repository</legend>
        <div className="row">
          <label>GitHub repository<input value={p.repoUrl} readOnly aria-describedby="locked-hint" /></label>
          <label>Project name<input value={p.name} readOnly aria-describedby="locked-hint" /></label>
        </div>
        <p id="locked-hint" className="hint">Name and repository are fixed — they identify the namespaces, Argo CD apps and image repository.
          To move to another repo, create a new project.</p>
        <label>Default branch<input required value={form.defaultBranch} onChange={set('defaultBranch')} spellCheck="false" /></label>
        <label className="check">
          <input type="checkbox" role="switch" checked={form.webhook} onChange={set('webhook')} />
          Build on every push &amp; pull request (GitHub webhook)
        </label>
      </fieldset>

      <fieldset className="fieldset">
        <legend>Build &amp; test</legend>
        <div className="row">
          <label>Dockerfile<input required value={form.dockerfile} onChange={set('dockerfile')} spellCheck="false" /></label>
          <label>Build context<input required value={form.contextDir} onChange={set('contextDir')} spellCheck="false" /></label>
        </div>
        <label>Stack preset
          <select defaultValue="" onChange={e => e.target.value && setForm({ ...form, ...PRESETS[e.target.value] })}>
            <option value="">Keep current test image &amp; command</option>
            {Object.keys(PRESETS).filter(Boolean).map(k => <option key={k} value={k}>{k}</option>)}
          </select>
        </label>
        <div className="row">
          <label>Test image<input value={form.testImage} onChange={set('testImage')} spellCheck="false" /></label>
          <label>Test command<input placeholder="(skip)" value={form.testCommand} onChange={set('testCommand')} spellCheck="false" /></label>
        </div>
        <label>Vulnerability gate
          <select value={form.scanSeverity} onChange={set('scanSeverity')}>
            {SEVERITIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
      </fieldset>

      <RuntimeFields value={form} onChange={v => setForm({ ...form, ...v })} />

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <button type="submit" {...btn('settings', 'btn primary')} disabled={!!btn('settings').disabled || !dirty}>
          <Icon name="check" size={14} />Save settings
        </button>
        <button type="button" className="btn ghost" disabled={!dirty} onClick={() => setForm(initial)}>Discard</button>
        <button type="button" {...btn('reconcile')} onClick={() => run('reconcile',
          () => api(`/api/projects/${p.id}/reconcile`, { method: 'POST' }), 'Namespaces, pull secrets and Argo CD apps re-applied')}>
          <Icon name="refresh" size={14} />Repair environments
        </button>
        <button type="button" {...btn('template')} title="Mark the GitHub repo as a template so new projects can start from it" onClick={() => run('template',
          () => api(`/api/projects/${p.id}/save-as-template`, { method: 'POST' }), 'Saved — it now appears under Templates › Your templates')}>
          <Icon name="layers" size={14} />Save as template
        </button>
      </div>
    </form>
  )
}

function DangerZone({ p, btn, run, onDeleted }) {
  const [confirmName, setConfirmName] = useState('')
  return (
    <div className="card form danger-zone" style={{ maxWidth: 'none' }}>
      <div className="card-head"><h3 style={{ color: 'var(--red)' }}>Danger zone</h3></div>
      <p className="muted" style={{ marginTop: 0 }}>
        Deletes the Argo CD apps and this project's release from the shared <code>dev</code>, <code>staging</code> and{' '}
        <code>prod</code> namespaces (other projects are untouched), the GitOps values files, the GitHub webhook and all run
        history. Registry images are kept.
      </p>
      <label>Type <code>{p.name}</code> to confirm
        <input value={confirmName} onChange={e => setConfirmName(e.target.value)} style={{ maxWidth: 360 }} autoComplete="off" spellCheck="false" />
      </label>
      <button {...btn('delete', 'btn danger')} disabled={confirmName !== p.name || btn('delete').disabled}
        onClick={async () => {
          const r = await run('delete', () => api(`/api/projects/${p.id}`, { method: 'DELETE', body: { confirmName } }),
            r => r.warnings?.length ? `Deleted ${r.deleted} with warnings: ${r.warnings.join('; ')}` : `Deleted ${r.deleted}`)
          if (r) onDeleted()
        }}><Icon name="trash" size={14} />Delete {p.name}</button>
    </div>
  )
}
