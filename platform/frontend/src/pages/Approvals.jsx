import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, PageHead, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

const FILTERS = [['PENDING', 'Pending'], ['', 'All'], ['DONE', 'Approved'], ['REJECTED', 'Rejected'], ['FAILED', 'Failed']]

/** The tag part of an image reference (repo/name:tag). */
const tagOf = url => (url && url.includes(':') ? url.split(':').pop() : null)

export default function Approvals({ me }) {
  const [projects, setProjects] = useState({})
  const [scans, setScans] = useState({})
  const [filter, setFilter] = useState('PENDING')
  const { data, error, stale, refresh } = usePoll(() => api(`/api/promotions${filter ? `?status=${filter}` : ''}`), 10000, [filter])
  const { busy, run } = useAction(refresh)
  const items = data || []

  useEffect(() => {
    api('/api/projects').then(list => setProjects(Object.fromEntries(list.map(x => [x.project.id, x])))).catch(() => {})
    // Scan results of recent builds, matched to a request by project and image tag (shown when the build is still in the list)
    api('/api/runs?limit=200').then(list => {
      const out = {}
      for (const { run: r } of list) {
        const tag = tagOf(r.imageUrl)
        if (tag && !out[`${r.projectId}:${tag}`]) out[`${r.projectId}:${tag}`] = r
      }
      setScans(out)
    }).catch(() => {})
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
      <div className="tabs" role="group" aria-label="Filter by status">
        {FILTERS.map(([v, l]) => <button key={l} className={filter === v ? 'active' : ''} aria-pressed={filter === v} onClick={() => setFilter(v)}>{l}</button>)}
      </div>

      {data && items.length === 0 && <div className="card"><Empty icon="shield" title={filter === 'PENDING' ? 'Nothing waiting for you' : 'Nothing here'}>
        {filter === 'PENDING' ? 'Prod promotions and rollbacks appear here for approval.' : 'Try another filter.'}</Empty></div>}

      <ul className="fl-approvals">
        {items.map(pr => {
          const entry = projects[pr.projectId]
          const name = entry?.project.name || `project ${pr.projectId}`
          const target = entry?.environments?.find(e => e.environment === pr.toEnv)
          const live = target && target.desiredTag && target.desiredTag !== '0.0.0' ? target.desiredTag : null
          const scan = scans[`${pr.projectId}:${pr.imageTag}`]
          const rollback = pr.kind === 'ROLLBACK'
          const own = pr.requestedBy === me.login
          return (
            <li key={pr.id} className={`fl-approval status-${pr.status}`}>
              <div className="fl-approval-head">
                <Avatar name={name} size={40} />
                <div className="fl-approval-who">
                  <b><Link to={`/projects/${pr.projectId}`}>{name}</Link> <span className="muted">#{pr.id}</span></b>
                  <span className="fl-kind"><Icon name={rollback ? 'undo' : 'rocket'} size={13} />{rollback ? 'Rollback' : 'Promotion'} to {pr.toEnv}</span>
                </div>
                <Badge value={pr.status} />
              </div>

              <div className="fl-change" aria-label={`Changes ${pr.toEnv} from ${live || 'nothing deployed'} to ${pr.imageTag}`}>
                <div className="fl-change-side">
                  <span className="fl-change-label">{pr.toEnv} runs now</span>
                  {live ? <code className="fl-tag old">{live}</code> : <span className="fl-tag-none">Not deployed yet</span>}
                </div>
                <Icon name="arrow" size={18} className="fl-change-arrow" />
                <div className="fl-change-side">
                  <span className="fl-change-label">{pr.status === 'DONE' ? 'Deployed' : 'Would deploy'}</span>
                  <code className="fl-tag new">{pr.imageTag}</code>
                </div>
              </div>

              <dl className="fl-approval-facts">
                <div><dt>Environment</dt><dd><span className="badge amber"><Icon name="lock" size={11} />{pr.toEnv}</span>{pr.fromEnv && <span className="muted"> from {pr.fromEnv}</span>}</dd></div>
                <div><dt>Requested by</dt><dd>{pr.requestedBy}{own && <span className="muted"> (you)</span>} · <span title={new Date(pr.requestedAt).toLocaleString()}>{ago(pr.requestedAt)}</span></dd></div>
                <div><dt>Scan</dt><dd>{scan ? <><Vulns run={scan} /> <Link to={`/runs/${scan.id}`} className="fl-small">run #{scan.id}</Link></> : <span className="muted">No scan result found for this tag</span>}</dd></div>
              </dl>

              {pr.message && <p className="fl-approval-msg">{pr.message}</p>}
              {pr.decidedBy && <p className="muted fl-small" style={{ margin: 0 }}>Decided by {pr.decidedBy} · {ago(pr.decidedAt)}</p>}
              {canDecide(pr) && (
                <div className="fl-approval-actions">
                  <button className={`btn primary${busy === `approve-${pr.id}` ? ' busy' : ''}`} disabled={!!busy} onClick={() => decide(pr, 'approve')}>
                    <Icon name="check" size={14} />Approve &amp; deploy</button>
                  <button className={`btn danger${busy === `reject-${pr.id}` ? ' busy' : ''}`} disabled={!!busy} onClick={() => decide(pr, 'reject')}>
                    <Icon name="x" size={14} />Reject</button>
                </div>
              )}
              {pr.status === 'PENDING' && me.approver && !canDecide(pr) && <p className="muted fl-small" style={{ margin: 0 }}>Your own request: another approver must decide.</p>}
            </li>
          )
        })}
      </ul>
    </>
  )
}
