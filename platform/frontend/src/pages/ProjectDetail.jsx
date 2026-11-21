import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, Skeleton, Tabs, ago, envTone } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'
import { BUILD_STRATEGIES, PRESETS, SEVERITIES } from '../projectOptions.js'
import RuntimeFields, { RUNTIME_DEFAULTS, cleanEnv } from '../components/RuntimeFields.jsx'
import DeliverySettings from '../components/DeliverySettings.jsx'
import ToolLinks from '../components/ToolLinks.jsx'
import CertificateManager from '../components/CertificateManager.jsx'
import { DetailHead, StateWord } from '../components/flow/FlowParts.jsx'

const ENVS = ['dev', 'staging', 'prod']
const NEXT = { dev: 'staging', staging: 'prod' }
/** Something real has been deployed to this env (not the 0.0.0 placeholder). */
const isDeployed = s => !!s.desiredTag && s.desiredTag !== '0.0.0'

/** The state of one environment in words (the dot colour only repeats it). */
function envWord(s, tone, deployed, stopped) {
  if (!deployed) return 'Not deployed'
  if (stopped) return 'Scaled down'
  if (tone === 'red') return s.healthStatus === 'Missing' ? 'Missing' : 'Degraded'
  if (tone === 'blue') return 'Rolling out'
  if (tone === 'amber') return 'Out of sync'
  if (tone === 'green') return 'Healthy'
  return s.healthStatus || 'Unknown'
}

