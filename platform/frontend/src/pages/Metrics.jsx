import { useState } from 'react'
import { api } from '../api.js'
import { Stale, usePoll } from '../hooks.jsx'

const fmt = (v, suffix = '') => (v === null || v === undefined ? '—' : `${v}${suffix}`)
const pct = v => (v === null || v === undefined ? '—' : `${Math.round(v * 100)}%`)
const hrs = v => (v === null || v === undefined ? '—' : v < 1 ? `${Math.round(v * 60)} min` : `${v.toFixed(1)} h`)

export default function Metrics() {
  const [days, setDays] = useState(30)
  const { data, error, stale, refresh } = usePoll(() => api(`/api/metrics?days=${days}`), 60000, [days])

  if (!data) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  const o = data.overall

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <div className="card">
        <div className="head">
          <h2>Delivery metrics (DORA)</h2>
          <select value={days} onChange={e => setDays(Number(e.target.value))} style={{ width: 160 }}>
            <option value={7}>Last 7 days</option>
            <option value={30}>Last 30 days</option>
            <option value={90}>Last 90 days</option>
          </select>
        </div>
      </div>
      <div className="metrics">
        <Metric label="Prod deploys / day" value={fmt(o.prodDeploysPerDay)} sub={`${o.prodDeploys} prod · ${o.devDeploys} dev`} />
        <Metric label="Lead time (CI start → prod, median)" value={hrs(o.leadTimeHoursMedian)} />
        <Metric label="Change failure rate (prod rollbacks)" value={pct(o.changeFailureRate)} sub={`${o.prodRollbacks} rollbacks`} />
        <Metric label="Time to restore (red → green build)" value={hrs(o.timeToRestoreHoursMean)} />
        <Metric label="CI success rate" value={pct(o.ciSuccessRate)} sub={`${o.ciRuns} runs`} />
        <Metric label="CI duration (mean)" value={fmt(o.ciDurationMinutesMean, ' min')} />
      </div>
      <div className="card">
        <h3>Per project</h3>
        <table>
          <thead><tr><th>Project</th><th>Prod deploys</th><th>Dev deploys</th><th>Lead time</th><th>Change failure</th><th>Time to restore</th><th>CI success</th><th>CI runs</th></tr></thead>
          <tbody>
            {Object.entries(data.projects).map(([name, m]) => (
              <tr key={name}>
                <td>{name}</td>
                <td>{m.prodDeploys}</td>
                <td>{m.devDeploys}</td>
                <td>{hrs(m.leadTimeHoursMedian)}</td>
                <td>{pct(m.changeFailureRate)}</td>
                <td>{hrs(m.timeToRestoreHoursMean)}</td>
                <td>{pct(m.ciSuccessRate)}</td>
                <td>{m.ciRuns}</td>
              </tr>
            ))}
            {Object.keys(data.projects).length === 0 && <tr><td colSpan="8" className="muted">No projects yet.</td></tr>}
          </tbody>
        </table>
        <p className="muted">
          Lead time pairs each prod deployment with the CI run that built its image. Time to restore is pipeline
          recovery (first failing build → next green build); the platform has no incident data for production MTTR.
        </p>
      </div>
    </>
  )
}

function Metric({ label, value, sub }) {
  return (
    <div className="metric">
      <div className="value">{value}</div>
      <div className="label">{label}</div>
      {sub && <div className="label">{sub}</div>}
    </div>
  )
}
