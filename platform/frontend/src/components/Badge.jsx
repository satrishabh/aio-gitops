export const COLORS = {
  SUCCEEDED: 'green', Synced: 'green', Healthy: 'green', DONE: 'green',
  RUNNING: 'blue', Progressing: 'blue', PENDING: 'amber', WAITING: 'amber', OutOfSync: 'amber', Suspended: 'amber',
  FAILED: 'red', Degraded: 'red', REJECTED: 'red', Missing: 'red',
  CANCELLED: 'gray', SKIPPED: 'gray', Unknown: 'gray', 'RISK ACCEPTED': 'orange', 'NOT DEPLOYED': 'gray',
}
export const colorOf = v => COLORS[v] || 'gray'

const LABELS = { SUCCEEDED: 'Succeeded', RUNNING: 'Running', PENDING: 'Pending', FAILED: 'Failed', CANCELLED: 'Cancelled',
  WAITING: 'Waiting', SKIPPED: 'Skipped', DONE: 'Done', REJECTED: 'Rejected', OutOfSync: 'Out of sync',
  'RISK ACCEPTED': 'Risk accepted', 'NOT DEPLOYED': 'Not deployed' }

// The dot is decoration; the text label carries the meaning, so colour is never the only signal.
export default function Badge({ value, label, quiet }) {
  if (!value) return <span className="badge gray quiet" aria-label="No status">—</span>
  return <span className={`badge ${colorOf(value)}${quiet ? ' quiet' : ''}`}>{label || LABELS[value] || value}</span>
}

export function Vulns({ run }) {
  if (run.vulnCritical == null && run.vulnHigh == null) return <span className="muted" aria-label="No scan">—</span>
  const v = (cls, n, t) => <span className={`vuln ${cls}${!n ? ' zero' : ''}`} title={`${n ?? 0} ${t}`} aria-hidden="true">{cls.toUpperCase()}{n ?? 0}</span>
  const summary = `Vulnerabilities: ${run.vulnCritical ?? 0} critical, ${run.vulnHigh ?? 0} high, ${run.vulnMedium ?? 0} medium, ${run.vulnLow ?? 0} low`
  return (
    <span className="vulns" role="img" aria-label={summary}>
      {v('c', run.vulnCritical, 'critical')}{v('h', run.vulnHigh, 'high')}{v('m', run.vulnMedium, 'medium')}{v('l', run.vulnLow, 'low')}
    </span>
  )
}
