import { useState } from 'react'
import { api, fmtTime } from '../api.js'
import Icon from '../components/Icon.jsx'
import { PageHead, Skeleton, ago } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const tone = a => a.includes('reject') || a.includes('delete') || a.includes('risk') || a.includes('fail') ? 'red'
  : a.startsWith('deploy') || a.includes('approve') ? 'green'
    : a.startsWith('run') ? 'blue' : 'violet'

export default function Audit() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/audit'), 15000)
  const [q, setQ] = useState('')

  if (!data) return <><PageHead title="Audit log" /><Skeleton h={420} /></>
  const shown = data.filter(e => !q || JSON.stringify(e).toLowerCase().includes(q.toLowerCase()))

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Audit log" sub="Every trigger, promotion, approval, deployment and override — newest first.">
        <div className="search" style={{ width: 300 }}>
          <Icon name="search" size={15} /><input placeholder="Filter by actor, action, project…" value={q} onChange={e => setQ(e.target.value)} />
        </div>
      </PageHead>
      <div className="card flush">
        <table>
          <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
          <tbody>
            {shown.map(e => (
              <tr key={e.id}>
                <td title={fmtTime(e.createdAt)} style={{ whiteSpace: 'nowrap' }}>{ago(e.createdAt)}</td>
                <td><b>{e.actor}</b></td>
                <td><span className={`badge ${tone(e.action) === 'violet' ? 'blue' : tone(e.action)}`}>{e.action}</span></td>
                <td>{e.target ? <code>{e.target}</code> : '—'}</td>
                <td className="muted" style={{ maxWidth: 460 }}>{e.details || ''}</td>
              </tr>
            ))}
            {shown.length === 0 && <tr><td colSpan="5" className="empty">No matching events.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  )
}
