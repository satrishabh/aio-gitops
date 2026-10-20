import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'

const ENVS = ['dev', 'staging', 'prod']
const NEXT = { dev: 'staging', staging: 'prod' }

export default function ProjectDetail() {
  const { id } = useParams()
  const [data, setData] = useState(null)
  const [ref, setRef] = useState('')
  const [msg, setMsg] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(() => api(`/api/projects/${id}`).then(setData).catch(e => setError(e.message)), [id])
  useEffect(() => {
    load()
    const t = setInterval(load, 8000)
    return () => clearInterval(t)
  }, [load])

  async function act(fn, ok) {
    setError(''); setMsg('')
    try { await fn(); setMsg(ok); load() } catch (e) { setError(e.message) }
  }

  if (!data) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  const { project: p, environments, runs, deployments, promotions } = data

  return (
    <>
      <div className="card">
        <h2>{p.name}</h2>
        <p>
          <a href={p.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer">{p.repoOwner}/{p.repoName}</a>
          {' · '}branch <code>{p.defaultBranch}</code>{' · '}image <code>{p.imageRepo}</code>
          {' · '}webhook {p.webhookId ? <Badge value="Synced" /> : <span className="muted">not registered</span>}
        </p>
        <div className="row inline">
          <input placeholder={`branch or SHA (default ${p.defaultBranch})`} value={ref} onChange={e => setRef(e.target.value)} />
          <button className="btn primary" onClick={() => act(
            () => api(`/api/projects/${id}/runs`, { method: 'POST', body: { ref } }), 'CI run started')}>
            Run CI
          </button>
        </div>
        {msg && <p className="ok">{msg}</p>}
        {error && <p className="error">{error}</p>}
      </div>

      <div className="envs">
        {ENVS.map(env => {
          const s = environments.find(e => e.environment === env) || {}
          return (
            <div className="card env" key={env}>
              <h3>{env}</h3>
              <p>Sync <Badge value={s.syncStatus} /> Health <Badge value={s.healthStatus} /></p>
              <p>Image <code>{s.imageTag || '—'}</code></p>
              <p className="muted">Updated {fmtTime(s.updatedAt)}</p>
              {NEXT[env] && (
                <button className="btn" onClick={() => act(
                  () => api(`/api/projects/${id}/promote`, { method: 'POST', body: { from: env, to: NEXT[env] } }),
                  NEXT[env] === 'prod' ? 'Prod promotion requested — waiting for approval' : `Promoted to ${NEXT[env]}`)}>
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
          <thead><tr><th>#</th><th>Status</th><th>Trigger</th><th>Ref / commit</th><th>Image</th><th>Vulnerabilities</th><th>Started</th><th>Duration</th></tr></thead>
          <tbody>
            {runs.map(r => (
              <tr key={r.id}>
                <td><Link to={`/runs/${r.id}`}>#{r.id}</Link></td>
                <td><Badge value={r.status} /></td>
                <td>{r.trigger} <span className="muted">by {r.triggeredBy}</span></td>
                <td><code>{(r.commitSha || r.gitRef).slice(0, 12)}</code></td>
                <td><code>{r.imageUrl ? r.imageUrl.split(':').pop() : '—'}</code></td>
                <td><Vulns run={r} /></td>
                <td>{fmtTime(r.startedAt || r.createdAt)}</td>
                <td>{duration(r.startedAt, r.finishedAt)}</td>
              </tr>
            ))}
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
                  {d.imageTag && (
                    <button className="btn small" onClick={() => {
                      if (!confirm(`Roll back ${d.environment} to ${d.imageTag}?`)) return
                      act(() => api(`/api/projects/${id}/environments/${d.environment}/rollback`,
                        { method: 'POST', body: { deploymentId: d.id } }),
                        d.environment === 'prod' ? 'Prod rollback requested — waiting for approval' : 'Rollback committed')
                    }}>Roll back to this</button>
                  )}
                </td>
              </tr>
            ))}
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
          </tbody>
        </table>
      </div>
    </>
  )
}
