import { useMemo, useState } from 'react'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { Avatar, BarChart, PageHead, Ring, Skeleton, Sparkline } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

/*
 * Delivery metrics, redesign phase 3: the four DORA keys as tiles with a per-day sparkline,
 * the performance band up front, the daily chart, and a sortable by-project table.
 */

const pct = v => (v == null ? '—' : `${Math.round(v * 100)}%`)
const hrs = v => (v == null ? '—' : v < 1 ? `${Math.round(v * 60)} min` : `${v.toFixed(1)} h`)
const PERIODS = [7, 14, 30, 90]
const PERIOD_KEY = 'aio-metrics-days'
const SERIES = [
  { key: 'succeeded', color: 'var(--ok-fill)', label: 'CI succeeded' },
  { key: 'failed', color: 'var(--bad-fill)', label: 'CI failed' },
  { key: 'deploys', color: 'var(--info-fill)', label: 'Deployments' },
]

/** DORA performance band (State of DevOps thresholds), with what it means in one line. */
function band(o) {
  const perDay = o.prodDeploysPerDay
  if (!o.prodDeploys) return ['No prod deploys yet', 'gray', 'Nothing reached prod in this period, so there is no band to show.']
  if (perDay >= 1 && (o.leadTimeHoursMedian ?? 99) < 24 && (o.changeFailureRate ?? 1) <= 0.15)
    return ['Elite', 'green', 'Deploying to prod daily, with lead time under a day and at most 15% of changes rolled back.']
  if (perDay >= 1 / 7) return ['High', 'blue', 'Deploying to prod at least once a week. Elite needs daily deploys, lead time under a day and ≤ 15% change failure.']
  if (perDay >= 1 / 30) return ['Medium', 'amber', 'Deploying to prod at least once a month. High needs at least one prod deploy a week.']
  return ['Low', 'red', 'Deploying to prod less than once a month. Medium needs at least one prod deploy a month.']
}

function readPeriod() {
  try {
    const v = Number(localStorage.getItem(PERIOD_KEY))
    return PERIODS.includes(v) ? v : 30
  } catch { return 30 }
}

const COLUMNS = [
  { key: 'name', label: 'Project', get: ([name]) => name.toLowerCase() },
  { key: 'prodDeploys', label: 'Prod deploys', get: ([, m]) => m.prodDeploys, num: true },
  { key: 'devDeploys', label: 'Dev deploys', get: ([, m]) => m.devDeploys, num: true },
  { key: 'leadTimeHoursMedian', label: 'Lead time', get: ([, m]) => m.leadTimeHoursMedian, num: true },
  { key: 'changeFailureRate', label: 'Change failure', get: ([, m]) => m.changeFailureRate, num: true },
  { key: 'timeToRestoreHoursMean', label: 'Time to restore', get: ([, m]) => m.timeToRestoreHoursMean, num: true },
  { key: 'ciSuccessRate', label: 'CI success', get: ([, m]) => m.ciSuccessRate, num: true },
]

