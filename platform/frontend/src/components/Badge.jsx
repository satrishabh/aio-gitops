export const COLORS = {
  SUCCEEDED: 'green', Synced: 'green', Healthy: 'green', DONE: 'green',
  RUNNING: 'blue', Progressing: 'blue', PENDING: 'amber', WAITING: 'amber', OutOfSync: 'amber', Suspended: 'amber',
  FAILED: 'red', Degraded: 'red', REJECTED: 'red', Missing: 'red',
  CANCELLED: 'gray', SKIPPED: 'gray', Unknown: 'gray', 'RISK ACCEPTED': 'orange', 'NOT DEPLOYED': 'gray',
}
export const colorOf = v => COLORS[v] || 'gray'

const LABELS = { SUCCEEDED: 'Succeeded', RUNNING: 'Running', PENDING: 'Pending', FAILED: 'Failed', CANCELLED: 'Cancelled',
  WAITING: 'Waiting', SKIPPED: 'Skipped', DONE: 'Done', REJECTED: 'Rejected' }

export default function Badge({ value, label }) {
  if (!value) return <span className="badge gray">—</span>
  return <span className={`badge ${colorOf(value)}`}>{label || LABELS[value] || value}</span>
}

export function Vulns({ run }) {
  if (run.vulnCritical == null && run.vulnHigh == null) return <span className="muted">—</span>
  const v = (cls, n, t) => <span className={`vuln ${cls}${!n ? ' zero' : ''}`} title={t}>{cls.toUpperCase()}{n ?? 0}</span>
  return (
    <span className="vulns">
      {v('c', run.vulnCritical, 'critical')}{v('h', run.vulnHigh, 'high')}{v('m', run.vulnMedium, 'medium')}{v('l', run.vulnLow, 'low')}
    </span>
  )
}
