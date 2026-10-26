import { useState } from 'react'
import { api } from '../api.js'
import { Avatar, BarChart, Kpi, PageHead, Ring, Skeleton } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const pct = v => (v == null ? '—' : `${Math.round(v * 100)}%`)
const hrs = v => (v == null ? '—' : v < 1 ? `${Math.round(v * 60)} min` : `${v.toFixed(1)} h`)
const SERIES = [
  { key: 'succeeded', color: 'var(--green)', label: 'CI succeeded' },
  { key: 'failed', color: 'var(--red)', label: 'CI failed' },
  { key: 'deploys', color: 'var(--accent)', label: 'Deployments' },
]

/** DORA performance band (per the State of DevOps thresholds) for a quick qualitative read. */
function band(o) {
  const perDay = o.prodDeploysPerDay
  if (!o.prodDeploys) return ['No prod deploys yet', 'gray']
  if (perDay >= 1 && (o.leadTimeHoursMedian ?? 99) < 24 && (o.changeFailureRate ?? 1) <= 0.15) return ['Elite', 'green']
  if (perDay >= 1 / 7) return ['High', 'blue']
  if (perDay >= 1 / 30) return ['Medium', 'amber']
  return ['Low', 'red']
}

export default function Metrics() {
  const [days, setDays] = useState(30)
  const { data, error, stale, refresh } = usePoll(() => api(`/api/metrics?days=${days}`), 60000, [days])

  const head = (
    <PageHead title="Delivery metrics" sub="DORA four keys computed from your CI runs, Argo CD deployments and rollbacks.">
      <select value={days} onChange={e => setDays(Number(e.target.value))} style={{ width: 170, marginTop: 0 }}>
        <option value={7}>Last 7 days</option><option value={30}>Last 30 days</option><option value={90}>Last 90 days</option>
      </select>
    </PageHead>
  )
  if (!data) return <>{head}<div className="grid cols-4"><Skeleton h={110} /><Skeleton h={110} /><Skeleton h={110} /><Skeleton h={110} /></div><Skeleton h={260} /></>
  const o = data.overall
  const [bandLabel, bandTone] = band(o)

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      {head}
      <div className="grid cols-4" style={{ marginBottom: 18 }}>
        <Kpi icon="rocket" tone="cyan" label="Deployment frequency" value={`${o.prodDeploysPerDay}/day`} sub={`${o.prodDeploys} prod · ${o.devDeploys} dev deploys`} />
        <Kpi icon="clock" label="Lead time for changes" value={hrs(o.leadTimeHoursMedian)} sub="median, CI start → prod" />
        <Kpi icon="undo" tone={o.changeFailureRate > 0.15 ? 'red' : 'green'} label="Change failure rate" value={pct(o.changeFailureRate)} sub={`${o.prodRollbacks} prod rollback${o.prodRollbacks === 1 ? '' : 's'}`} />
        <Kpi icon="refresh" tone="amber" label="Time to restore" value={hrs(o.timeToRestoreHoursMean)} sub="mean, red build → green build" />
      </div>

      <div className="grid span">
        <div className="card">
          <div className="card-head">
            <h3>Daily activity</h3>
            <div className="legend">{SERIES.map(s => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</div>
          </div>
          <BarChart data={data.daily} series={SERIES} height={240} label="CI runs per day" />
        </div>
        <div className="card">
          <div className="card-head"><h3>Performance</h3><span className={`badge ${bandTone}`}>{bandLabel}</span></div>
          <div style={{ display: 'flex', justifyContent: 'space-around', padding: '6px 0 16px' }}>
            <div style={{ textAlign: 'center' }}><Ring value={o.ciSuccessRate} size={96} /><div className="muted">CI success</div></div>
            <div style={{ textAlign: 'center' }}><Ring value={o.changeFailureRate == null ? null : 1 - o.changeFailureRate} size={96} /><div className="muted">Change success</div></div>
          </div>
          <div className="facts">
            <div><span>CI runs</span>{o.ciRuns}</div>
            <div><span>Avg CI duration</span>{o.ciDurationMinutesMean == null ? '—' : `${o.ciDurationMinutesMean} min`}</div>
          </div>
        </div>
      </div>

      <div className="card flush">
        <div className="card-head"><h3>By project</h3></div>
        <table style={{ marginTop: 12 }}>
          <thead><tr><th>Project</th><th>Prod deploys</th><th>Dev deploys</th><th>Lead time</th><th>Change failure</th><th>Time to restore</th><th style={{ width: 200 }}>CI success</th></tr></thead>
          <tbody>
            {Object.entries(data.projects).map(([name, m]) => (
              <tr key={name}>
                <td><span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}><Avatar name={name} size={26} /><b>{name}</b></span></td>
                <td>{m.prodDeploys}</td><td>{m.devDeploys}</td>
                <td>{hrs(m.leadTimeHoursMedian)}</td><td>{pct(m.changeFailureRate)}</td><td>{hrs(m.timeToRestoreHoursMean)}</td>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <div className="bar-track" style={{ flex: 1 }}><div className="bar-fill" style={{ width: `${Math.round((m.ciSuccessRate ?? 0) * 100)}%` }} /></div>
                    <span className="mono">{pct(m.ciSuccessRate)}</span>
                  </div>
                </td>
              </tr>
            ))}
            {Object.keys(data.projects).length === 0 && <tr><td colSpan="7" className="empty">No projects yet.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="muted" style={{ fontSize: '0.8rem' }}>
        Lead time pairs each prod deployment with the CI run that built its image. Time to restore is pipeline recovery
        (first failing build → next green build); the platform has no incident data for production MTTR.
      </p>
    </>
  )
}