export default function ProjectDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const [ref, setRef] = useState('')
  const [deployBuild, setDeployBuild] = useState(true)
  const [tab, setTab] = useState('runs')
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}`), 8000, [id])
  const { busy, run } = useAction(refresh)

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={130} /><Skeleton h={220} /><Skeleton h={300} /></>
  const { project: p, environments, runs, deployments, promotions, failedSteps = {}, namespaces = {}, clusters = {}, rollout = {} } = data
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })
  const envOf = env => environments.find(e => e.environment === env) || {}

  async function startRun() {
    const r = await run('run', () => api(`/api/projects/${id}/runs`, { method: 'POST', body: { ref, deploy: deployBuild } }), r => `CI run #${r.id} started`)
    if (r) nav(`/runs/${r.id}`)
  }
  const promote = env => run(`promote-${env}`,
    () => api(`/api/projects/${id}/promote`, { method: 'POST', body: { from: env, to: NEXT[env] } }),
    NEXT[env] === 'prod' ? 'Prod promotion requested — approve it on the Approvals page' : `Promoted to ${NEXT[env]}`)
  const scale = (env, n) => run(`scale-${env}`,
    () => api(`/api/projects/${id}/environments/${env}/scale`, { method: 'POST', body: { replicas: n } }),
    n === 0 ? `${env} scaled down — the app is stopped` : `${env} scaling to ${n} replica${n > 1 ? 's' : ''}`)
  /** Same call as the Roll back button on each deployment row. */
  function rollback(d) {
    if (!confirm(`Roll back ${d.environment} to ${d.imageTag}?`)) return
    run(`rb-${d.id}`, () => api(`/api/projects/${id}/environments/${d.environment}/rollback`,
      { method: 'POST', body: { deploymentId: d.id } }),
      d.environment === 'prod' ? 'Prod rollback requested — waiting for approval' : 'Rollback committed')
  }
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

      <DetailHead
        avatar={<Avatar name={p.name} size={54} />}
        title={p.name}
        sub={<>
          <a href={p.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer"><Icon name="github" size={13} /> {p.repoOwner}/{p.repoName}</a>
          <span><Icon name="git" size={13} /> {p.defaultBranch}</span>
          <span><Icon name="box" size={13} /> <code>{p.imageRepo}</code></span>
          <span><Icon name="shield" size={13} /> gate <code>{p.scanSeverity}</code></span>
          <span><Icon name="zap" size={13} /> webhook {p.webhookId ? 'on' : 'off'}</span>
        </>}
        below={<div className="fl-run-bar">
          <div className="fl-run-ci">
            <input aria-label="Branch or commit to build" placeholder={`branch or SHA (default ${p.defaultBranch})`} value={ref} onChange={e => setRef(e.target.value)}
                   onKeyDown={e => e.key === 'Enter' && startRun()} />
            <button {...btn('run', 'btn primary')} onClick={startRun}><Icon name="play" size={14} />Run CI</button>
            <label className="fl-inline-check">
              <input type="checkbox" checked={deployBuild} onChange={e => setDeployBuild(e.target.checked)} />
              Deploy manual builds to dev after checks pass
            </label>
          </div>
          <div className="fl-run-tools">
            <Link className="btn" to={`/pipelines/security?project=${p.id}`}><Icon name="shield" size={14} />Security scan</Link>
            <button className="btn" onClick={() => setTab('settings')} aria-label={`Edit ${p.name} settings`}><Icon name="settings" size={14} />Edit</button>
            {deployedEnvs.length > 0 && (anyRunning
              ? <button {...btn('scale-all', 'btn')} onClick={() => scaleAll(0)} title="Stop every environment (0 pods)"><Icon name="stop" size={13} />Stop app</button>
              : <button {...btn('scale-all', 'btn')} onClick={() => scaleAll(1)} title="Start every deployed environment"><Icon name="play" size={13} />Start app</button>)}
            <button {...btn('refresh', 'btn ghost')} title="Refresh" aria-label="Refresh" onClick={() => run('refresh', refresh)}><Icon name="refresh" size={15} /></button>
          </div>
        </div>}
      />

      <section className="fl-track" aria-labelledby="track-h">
        <div className="fl-track-head">
          <h2 id="track-h">Delivery track</h2>
          <p>Each build goes dev → staging → prod. Promote moves the running image to the next stop; changes to prod wait for an approver.</p>
        </div>
        <ol className="fl-stops">
          {ENVS.map((env, i) => {
            const s = envOf(env)
            const tone = envTone(s)
            const deployed = isDeployed(s)
            const parked = !deployed
            const stopped = deployed && s.replicas === 0
            const n = s.replicas || 0
            const rolling = !stopped && s.desiredTag && s.imageTag && s.desiredTag !== s.imageTag
            const current = s.desiredTag || s.imageTag
            const back = deployments.find(d => d.environment === env && d.imageTag && d.imageTag !== '0.0.0' && d.imageTag !== current)
            return (
              <li key={env} className={`fl-stop is-${tone}${parked ? ' parked' : ''}`}>
                <div className="fl-stop-head">
                  <span className="fl-stop-n" aria-hidden="true">{i + 1}</span>
                  <h3>{env}</h3>
                  <StateWord tone={tone}>{envWord(s, tone, deployed, stopped)}</StateWord>
                  {env === 'prod' && <span className="badge amber"><Icon name="lock" size={11} />gated</span>}
                </div>
                <div className="fl-stop-label">{parked ? 'Not deployed' : stopped ? 'Stopped · image' : 'Running image'}</div>
                <div className="fl-stop-tag">{parked ? <span className="muted">—</span> : (current || '—')}</div>
                {rolling && <div className="fl-stop-rolling"><Badge value="Progressing" label="Rolling out" /> <span className="muted">live {s.imageTag}</span></div>}
                {env === 'prod' && rollout.enabled && <BlueGreen ro={rollout} appUrl={s.appUrl} p={p} btn={btn} run={run} id={id} />}

                <dl className="fl-stop-meta">
                  <div><dt>Sync</dt><dd><Badge value={s.syncStatus} /></dd></div>
                  <div><dt>Health</dt><dd><Badge value={s.healthStatus} /></dd></div>
                  <div><dt>Cluster</dt><dd><Link to="/clusters"><code>{clusters[env] || 'in-cluster'}</code></Link></dd></div>
                  <div><dt>Namespace</dt><dd><code>{namespaces[env] || env}</code></dd></div>
                  <div><dt>Replicas</dt>
                    <dd>{deployed
                      ? <span className="stepper">
                          <button aria-label={`Fewer ${env} replicas`} disabled={!!busy || n === 0} onClick={() => scale(env, n - 1)}>−</button>
                          <b aria-live="polite" aria-label={`${n} replicas`}>{n}</b>
                          <button aria-label={`More ${env} replicas`} disabled={!!busy || n >= 5} onClick={() => scale(env, n + 1)}>+</button>
                        </span>
                      : (s.replicas ?? '—')}</dd></div>
                  <div><dt>Updated</dt><dd>{ago(s.updatedAt)}</dd></div>
                </dl>

                <div className="fl-stop-foot">
                  <div className="fl-stop-move">
                    {NEXT[env] && <button {...btn(`promote-${env}`, 'btn primary small')} disabled={!!busy || parked}
                                          title={parked ? `Nothing in ${env} to promote yet` : `Promote ${env} → ${NEXT[env]}`}
                                          onClick={() => promote(env)}>
                      {NEXT[env] === 'prod' ? <Icon name="lock" size={12} /> : <Icon name="arrow" size={12} />}
                      Promote to {NEXT[env]}{NEXT[env] === 'prod' && <span className="fl-needs"> · needs approval</span>}
                    </button>}
                    {back && <button {...btn(`rb-${back.id}`, 'btn small')} onClick={() => rollback(back)} title={`Roll ${env} back to ${back.imageTag}, deployed ${ago(back.deployedAt)}`}>
                      <Icon name="undo" size={12} />Roll back to <code>{back.imageTag.slice(0, 12)}</code></button>}
                    {parked && <span className="fl-stop-hint">{env === 'dev' ? 'Run CI to deploy here' : `Promote from ${ENVS[i - 1]} to deploy`}</span>}
                  </div>
                  <div className="fl-stop-actions">
                    <Link className="btn small" to={`/projects/${id}/cd/${env}`} title={`Argo CD sync, resources, pod logs and config for ${env}`}>
                      <Icon name="rocket" size={13} />CD</Link>
                    {s.appUrl && deployed && !stopped && <a className="btn small" href={s.appUrl} target="_blank" rel="noreferrer"><Icon name="external" size={13} />Open app</a>}
                    {deployed && (stopped
                      ? <button {...btn(`scale-${env}`, 'btn small primary')} onClick={() => scale(env, 1)}><Icon name="play" size={12} />Start</button>
                      : <button {...btn(`scale-${env}`, 'btn small')} onClick={() => scale(env, 0)} title="Scale down to 0 pods"><Icon name="stop" size={12} />Stop</button>)}
                  </div>
                </div>
              </li>
            )
          })}
        </ol>
      </section>

      <div className="fl-extras">
        <ToolLinks key={`tools-${p.id}`} projectId={p.id} />
        <DeliverySettings key={p.id} project={p} onSaved={refresh} />
      </div>

      <Tabs label="Project sections" value={tab} onChange={setTab} tabs={[
        { id: 'runs', label: 'CI runs', icon: 'play', count: runs.length },
        { id: 'deploys', label: 'Deployments', icon: 'rocket', count: deployments.length },
        { id: 'promos', label: 'Promotions', icon: 'shield', count: promotions.length },
        { id: 'domains', label: 'Domains', icon: 'globe' },
        { id: 'certificates', label: 'Certificates', icon: 'shield' },
        { id: 'settings', label: 'Settings', icon: 'settings' },
      ]} />

      {tab === 'runs' && (
        <div className="card flush">
          <table className="fl-table">
            <thead><tr><th>Run</th><th>Status</th><th>Commit</th><th>Image</th><th>Vulnerabilities</th><th>Trigger</th><th>Duration</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.id} style={{ cursor: 'pointer' }} onClick={e => { if (!e.target.closest('button,a')) nav(`/runs/${r.id}`) }}>
                  <td data-label="Run"><Link to={`/runs/${r.id}`}><b>#{r.id}</b></Link><div className="muted fl-small">{ago(r.createdAt)}</div></td>
                  <td data-label="Status">
                    <Badge value={r.status} />{r.gateOverridden && <> <Badge value="RISK ACCEPTED" /></>}
                    {failedSteps[r.id] && <div className="failed-step"><Icon name="x" size={11} /> {failedSteps[r.id]}</div>}
                    {r.status === 'FAILED' && !failedSteps[r.id] && r.message && <div className="failed-step">{r.message.slice(0, 80)}</div>}
                  </td>
                  <td data-label="Commit"><code>{(r.commitSha || r.gitRef).slice(0, 10)}</code></td>
                  <td data-label="Image"><code>{r.imageUrl ? r.imageUrl.split(':').pop().slice(0, 12) : '—'}</code></td>
                  <td data-label="Vulnerabilities"><Vulns run={r} /></td>
                  <td data-label="Trigger"><span className="muted">{r.trigger.toLowerCase()} · {r.triggeredBy}</span></td>
                  <td data-label="Duration" className="mono">{duration(r.startedAt, r.finishedAt)}</td>
                  <td className="fl-cell-actions">
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
          <table className="fl-table">
            <thead><tr><th>Environment</th><th>Image tag</th><th>GitOps revision</th><th>Deployed</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {deployments.map(d => (
                <tr key={d.id}>
                  <td data-label="Environment"><Link to={`/projects/${id}/cd/${d.environment}`} style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}><span className="dot green" />{d.environment}</Link>
                    <span className="muted"> · #{d.argoHistoryId}</span></td>
                  <td data-label="Image tag"><code>{d.imageTag || '?'}</code></td>
                  <td data-label="GitOps revision"><code>{(d.revision || '').slice(0, 10)}</code></td>
                  <td data-label="Deployed">{fmtTime(d.deployedAt)} <span className="muted">({ago(d.deployedAt)})</span></td>
                  <td className="fl-cell-actions">
                    {d.imageTag && d.imageTag !== '0.0.0' && (
                      <button {...btn(`rb-${d.id}`, 'btn small')} onClick={() => rollback(d)}><Icon name="undo" size={12} />Roll back</button>
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
          <table className="fl-table">
            <thead><tr><th>#</th><th>Kind</th><th>Target</th><th>Tag</th><th>Status</th><th>Requested</th><th>Decided</th></tr></thead>
            <tbody>
              {promotions.map(pr => (
                <tr key={pr.id}>
                  <td data-label="#">{pr.id}</td>
                  <td data-label="Kind"><Icon name={pr.kind === 'ROLLBACK' ? 'undo' : 'rocket'} size={13} /> {pr.kind.toLowerCase()}</td>
                  <td data-label="Target">{pr.fromEnv ? <>{pr.fromEnv} <Icon name="arrow" size={11} /> </> : ''}<b>{pr.toEnv}</b></td>
                  <td data-label="Tag"><code>{pr.imageTag}</code></td>
                  <td data-label="Status"><Badge value={pr.status} /> {pr.message && <div className="muted fl-small">{pr.message}</div>}</td>
                  <td data-label="Requested">{pr.requestedBy} <div className="muted fl-small">{ago(pr.requestedAt)}</div></td>
                  <td data-label="Decided">{pr.decidedBy || '—'} <div className="muted fl-small">{pr.decidedAt ? ago(pr.decidedAt) : ''}</div></td>
                </tr>
              ))}
              {promotions.length === 0 && <tr><td colSpan="7" className="empty">No promotions yet. Use Promote on the track above.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'domains' && <DomainsTab id={id} />}
      {tab === 'certificates' && <CertificateManager projectId={id} onDomains={() => setTab('domains')} />}

      {tab === 'settings' && <>
        <Settings p={p} run={run} btn={btn} />
        <DangerZone p={p} btn={btn} run={run} onDeleted={() => nav('/projects')} />
      </>}
    </>
  )
}


function DomainsTab({ id }) {
  const [form, setForm] = useState({
    env: 'dev', host: '', path: '/', stripPrefix: false, forceHttps: true, wwwRedirect: false,
  })
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}/domains`), 15000, [id])
  const { busy, run } = useAction(refresh)
  const domains = data?.domains || []

  async function add(e) {
    e.preventDefault()
    const result = await run('domain-add', () => api(`/api/projects/${id}/domains`, { method: 'POST', body: form }),
      d => `Added https://${d.host}${d.path === '/' ? '' : d.path}`)
    if (result) setForm({ env: form.env, host: '', path: '/', stripPrefix: false, forceHttps: true, wwwRedirect: false })
  }

  function remove(domain) {
    if (!window.confirm(`Remove ${domain.host}${domain.path === '/' ? '' : domain.path}?`)) return
    run(`domain-remove-${domain.id}`, () => api(`/api/projects/${id}/domains/${domain.id}`, { method: 'DELETE' }),
      `Removed ${domain.host}${domain.path === '/' ? '' : domain.path}`)
  }

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <form className="card form" style={{ maxWidth: 'none' }} onSubmit={add}>
        <div className="card-head"><h3>Add a custom domain or path</h3></div>
        <p className="muted" style={{ marginTop: 0 }}>
          Point a hostname to the selected environment. You can share one hostname across projects by assigning different paths.
          HTTPS certificates are requested automatically after DNS resolves to the cluster.
        </p>
        <div className="row">
          <label>Environment
            <select value={form.env} onChange={e => setForm({ ...form, env: e.target.value })}>
              {ENVS.map(env => <option key={env} value={env}>{env}</option>)}
            </select>
          </label>
          <label>Hostname
            <input required placeholder="app.example.com" value={form.host}
              onChange={e => setForm({ ...form, host: e.target.value })} />
          </label>
          <label>Path
            <input placeholder="/" value={form.path} onChange={e => setForm({ ...form, path: e.target.value })} />
          </label>
        </div>
        <div className="row" style={{ flexWrap: 'wrap' }}>
          <label className="check"><input type="checkbox" checked={form.stripPrefix}
            onChange={e => setForm({ ...form, stripPrefix: e.target.checked })} />Strip path prefix</label>
          <label className="check"><input type="checkbox" checked={form.forceHttps}
            onChange={e => setForm({ ...form, forceHttps: e.target.checked })} />Redirect HTTP to HTTPS</label>
          <label className="check"><input type="checkbox" checked={form.wwwRedirect}
            onChange={e => setForm({ ...form, wwwRedirect: e.target.checked })} />Redirect www to this hostname</label>
        </div>
        <button className={`btn primary${busy === 'domain-add' ? ' busy' : ''}`} disabled={!!busy}>
          <Icon name="plus" size={14} />Add domain
        </button>
      </form>

      <div className="card flush">
        <div className="card-head" style={{ padding: '16px 18px 0' }}>
          <h3>Custom domains</h3>
          <button className="btn small ghost" disabled={!!busy} onClick={() => run('domain-refresh', refresh)}>
            <Icon name="refresh" size={13} />Refresh
          </button>
        </div>
        {data == null ? error ? <p className="error" style={{ padding: 18 }}>{error}</p> : <Skeleton h={100} />
          : domains.length === 0 ? <Empty icon="globe" title="No custom domains yet">Add a hostname above to route traffic to this project.</Empty>
            : (
              <table className="fl-table">
                <thead><tr><th>Route</th><th>Environment</th><th>Certificate</th><th>DNS</th><th><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  {domains.map(d => (
                    <tr key={d.id}>
                      <td data-label="Route"><a href={d.url} target="_blank" rel="noreferrer"><b>{d.host}</b>{d.path !== '/' && <code>{d.path}</code>}</a>
                        <div className="muted">{d.stripPrefix ? 'path prefix stripped' : 'path preserved'}{d.wwwRedirect ? ' · www redirects here' : ''}</div>
                      </td>
                      <td data-label="Environment">{d.env}</td>
                      <td data-label="Certificate"><Badge value={d.status} />{d.message && <div className="muted" style={{ fontSize: '0.75rem', maxWidth: 320 }}>{d.message}</div>}
                        {d.certExpiresAt && <div className="muted" style={{ fontSize: '0.75rem' }}>Expires {fmtTime(d.certExpiresAt)}</div>}
                      </td>
                      <td data-label="DNS">{d.dnsRecords.length
                        ? d.dnsRecords.map((record, i) => <div key={`${record.name}-${i}`} className="mono" style={{ fontSize: '0.75rem' }}>
                            A {record.name} → {record.value}
                          </div>)
                        : d.managedDns ? <span className="muted">Managed automatically</span>
                          : <span className="muted">Ingress IP not reported yet</span>}
                      </td>
                      <td className="fl-cell-actions">
                        <button className="btn small" disabled={!!busy} title="Check DNS and certificate status"
                          onClick={() => run(`domain-check-${d.id}`, () => api(`/api/projects/${id}/domains/${d.id}/check`, { method: 'POST' }), 'Domain status checked')}>
                          <Icon name="refresh" size={12} />Check
                        </button>{' '}
                        <button className="btn small danger" disabled={!!busy} onClick={() => remove(d)}>
                          <Icon name="trash" size={12} />Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
      </div>
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
  buildStrategy: p.buildStrategy || 'auto',
  envVars: parseEnv(p.envVars), cpuRequest: p.cpuRequest || RUNTIME_DEFAULTS.cpuRequest, cpuLimit: p.cpuLimit || RUNTIME_DEFAULTS.cpuLimit,
  memoryRequest: p.memoryRequest || RUNTIME_DEFAULTS.memoryRequest, memoryLimit: p.memoryLimit || RUNTIME_DEFAULTS.memoryLimit,
})

function Settings({ p, run, btn }) {
  const initial = settingsOf(p)
  const [form, setForm] = useState(initial)
  const set = k => e => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const dirty = JSON.stringify({ ...form, envVars: cleanEnv(form.envVars) }) !== JSON.stringify(initial)
  // Which strategies can be chosen now (buildah only while Podman / Buildah is switched on); unknown = the ungated ones
  const [strategies, setStrategies] = useState(null)
  useEffect(() => { api('/api/projects/build-strategies').then(setStrategies).catch(() => {}) }, [])
  const offerable = ([v]) => v === initial.buildStrategy || (strategies ? strategies[v] === true : v !== 'buildah')
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
        <label>Build strategy
          <select value={form.buildStrategy} onChange={set('buildStrategy')}>
            {BUILD_STRATEGIES.filter(offerable).map(([v, l]) => <option key={v} value={v}>
              {l}{strategies && strategies[v] === false ? ' (switched off: runs are refused)' : ''}</option>)}
          </select>
        </label>
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
        <button type="button" {...btn('reconcile')} title="Re-apply Argo CD apps and move them to the cluster each environment maps to (prod moves only for approvers; can take a few minutes)"
          onClick={() => run('reconcile', () => api(`/api/projects/${p.id}/reconcile`, { method: 'POST' }), r => {
            const e = r?.environments || {}
            const moved = Object.keys(e).filter(k => e[k] === 'MOVED'), pending = Object.keys(e).filter(k => e[k] === 'MOVE_PENDING')
            return ['Argo CD apps re-applied', moved.length && `moved ${moved.join(', ')} to their new cluster`,
              pending.length && `${pending.join(', ')} still on the old cluster — an approver must repair to move prod`].filter(Boolean).join(' · ')
          })}>
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
