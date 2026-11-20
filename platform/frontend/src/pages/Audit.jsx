import { useState } from 'react'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, SkeletonTable, ago } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const tone = a => a.includes('reject') || a.includes('delete') || a.includes('risk') || a.includes('fail') ? 'red'
  : a.startsWith('deploy') || a.includes('approve') ? 'green'
    : a.startsWith('run') ? 'blue' : 'violet'

const SUB = 'Every trigger, promotion, approval, deployment and override — newest first.'

export default function Audit() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/audit'), 15000)
  const [q, setQ] = useState('')

  if (error && !data) return <><Stale stale={stale} error={error} onRetry={refresh} /><PageHead title="Audit log" sub={SUB} /></>
  if (!data) return <><PageHead title="Audit log" sub={SUB} /><SkeletonTable rows={8} cols={5} label="Loading audit log" /></>
  const shown = data.filter(e => !q || JSON.stringify(e).toLowerCase().includes(q.toLowerCase()))

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Audit log" sub={SUB}>
        <div className="search page-search wide">
          <Icon name="search" size={15} /><input type="search" aria-label="Filter audit log" placeholder="Filter by actor, action, project…" value={q} onChange={e => setQ(e.target.value)} />
        </div>
      </PageHead>
      {data.length === 0 ? (
        <div className="card"><Empty icon="activity" title="No audit events yet">
          Triggers, promotions, approvals and deployments are recorded here as they happen.
        </Empty></div>
      ) : shown.length === 0 ? (
        <div className="card"><Empty icon="search" title="No matching events" action={<button className="btn" onClick={() => setQ('')}>Clear filter</button>}>
          Nothing in the log matches “{q}”.
        </Empty></div>
      ) : (
        <div className="card flush">
          <table className="stack-table audit-table">
            <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
            <tbody>
              {shown.map(e => (
                <tr key={e.id}>
                  <td data-label="When" title={fmtTime(e.createdAt)} className="nowrap">{ago(e.createdAt)}</td>
                  <td data-label="Actor"><b>{e.actor}</b></td>
                  {/* Action tags are labels, not live states: quiet (no dot), the words carry the meaning */}
                  <td className="stack-title"><Badge tone={tone(e.action) === 'violet' ? 'blue' : tone(e.action)} label={e.action} quiet /></td>
                  <td data-label="Target">{e.target ? <code>{e.target}</code> : '—'}</td>
                  <td data-label="Details" className={`muted audit-details${e.details ? '' : ' stack-empty'}`}>{e.details || ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
