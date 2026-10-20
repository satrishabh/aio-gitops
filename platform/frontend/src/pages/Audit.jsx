import { useEffect, useState } from 'react'
import { api, fmtTime } from '../api.js'

export default function Audit() {
  const [items, setItems] = useState([])
  const [q, setQ] = useState('')
  useEffect(() => { api('/api/audit').then(setItems) }, [])

  const shown = items.filter(e => !q || JSON.stringify(e).toLowerCase().includes(q.toLowerCase()))
  return (
    <div className="card">
      <h2>Audit log</h2>
      <input placeholder="Filter…" value={q} onChange={e => setQ(e.target.value)} />
      <table>
        <thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr></thead>
        <tbody>
          {shown.map(e => (
            <tr key={e.id}>
              <td>{fmtTime(e.createdAt)}</td>
              <td>{e.actor}</td>
              <td><code>{e.action}</code></td>
              <td>{e.target || '—'}</td>
              <td className="muted">{e.details || ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
