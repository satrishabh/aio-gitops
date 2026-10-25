import { Link } from 'react-router-dom'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']

export default function Projects() {
  const { data: items, error, stale, refresh } = usePoll(() => api('/api/projects'), 10000)

  if (!items) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  if (items.length === 0) return (
    <div className="card">
      <p>No projects yet.</p>
      <Link className="btn primary" to="/new">Add a GitHub repository</Link>
    </div>
  )

  return (
    <div className="card">
      <Stale stale={stale} error={error} onRetry={refresh} />
      <h2>Projects</h2>
      <table>
        <thead>
          <tr><th>Project</th><th>Repository</th><th>Last CI run</th>{ENVS.map(e => <th key={e}>{e}</th>)}</tr>
        </thead>
        <tbody>
          {items.map(({ project, lastRun, environments }) => (
            <tr key={project.id}>
              <td><Link to={`/projects/${project.id}`}>{project.name}</Link></td>
              <td><a href={project.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer">{project.repoOwner}/{project.repoName}</a></td>
              <td>
                {lastRun?.id
                  ? <><Link to={`/runs/${lastRun.id}`}>#{lastRun.id}</Link> <Badge value={lastRun.status} /> <span className="muted">{fmtTime(lastRun.createdAt)}</span></>
                  : <span className="muted">never</span>}
              </td>
              {ENVS.map(env => {
                const s = environments.find(e => e.environment === env)
                return (
                  <td key={env}>
                    {s ? <>
                      <Badge value={s.healthStatus} /> <code>{s.desiredTag || s.imageTag || '—'}</code>
                      {s.replicas === 0 && <span className="muted"> (not deployed)</span>}
                    </> : <span className="muted">—</span>}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
