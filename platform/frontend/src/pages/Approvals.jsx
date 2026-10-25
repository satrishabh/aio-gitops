import { useEffect, useState } from 'react'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, PageHead, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

const FILTERS = [['PENDING', 'Pending'], ['', 'All'], ['DONE', 'Approved'], ['REJECTED', 'Rejected'], ['FAILED', 'Failed']]

export default function Approvals({ me }) {
  const [projects, setProjects] = useState({})
  const [filter, setFilter] = useState('PENDING')
  const { data, error, stale, refresh } = usePoll(() => api(`/api/promotions${filter ? `?status=${filter}` : ''}`), 10000, [filter])
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
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Production approvals"
                sub={!me.approver ? 'You can view requests but are not an approver.'
                  : me.canSelfApprove ? 'You are an approver and may approve your own requests (audited).'
                    : 'Four-eyes rule: someone else must approve your own requests.'} />
      <div className="tabs">
        {FILTERS.map(([v, l]) => <button key={l} className={filter === v ? 'active' : ''} onClick={() => setFilter(v)}>{l}</button>)}
      </div>

      {data && items.length === 0 && <div className="card"><Empty icon="shield" title={filter === 'PENDING' ? 'Nothing waiting for you' : 'Nothing here'}>
        {filter === 'PENDING' ? 'Prod promotions and rollbacks appear here for approval.' : 'Try another filter.'}</Empty></div>}

      <div className="grid cols-2">
        {items.map(pr => {
          const name = projects[pr.projectId] || `project ${pr.projectId}`
          return (
            <div key={pr.id} className="card" style={{ margin: 0 }}>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                <Avatar name={name} size={40} />
                <div style={{ flex: 1 }}>
                  <b>{name}</b> <span className="muted">#{pr.id}</span>
                  <div className="muted" style={{ fontSize: '0.8rem' }}>
                    <Icon name={pr.kind === 'ROLLBACK' ? 'undo' : 'rocket'} size={12} /> {pr.kind.toLowerCase()} requested by {pr.requestedBy} · {ago(pr.requestedAt)}
                  </div>
                </div>
                <Badge value={pr.status} />
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '16px 0', padding: '12px 14px', borderRadius: 12, background: 'rgba(10,13,20,0.45)', border: '1px solid var(--line)' }}>
                <span className="badge gray">{pr.fromEnv || 'history'}</span>
                <Icon name="arrow" size={16} />
                <span className="badge amber"><Icon name="lock" size={11} />{pr.toEnv}</span>
                <code style={{ marginLeft: 'auto' }}>{pr.imageTag}</code>
              </div>
              {pr.message && <p className="muted" style={{ margin: '0 0 12px' }}>{pr.message}</p>}
              {pr.decidedBy && <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>Decided by {pr.decidedBy} · {ago(pr.decidedAt)}</p>}
              {canDecide(pr) && (
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className={`btn primary${busy === `approve-${pr.id}` ? ' busy' : ''}`} disabled={!!busy} onClick={() => decide(pr, 'approve')}>
                    <Icon name="check" size={14} />Approve &amp; deploy</button>
                  <button className={`btn danger${busy === `reject-${pr.id}` ? ' busy' : ''}`} disabled={!!busy} onClick={() => decide(pr, 'reject')}>
                    <Icon name="x" size={14} />Reject</button>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </>
  )
}
