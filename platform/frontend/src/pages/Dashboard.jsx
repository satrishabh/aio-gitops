import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, BarChart, Empty, Kpi, PageHead, Ring, Skeleton, ago, envTone, isStopped } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']
const SERIES = [
  { key: 'succeeded', color: 'var(--green)', label: 'CI succeeded' },
  { key: 'failed', color: 'var(--red)', label: 'CI failed' },
  { key: 'deploys', color: 'var(--accent)', label: 'Deployments' },
]

const FEED = {
  'run.trigger': ['play', 'blue'], 'run.cancel': ['stop', 'gray'], 'run.accept_risk': ['bug', 'red'],
  'promotion.request': ['rocket', 'violet'], 'promotion.approve': ['check', 'green'], 'promotion.self_approve': ['check', 'green'],
  'promotion.reject': ['x', 'red'], 'rollback.request': ['undo', 'violet'], 'project.create': ['plus', 'violet'],
  'project.delete': ['trash', 'red'], 'project.settings': ['settings', 'gray'],
}
const feedIcon = a => FEED[a] || (a.startsWith('deploy.') ? ['rocket', 'green'] : ['zap', 'gray'])

export default function Dashboard({ me }) {
  const { data, error, stale, refresh } = usePoll(async () => {
    const [projects, metrics, audit, pending] = await Promise.all([
      api('/api/projects'), api('/api/metrics?days=14'), api('/api/audit'), api('/api/promotions?status=PENDING'),
    ])
    return { projects, metrics, audit: audit.slice(0, 12), pending }
  }, 10000)

  const hour = new Date().getHours()
  const greet = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'

  if (!data) return <>
    <PageHead title={`${greet}, ${me.name?.split(' ')[0] || me.login}`} sub="Loading your delivery pipeline…" />
    <div className="grid cols-4"><Skeleton h={104} /><Skeleton h={104} /><Skeleton h={104} /><Skeleton h={104} /></div>
    <Skeleton h={300} />
  </>

  const { projects, metrics, audit, pending } = data
  const o = metrics.overall
  const running = projects.filter(p => ['RUNNING', 'PENDING'].includes(p.lastRun?.status)).length
  const unhealthy = projects.flatMap(p => p.environments).filter(e => envTone(e) === 'red').length

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title={`${greet}, ${me.name?.split(' ')[0] || me.login}`}
                sub="Everything flowing from GitHub through Tekton CI and Argo CD, in one place.">
        <Link to="/new" className="btn primary"><Icon name="plus" size={15} />New project</Link>
      </PageHead>

      <div className="grid cols-4" style={{ marginBottom: 18 }}>
        <Kpi icon="grid" label="Projects" value={projects.length} sub={`${running} CI run${running === 1 ? '' : 's'} in progress`} />
        <Kpi icon="check" tone="green" label="CI success (14d)" value={o.ciSuccessRate == null ? '—' : `${Math.round(o.ciSuccessRate * 100)}%`}
             sub={`${o.ciRuns} runs · avg ${o.ciDurationMinutesMean ?? '—'} min`} />
        <Kpi icon="rocket" tone="cyan" label="Deployments (14d)" value={o.prodDeploys + o.devDeploys}
             sub={`${o.prodDeploys} to prod · ${o.devDeploys} to dev`} />
        <Kpi icon="shield" tone={pending.length ? 'amber' : ''} label="Awaiting approval" value={pending.length}
             sub={unhealthy ? `${unhealthy} environment${unhealthy === 1 ? '' : 's'} unhealthy` : 'All environments healthy'} />
      </div>

      <div className="grid span">
        <div className="card">
          <div className="card-head">
            <h3>Delivery activity</h3>
            <div className="legend">{SERIES.map(s => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</div>
          </div>
          <BarChart data={metrics.daily} series={SERIES} label="CI runs per day" />
        </div>
        <div className="card">
          <div className="card-head"><h3>Health at a glance</h3></div>
          <div style={{ display: 'flex', justifyContent: 'space-around', alignItems: 'center', padding: '10px 0 18px' }}>
            <div style={{ textAlign: 'center' }}><Ring value={o.ciSuccessRate} size={92} /><div className="muted">CI success</div></div>
            <div style={{ textAlign: 'center' }}>
              <Ring value={o.changeFailureRate == null ? null : 1 - o.changeFailureRate} size={92} />
              <div className="muted">Change success</div>
            </div>
          </div>
          <div className="facts">
            <div><span>Lead time</span>{o.leadTimeHoursMedian == null ? '—' : `${o.leadTimeHoursMedian} h`}</div>
            <div><span>Time to restore</span>{o.timeToRestoreHoursMean == null ? '—' : `${o.timeToRestoreHoursMean} h`}</div>
          </div>
          <Link to="/metrics" className="btn small" style={{ marginTop: 12 }}>Open DORA metrics <Icon name="arrow" size={13} /></Link>
        </div>
      </div>

      <div className="grid span">
        <div className="card flush">
          <div className="card-head"><h3>Environments</h3><Link to="/projects" className="btn small ghost">All projects</Link></div>
          {projects.length === 0 ? <Empty icon="rocket" title="No projects yet">
            <Link to="/new">Connect your first GitHub repository</Link> to see it flow through dev, staging and prod.
          </Empty> : (
            <table style={{ marginTop: 12 }}>
              <thead><tr><th>Project</th><th>Last CI</th>{ENVS.map(e => <th key={e}>{e}</th>)}</tr></thead>
              <tbody>
                {projects.map(({ project: p, lastRun, environments }) => (
                  <tr key={p.id}>
                    <td><Link to={`/projects/${p.id}`} style={{ display: 'flex', alignItems: 'center', gap: 10, color: 'var(--text)' }}>
                      <Avatar name={p.name} size={28} /><b>{p.name}</b></Link></td>
                    <td>{lastRun?.id ? <Link to={`/runs/${lastRun.id}`}><Badge value={lastRun.status} /></Link> : <span className="muted">never</span>}</td>
                    {ENVS.map(env => {
                      const s = environments.find(e => e.environment === env)
                      const tone = envTone(s)
                      return (
                        <td key={env}>
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                            <span className={`dot ${tone}`} />
                            <code>{s?.replicas === 0 ? (isStopped(s) ? 'scaled down' : 'not deployed') : (s?.desiredTag || s?.imageTag || '—').slice(0, 12)}</code>
                          </span>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="card">
          <div className="card-head"><h3>Activity</h3><Link to="/audit" className="btn small ghost">Audit log</Link></div>
          <ul className="feed">
            {audit.map(e => {
              const [ico, tone] = feedIcon(e.action)
              return (
                <li key={e.id}>
                  <div className={`ico ${tone}`}><Icon name={ico} size={15} /></div>
                  <div className="body">
                    <div><b>{e.actor}</b> <span className="muted">{e.action.replace(/[._]/g, ' ')}</span> {e.target && <code>{e.target}</code>}</div>
                    <div className="when">{ago(e.createdAt)}{e.details ? ` · ${e.details.slice(0, 60)}` : ''}</div>
                  </div>
                </li>
              )
            })}
            {audit.length === 0 && <li className="muted">No activity yet.</li>}
          </ul>
        </div>
      </div>
    </>
  )
}
