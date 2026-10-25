const COLORS = {
  SUCCEEDED: 'green', Synced: 'green', Healthy: 'green', DONE: 'green',
  RUNNING: 'blue', Progressing: 'blue', PENDING: 'amber', WAITING: 'amber', OutOfSync: 'amber',
  FAILED: 'red', Degraded: 'red', REJECTED: 'red', Missing: 'red',
  CANCELLED: 'gray', SKIPPED: 'gray', Unknown: 'gray', 'RISK ACCEPTED': 'orange',
}

export default function Badge({ value }) {
  if (!value) return <span className="badge gray">—</span>
  return <span className={`badge ${COLORS[value] || 'gray'}`}>{value}</span>
}

export function Vulns({ run }) {
  if (run.vulnCritical == null && run.vulnHigh == null) return <span className="muted">—</span>
  return (
    <span className="vulns">
      <span className="badge red" title="critical">C {run.vulnCritical ?? 0}</span>
      <span className="badge orange" title="high">H {run.vulnHigh ?? 0}</span>
      <span className="badge amber" title="medium">M {run.vulnMedium ?? 0}</span>
      <span className="badge gray" title="low">L {run.vulnLow ?? 0}</span>
    </span>
  )
}