export default function Metrics() {
  const [days, setDays] = useState(readPeriod)
  const [sort, setSort] = useState({ key: 'prodDeploys', dir: 'desc' })
  const choosePeriod = d => {
    setDays(d)
    try { localStorage.setItem(PERIOD_KEY, String(d)) } catch { /* the choice just isn't remembered */ }
  }
  const { data, error, stale, refresh } = usePoll(() => api(`/api/metrics?days=${days}`), 60000, [days])

  const rows = useMemo(() => {
    if (!data) return []
    const col = COLUMNS.find(c => c.key === sort.key)
    const entries = Object.entries(data.projects)
    return entries.sort((a, b) => {
      const x = col.get(a), y = col.get(b)
      if (x == null && y == null) return 0
      if (x == null) return 1            // no data always sorts last
      if (y == null) return -1
      const c = x < y ? -1 : x > y ? 1 : 0
      return sort.dir === 'asc' ? c : -c
    })
  }, [data, sort])

  const head = (
    <PageHead title="Delivery metrics" sub="The four DORA keys, computed from your CI runs, Argo CD deployments and rollbacks.">
      <div className="segmented" role="group" aria-label="Period">
        {PERIODS.map(d => <button key={d} type="button" aria-pressed={d === days} onClick={() => choosePeriod(d)}>{d} days</button>)}
      </div>
    </PageHead>
  )
  if (error && !data) return <>{head}<Stale stale={stale} error={error} onRetry={refresh} /></>
  if (!data) return <>{head}<Skeleton h={120} /><div className="dora-grid"><Skeleton h={170} /><Skeleton h={170} /><Skeleton h={170} /><Skeleton h={170} /></div><Skeleton h={300} /></>

  const o = data.overall
  const daily = data.daily || []
  const [bandLabel, bandTone, bandText] = band(o)
  const series = key => daily.map(d => (d[key] === undefined ? null : d[key]))
  const prodPerDay = daily.map(d => d.deploysProd ?? null)

  const tiles = [
    { key: 'freq', icon: 'rocket', label: 'Deployment frequency', value: `${o.prodDeploysPerDay}/day`,
      sub: `${o.prodDeploys} prod · ${o.devDeploys} dev${o.stagingDeploys != null ? ` · ${o.stagingDeploys} staging` : ''}`,
      spark: prodPerDay, sparkLabel: 'prod deploys per day', better: 'higher is better', color: 'var(--info-fill)' },
    { key: 'lead', icon: 'clock', label: 'Lead time for changes', value: hrs(o.leadTimeHoursMedian),
      sub: 'median, CI start → prod', spark: series('leadTimeHours'), sparkLabel: 'median lead time of each day’s prod deploys, in hours',
      better: 'lower is better', color: 'var(--accent)' },
    { key: 'cfr', icon: 'undo', label: 'Change failure rate', value: pct(o.changeFailureRate), tone: o.changeFailureRate > 0.15 ? 'red' : '',
      sub: `${o.prodRollbacks} prod rollback${o.prodRollbacks === 1 ? '' : 's'}`, spark: series('rollbacks'), sparkLabel: 'prod rollbacks per day',
      better: 'lower is better', color: 'var(--bad-fill)' },
    { key: 'mttr', icon: 'refresh', label: 'Time to restore', value: hrs(o.timeToRestoreHoursMean),
      sub: 'mean, red build → green build', spark: series('restoreHours'), sparkLabel: 'mean recovery time of each day’s fixes, in hours',
      better: 'lower is better', color: 'var(--warn-fill)' },
  ]

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      {head}

      <section className={`dora-band band-${bandTone}`} aria-label="Performance band">
        <div className="dora-band-label"><span>DORA band</span><b>{bandLabel}</b></div>
        <p>{bandText}</p>
        <dl className="dora-band-facts">
          <div><dt>CI runs</dt><dd>{o.ciRuns}</dd></div>
          <div><dt>CI success</dt><dd>{pct(o.ciSuccessRate)}</dd></div>
          <div><dt>Avg CI duration</dt><dd>{o.ciDurationMinutesMean == null ? '—' : `${o.ciDurationMinutesMean} min`}</dd></div>
        </dl>
      </section>

      <div className="dora-grid">
        {tiles.map(t => (
          <section key={t.key} className={`dora-tile${t.tone ? ` tone-${t.tone}` : ''}`} aria-label={t.label}>
            <div className="dora-tile-label"><Icon name={t.icon} size={15} />{t.label}</div>
            <div className="dora-tile-value">{t.value}</div>
            <div className="dora-tile-sub">{t.sub}</div>
            <Sparkline values={t.spark} color={t.color} label={`${t.label}: ${t.sparkLabel}, ${daily.length} days`} />
            <div className="dora-tile-foot"><span>{daily.length} days</span><span>{t.better}</span></div>
          </section>
        ))}
      </div>

      <div className="grid span">
        <div className="card">
          <div className="card-head">
            <h3>Daily activity</h3>
            <div className="legend">{SERIES.map(s => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</div>
          </div>
          <BarChart data={daily} series={SERIES} height={240} label="CI runs and deployments per day" />
        </div>
        <div className="card">
          <div className="card-head"><h3>Success rates</h3></div>
          <div className="metrics-rings">
            <div><Ring value={o.ciSuccessRate} size={104} color="var(--ok-fill)" /><span>CI success</span></div>
            <div><Ring value={o.changeFailureRate == null ? null : 1 - o.changeFailureRate} size={104} color="var(--info-fill)" /><span>Change success</span></div>
          </div>
          <p className="muted metrics-note">Change success is 1 − change failure rate: prod deploys that were not rolled back.</p>
        </div>
      </div>

      <div className="card flush">
        <div className="card-head"><h3>By project</h3><span className="muted metrics-sort-hint">Select a column heading to sort</span></div>
        <div className="table-scroll">
          <table className="metrics-table">
            <thead>
              <tr>
                {COLUMNS.map(c => {
                  const active = sort.key === c.key
                  return (
                    <th key={c.key} aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'} className={c.num ? 'num' : ''}>
                      <button type="button" className={`sort${active ? ' active' : ''}`}
                              onClick={() => setSort(s => ({ key: c.key, dir: s.key === c.key && s.dir === 'desc' ? 'asc' : 'desc' }))}>
                        {c.label}<span className="sort-arrow" aria-hidden="true">{active ? (sort.dir === 'asc' ? '↑' : '↓') : '↕'}</span>
                      </button>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody>
              {rows.map(([name, m]) => (
                <tr key={name}>
                  <td><span className="metrics-project"><Avatar name={name} size={26} /><b>{name}</b></span></td>
                  <td className="num" data-label="Prod deploys">{m.prodDeploys}</td>
                  <td className="num" data-label="Dev deploys">{m.devDeploys}</td>
                  <td className="num" data-label="Lead time">{hrs(m.leadTimeHoursMedian)}</td>
                  <td className="num" data-label="Change failure">{pct(m.changeFailureRate)}</td>
                  <td className="num" data-label="Time to restore">{hrs(m.timeToRestoreHoursMean)}</td>
                  <td className="num" data-label="CI success">
                    <span className="metrics-bar">
                      <span className="bar-track"><span className="bar-fill" style={{ width: `${Math.round((m.ciSuccessRate ?? 0) * 100)}%` }} /></span>
                      <span className="mono">{pct(m.ciSuccessRate)}</span>
                    </span>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={COLUMNS.length} className="empty">No projects yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <p className="muted metrics-note">
        Lead time pairs each prod deployment with the CI run that built its image. Time to restore is pipeline recovery
        (first failing build → next green build); the platform has no incident data for production MTTR. Sparklines show one
        reading per day and leave gaps on days without one.
      </p>
    </>
  )
}
