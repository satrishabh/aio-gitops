import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']
const NEXT = { dev: 'staging', staging: 'prod' }
const SEVERITIES = [
  ['HIGH,CRITICAL', 'Block on HIGH or CRITICAL (default)'],
  ['CRITICAL', 'Block on CRITICAL only'],
  ['NONE', 'Report only (never block)'],
]

export default function ProjectDetail({ me }) {
  const { id } = useParams()
  const nav = useNavigate()
  const [ref, setRef] = useState('')
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${id}`), 8000, [id])
  const { busy, run } = useAction(refresh)

  if (!data) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  const { project: p, environments, runs, deployments, promotions, failedSteps = {}, namespaces = {} } = data
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <div className="card">
        <div className="head">
          <h2>{p.name}</h2>
          <button {...btn('refresh', 'btn small')} onClick={() => run('refresh', refresh)}>Refresh</button>
        </div>
        <p>
          <a href={p.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer">{p.repoOwner}/{p.repoName}</a>
          {' · '}branch <code>{p.defaultBranch}</code>{' · '}image <code>{p.imageRepo}</code>
          {' · '}scan gate <code>{p.scanSeverity}</code>
          {' · '}webhook {p.webhookId ? <Badge value="Synced" /> : <span className="muted">not registered</span>}
        </p>
        <div className="row inline">
          <input placeholder={`branch or SHA (default ${p.defaultBranch})`} value={ref} onChange={e => setRef(e.target.value)} />
          <button {...btn('run', 'btn primary')} onClick={async () => {
            const r = await run('run', () => api(`/api/projects/${id}/runs`, { method: 'POST', body: { ref } }),
              r => `CI run #${r.id} started`)
            if (r) nav(`/runs/${r.id}`)
          }}>Run CI</button>
        </div>
      </div>

      <div className="envs">
        {ENVS.map(env => {
          const s = environments.find(e => e.environment === env) || {}
          const rolling = s.desiredTag && s.imageTag && s.desiredTag !== s.imageTag
          const parked = s.replicas === 0
          return (
            <div className="card env" key={env}>
              <h3>{env}</h3>
              <p className="muted">Namespace <code>{namespaces[env] || env}</code> · release <code>{p.name}</code></p>
              <p>Sync <Badge value={s.syncStatus} /> Health <Badge value={s.healthStatus} /></p>
              <p>
                Desired <code>{s.desiredTag || '—'}</code>{' '}
                {rolling ? <>→ live <code>{s.imageTag}</code> <Badge value="Progressing" /></> : null}
              </p>
              <p className="muted">
                {parked ? 'Not deployed yet (0 replicas — promote or run CI to start)' : `Replicas ${s.replicas ?? '—'}`}
              </p>
              {s.appUrl && !parked && <p><a href={s.appUrl} target="_blank" rel="noreferrer">{s.appUrl}</a></p>}
              <p className="muted">Updated {fmtTime(s.updatedAt)}</p>
              {NEXT[env] && (
                <button {...btn(`promote-${env}`)} onClick={() => run(`promote-${env}`,
                  () => api(`/api/projects/${id}/promote`, { method: 'POST', body: { from: env, to: NEXT[env] } }),
                  NEXT[env] === 'prod' ? 'Prod promotion requested — approve it on the Approvals page' : `Promoted to ${NEXT[env]}`)}>
                  Promote → {NEXT[env]}
                </button>
              )}
            </div>
          )
        })}
      </div>

      <div className="card">
        <h3>CI runs</h3>
        <table>
          <thead><tr><th>#</th><th>Status</th><th>Trigger</th><th>Ref / commit</th><th>Image</th><th>Vulnerabilities</th><th>Started</th><th>Duration</th><th></th></tr></thead>
          <tbody>
            {runs.map(r => (
              <tr key={r.id}>
                <td><Link to={`/runs/${r.id}`}>#{r.id}</Link></td>
                <td>
                  <Badge value={r.status} />{r.gateOverridden && <> <Badge value="RISK ACCEPTED" /></>}
                  {failedSteps[r.id] && <div className="failed-step">failed at {failedSteps[r.id]}</div>}
                  {r.status === 'FAILED' && !failedSteps[r.id] && r.message && <div className="failed-step">{r.message}</div>}
                </td>
                <td>{r.trigger} <span className="muted">by {r.triggeredBy}</span></td>
                <td><code>{(r.commitSha || r.gitRef).slice(0, 12)}</code></td>
                <td><code>{r.imageUrl ? r.imageUrl.split(':').pop() : '—'}</code></td>
                <td><Vulns run={r} /></td>
                <td>{fmtTime(r.startedAt || r.createdAt)}</td>
                <td>{duration(r.startedAt, r.finishedAt)}</td>
                <td>
                  {['FAILED', 'SUCCEEDED', 'CANCELLED'].includes(r.status) && (
                    <button {...btn(`rerun-${r.id}`, 'btn small')} onClick={async () => {
                      const n = await run(`rerun-${r.id}`, () => api(`/api/runs/${r.id}/rerun`, { method: 'POST', body: {} }),
                        n => `Re-run #${n.id} started`)
                      if (n) nav(`/runs/${n.id}`)
                    }}>Re-run</button>
                  )}
                </td>
              </tr>
            ))}
            {runs.length === 0 && <tr><td colSpan="9" className="muted">No runs yet — click Run CI.</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h3>Deployment history (Argo CD)</h3>
        <table>
          <thead><tr><th>Env</th><th>Argo ID</th><th>Image tag</th><th>GitOps revision</th><th>Deployed</th><th></th></tr></thead>
          <tbody>
            {deployments.map(d => (
              <tr key={d.id}>
                <td>{d.environment}</td>
                <td>{d.argoHistoryId}</td>
                <td><code>{d.imageTag || '?'}</code></td>
                <td><code>{(d.revision || '').slice(0, 10)}</code></td>
                <td>{fmtTime(d.deployedAt)}</td>
                <td>
                  {d.imageTag && d.imageTag !== '0.0.0' && (
                    <button {...btn(`rb-${d.id}`, 'btn small')} onClick={() => {
                      if (!confirm(`Roll back ${d.environment} to ${d.imageTag}?`)) return
                      run(`rb-${d.id}`, () => api(`/api/projects/${id}/environments/${d.environment}/rollback`,
                        { method: 'POST', body: { deploymentId: d.id } }),
                        d.environment === 'prod' ? 'Prod rollback requested — waiting for approval' : 'Rollback committed')
                    }}>Roll back to this</button>
                  )}
                </td>
              </tr>
            ))}
            {deployments.length === 0 && <tr><td colSpan="6" className="muted">No deployments recorded yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="card">
        <h3>Promotions &amp; rollbacks</h3>
        <table>
          <thead><tr><th>#</th><th>Kind</th><th>Target</th><th>Tag</th><th>Status</th><th>Requested</th><th>Decided</th></tr></thead>
          <tbody>
            {promotions.map(pr => (
              <tr key={pr.id}>
                <td>{pr.id}</td>
                <td>{pr.kind}</td>
                <td>{pr.fromEnv ? `${pr.fromEnv} → ` : ''}{pr.toEnv}</td>
                <td><code>{pr.imageTag}</code></td>
                <td><Badge value={pr.status} /> {pr.message && <span className="muted">{pr.message}</span>}</td>
                <td>{pr.requestedBy} <span className="muted">{fmtTime(pr.requestedAt)}</span></td>
                <td>{pr.decidedBy || '—'} <span className="muted">{fmtTime(pr.decidedAt)}</span></td>
              </tr>
            ))}
            {promotions.length === 0 && <tr><td colSpan="7" className="muted">None yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <Settings p={p} busy={busy} run={run} btn={btn} />
      <DangerZone p={p} btn={btn} run={run} onDeleted={() => nav('/')} />
    </>
  )
}

function Settings({ p, run, btn }) {
  const [form, setForm] = useState({ scanSeverity: p.scanSeverity, testImage: p.testImage, testCommand: p.testCommand || '' })
  const set = k => e => setForm({ ...form, [k]: e.target.value })
  return (
    <div className="card form">
      <h3>Settings</h3>
      <label>Vulnerability gate
        <select value={form.scanSeverity} onChange={set('scanSeverity')}>
          {SEVERITIES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
      </label>
      <div className="row">
        <label>Test image<input value={form.testImage} onChange={set('testImage')} /></label>
        <label>Test command<input placeholder="(skip)" value={form.testCommand} onChange={set('testCommand')} /></label>
      </div>
      <button {...btn('settings', 'btn primary')} onClick={() => run('settings',
        () => api(`/api/projects/${p.id}`, { method: 'PATCH', body: form }), 'Settings saved — used from the next run')}>
        Save settings
      </button>{' '}
      <button {...btn('reconcile')} onClick={() => run('reconcile',
        () => api(`/api/projects/${p.id}/reconcile`, { method: 'POST' }), 'Namespaces, pull secrets and Argo CD apps re-applied')}>
        Repair environments
      </button>
    </div>
  )
}

function DangerZone({ p, btn, run, onDeleted }) {
  const [confirmName, setConfirmName] = useState('')
  return (
    <div className="card form danger-zone">
      <h3>Delete project</h3>
      <p className="muted">
        Removes the Argo CD apps and this project's release from the shared <code>dev</code>, <code>staging</code>{' '}
        and <code>prod</code> namespaces (other projects are untouched), the GitOps values files, the GitHub webhook
        and all run history. Container images in the registry are kept.
      </p>
      <label>Type <code>{p.name}</code> to confirm
        <input value={confirmName} onChange={e => setConfirmName(e.target.value)} />
      </label>
      <button {...btn('delete', 'btn danger')} disabled={confirmName !== p.name || btn('delete').disabled}
        onClick={async () => {
          const r = await run('delete', () => api(`/api/projects/${p.id}`, { method: 'DELETE', body: { confirmName } }),
            r => r.warnings?.length ? `Deleted ${r.deleted} with warnings: ${r.warnings.join('; ')}` : `Deleted ${r.deleted}`)
          if (r) onDeleted()
        }}>Delete {p.name}</button>
    </div>
  )
}
