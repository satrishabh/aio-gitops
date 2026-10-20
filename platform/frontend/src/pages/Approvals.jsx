import { useEffect, useState } from 'react'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'

export default function Approvals({ me }) {
  const [items, setItems] = useState([])
  const [projects, setProjects] = useState({})
  const [filter, setFilter] = useState('PENDING')
  const [error, setError] = useState('')

  const load = () => api(`/api/promotions${filter ? `?status=${filter}` : ''}`).then(setItems).catch(e => setError(e.message))
  useEffect(() => { load() }, [filter])
  useEffect(() => {
    api('/api/projects').then(list => setProjects(Object.fromEntries(list.map(x => [x.project.id, x.project.name]))))
  }, [])

  async function decide(id, action) {
    setError('')
    const body = action === 'reject' ? { reason: prompt('Reason for rejecting?') || '' } : undefined
    try { await api(`/api/promotions/${id}/${action}`, { method: 'POST', body }); load() } catch (e) { setError(e.message) }
  }

  return (
    <div className="card">
      <h2>Production approvals</h2>
      <div className="row inline">
        <select value={filter} onChange={e => setFilter(e.target.value)}>
          <option value="PENDING">Pending</option>
          <option value="">All</option>
          <option value="DONE">Done</option>
          <option value="REJECTED">Rejected</option>
          <option value="FAILED">Failed</option>
        </select>
        {!me.approver && <span className="muted">You are not an approver; you can view but not approve.</span>}
      </div>
      {error && <p className="error">{error}</p>}
      <table>
        <thead><tr><th>#</th><th>Project</th><th>Kind</th><th>Target</th><th>Image tag</th><th>Requested by</th><th>Status</th><th></th></tr></thead>
        <tbody>
          {items.map(pr => (
            <tr key={pr.id}>
              <td>{pr.id}</td>
              <td>{projects[pr.projectId] || pr.projectId}</td>
              <td>{pr.kind}</td>
              <td>{pr.fromEnv ? `${pr.fromEnv} → ` : ''}{pr.toEnv}</td>
              <td><code>{pr.imageTag}</code></td>
              <td>{pr.requestedBy} <span className="muted">{fmtTime(pr.requestedAt)}</span></td>
              <td><Badge value={pr.status} /></td>
              <td>
                {pr.status === 'PENDING' && me.approver && pr.requestedBy !== me.login && <>
                  <button className="btn primary small" onClick={() => decide(pr.id, 'approve')}>Approve &amp; deploy</button>{' '}
                  <button className="btn danger small" onClick={() => decide(pr.id, 'reject')}>Reject</button>
                </>}
              </td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan="8" className="muted">Nothing here.</td></tr>}
        </tbody>
      </table>
    </div>
  )
}
