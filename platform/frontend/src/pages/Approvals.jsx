import { useEffect, useState } from 'react'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

export default function Approvals({ me }) {
  const [projects, setProjects] = useState({})
  const [filter, setFilter] = useState('PENDING')
  const { data, error, stale, refresh } = usePoll(
    () => api(`/api/promotions${filter ? `?status=${filter}` : ''}`), 10000, [filter])
  const { busy, run } = useAction(refresh)
  const items = data || []

  useEffect(() => {
    api('/api/projects').then(list => setProjects(Object.fromEntries(list.map(x => [x.project.id, x.project.name])))).catch(() => {})
  }, [])

  const canDecide = pr => pr.status === 'PENDING' && me.approver && (pr.requestedBy !== me.login || me.canSelfApprove)

  function decide(pr, action) {
    let body
    if (action === 'reject') {
      const reason = prompt('Reason for rejecting?')
      if (reason === null) return
      body = { reason }
    }
    run(`${action}-${pr.id}`, () => api(`/api/promotions/${pr.id}/${action}`, { method: 'POST', body }),
      action === 'approve' ? `Approved — ${pr.toEnv} is deploying ${pr.imageTag}` : 'Rejected')
  }

  return (
    <div className="card">
      <Stale stale={stale} error={error} onRetry={refresh} />
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
        {me.approver && !me.canSelfApprove && <span className="muted">Four-eyes rule: someone else must approve your own requests.</span>}
      </div>
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
              <td><Badge value={pr.status} /> {pr.message && <span className="muted">{pr.message}</span>}</td>
              <td>
                {canDecide(pr) && <>
                  <button className={`btn primary small${busy === `approve-${pr.id}` ? ' busy' : ''}`} disabled={!!busy}
                          onClick={() => decide(pr, 'approve')}>Approve &amp; deploy</button>{' '}
                  <button className={`btn danger small${busy === `reject-${pr.id}` ? ' busy' : ''}`} disabled={!!busy}
                          onClick={() => decide(pr, 'reject')}>Reject</button>
                </>}
              </td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan="8" className="muted">{data ? 'Nothing here.' : 'Loading…'}</td></tr>}
        </tbody>
      </table>
    </div>
  )
}
