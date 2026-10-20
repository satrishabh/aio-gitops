import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'

const ENVS = ['dev', 'staging', 'prod']

export default function Projects() {
  const [items, setItems] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => {
    const load = () => api('/api/projects').then(setItems).catch(e => setError(e.message))
    load()
    const t = setInterval(load, 10000)
    return () => clearInterval(t)
  }, [])

  if (error) return <p className="error">{error}</p>
  if (!items) return <p>Loading…</p>
  if (items.length === 0) return (
    <div className="card">
      <p>No projects yet.</p>
      <Link className="btn primary" to="/new">Add a GitHub repository</Link>
    </div>
  )

  return (
    <div className="card">
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
                    {s ? <><Badge value={s.healthStatus} /> <code>{s.imageTag || '—'}</code></> : <span className="muted">—</span>}
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
