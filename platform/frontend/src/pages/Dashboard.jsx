import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, Skeleton, ago, envTone, isStopped } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

/*
 * Overview (home), redesign phase 2: the delivery pipeline is the page.
 * Strip: commits built → CI runs → dev → staging → prod, each with one tick per day.
 * Below: projects with their dev → staging → prod track, and "Needs you" + activity on the side.
 */

const ENVS = ['dev', 'staging', 'prod']
const PERIODS = [7, 14, 30]
const PERIOD_KEY = 'aio-overview-days'
const PROJECT_ROWS = 6

const FEED = {
  'run.trigger': ['play', 'blue'], 'run.cancel': ['stop', 'gray'], 'run.accept_risk': ['bug', 'red'],
  'promotion.request': ['rocket', 'violet'], 'promotion.approve': ['check', 'green'], 'promotion.self_approve': ['check', 'green'],
  'promotion.reject': ['x', 'red'], 'rollback.request': ['undo', 'violet'], 'project.create': ['plus', 'violet'],
  'project.delete': ['trash', 'red'], 'project.settings': ['settings', 'gray'],
}
const feedIcon = a => FEED[a] || (a.startsWith('deploy.') ? ['rocket', 'green'] : ['zap', 'gray'])

/** Words for each environment colour, so state never rests on colour alone. */
const TONE_WORD = { green: 'healthy', blue: 'syncing', amber: 'out of sync', red: 'degraded', gray: 'off' }
const CI_WORD = {
  SUCCEEDED: ['CI passed', 'green'], FAILED: ['CI failed', 'red'], RUNNING: ['CI running', 'blue'], PENDING: ['CI queued', 'blue'],
  CANCELLED: ['CI cancelled', 'gray'],
}

function readPeriod() {
  try {
    const v = Number(localStorage.getItem(PERIOD_KEY))
    return PERIODS.includes(v) ? v : 14
  } catch { return 14 }
}
const pct = v => (v == null ? '—' : `${Math.round(v * 100)}%`)
const hrs = v => (v == null ? '—' : v < 1 ? `${Math.round(v * 60)} min` : `${v.toFixed(1)} h`)
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

/** One environment's display: tag (or why there is none) and its tone. */
function envStop(s) {
  const tone = envTone(s)
  const text = !s ? 'not set up' : s.replicas === 0 ? (isStopped(s) ? 'scaled down' : 'not deployed') : (s.desiredTag || s.imageTag || '—').slice(0, 12)
  return { tone, text }
}

/** Short health line for a pipeline stage: how many projects are unhealthy, scaled down or fine in that environment. */
function envSummary(projects, env) {
  const stops = projects.map(p => p.environments.find(e => e.environment === env))
  const bad = stops.filter(s => envTone(s) === 'red').length
  const off = stops.filter(s => s && s.replicas === 0).length
  const syncing = stops.filter(s => envTone(s) === 'blue').length
  if (bad) return [`${bad} degraded`, 'red']
  if (syncing) return [`${syncing} syncing`, 'blue']
  if (off) return [`${off} scaled down or not deployed`, 'gray']
  return [projects.length ? 'all healthy' : 'no projects yet', 'green']
}

function Ticks({ days, pick, label }) {
  return (
    <div className="ticks" role="img" aria-label={label}>
      {days.map(d => <span key={d.date} className={`tick ${pick(d)}`} title={d.date} />)}
    </div>
  )
}

export default function Dashboard({ me }) {
  const community = me.accessTier === 'community'
  const [days, setDays] = useState(readPeriod)
  const choosePeriod = d => {
    setDays(d)
    try { localStorage.setItem(PERIOD_KEY, String(d)) } catch { /* the choice just isn't remembered */ }
  }
  const { data, error, stale, refresh } = usePoll(async () => {
    const [projects, metrics, audit, pending] = await Promise.all([
      api('/api/projects'), api(`/api/metrics?days=${days}`),
      community ? Promise.resolve([]) : api('/api/audit'),
      community ? Promise.resolve([]) : api('/api/promotions?status=PENDING'),
    ])
    return { projects, metrics, audit: audit.slice(0, 6), pending }
  }, 10000, [days])

  const hour = new Date().getHours()
  const greet = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
  const title = `${greet}, ${me.name?.split(' ')[0] || me.login}`

  const head = (
    <div className="ov-head">
      <div>
        <h1>{title}</h1>
        <p>Your delivery pipeline over the last {days} days. <span className="live-mark">Live</span></p>
      </div>
      <div className="segmented" role="group" aria-label="Period">
        {PERIODS.map(d => (
          <button key={d} type="button" aria-pressed={d === days} onClick={() => choosePeriod(d)}>{d} days</button>
        ))}
      </div>
    </div>
  )

  if (error && !data) return <>{head}<Stale stale={stale} error={error} onRetry={refresh} /></>
  if (!data) return <>
    {head}
    <div className="ov-strip-skeleton"><Skeleton h={150} /></div>
    <div className="ov-columns"><div className="ov-main"><Skeleton h={76} count={4} /></div>{!community && <div className="ov-side"><Skeleton h={220} /></div>}</div>
  </>

  const { projects, metrics, audit, pending } = data
  const o = metrics.overall
  const daily = metrics.daily || []
  const byId = Object.fromEntries(projects.map(p => [p.project.id, p.project]))
  const prodPending = pending.filter(p => p.toEnv === 'prod').length

  const stages = [
    o.commitsBuilt != null && {
      key: 'commits', step: 1, name: 'Commits', value: o.commitsBuilt, sub: `built by CI across ${plural(projects.length, 'project')}`,
      ticks: d => ((d.succeeded || 0) + (d.failed || 0) > 0 ? 'info' : 'idle'), what: 'days with CI activity',
    },
    {
      key: 'ci', step: 2, name: 'CI runs', value: o.ciRuns, sub: `${pct(o.ciSuccessRate)} passed · avg ${o.ciDurationMinutesMean ?? '—'} min`,
      ticks: d => (d.failed > 0 ? 'bad' : d.succeeded > 0 ? 'ok' : 'idle'), what: 'days with a failed run in red, passing days in green',
    },
    ...ENVS.map((env, i) => {
      const count = env === 'dev' ? o.devDeploys : env === 'prod' ? o.prodDeploys : o.stagingDeploys
      const [summary, tone] = env === 'prod' && prodPending ? [`${prodPending} waiting for approval`, 'amber'] : envSummary(projects, env)
      const field = `deploys${env[0].toUpperCase()}${env.slice(1)}`
      return {
        key: env, step: 3 + i, name: env[0].toUpperCase() + env.slice(1), value: count ?? '—', unit: count == null ? '' : 'deploys',
        sub: summary, tone,
        ticks: d => (d[field] > 0 ? 'ok' : 'idle'), what: `days with a ${env} deployment`,
      }
    }),
  ].filter(Boolean)

  const needs = community ? [] : [
    ...pending.map(p => ({
      key: `promo:${p.id}`, mark: p.kind === 'ROLLBACK' ? 'undo' : 'rocket', tone: 'ok',
      title: `${byId[p.projectId]?.name || 'Project'} → ${p.toEnv}`,
      sub: `${p.kind === 'ROLLBACK' ? 'Rollback' : 'Promotion'} requested ${ago(p.requestedAt)}${p.requestedBy ? ` by ${p.requestedBy}` : ''}`,
      action: 'Review', to: '/approvals',
    })),
    ...projects.filter(p => p.lastRun?.status === 'FAILED').map(({ project: p, lastRun }) => ({
      key: `run:${lastRun.id}`, mark: 'bug', tone: 'bad', title: `${p.name} #${lastRun.id} failed`, sub: 'Latest CI run', action: 'Open', to: `/runs/${lastRun.id}`,
    })),
    ...projects.flatMap(({ project: p, environments }) => environments.filter(e => envTone(e) === 'red').map(e => ({
      key: `env:${p.id}:${e.environment}`, mark: 'shield', tone: 'bad', title: `${p.name} ${e.environment} is degraded`,
      sub: `${e.healthStatus || 'Unhealthy'} in Argo CD`, action: 'Open', to: `/projects/${p.id}`,
    }))),
  ]

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      {head}

      <section className="ov-strip" aria-label="Delivery pipeline">
        <ol className="ov-stages">
          {stages.map(s => (
            <li key={s.key} className={`ov-stage stage-${s.key}`}>
              <div className="ov-stage-head"><span className="ov-step" aria-hidden="true">{s.step}</span><h2>{s.name}</h2></div>
              <div className="ov-value">{s.value}{s.unit && <span className="ov-unit"> {s.unit}</span>}</div>
              <div className={`ov-sub${s.tone ? ` tone-${s.tone}` : ''}`}>{s.sub}</div>
              <Ticks days={daily} pick={s.ticks} label={`${s.name}, one mark per day over ${daily.length} days: ${s.what}`} />
            </li>
          ))}
        </ol>
        <p className="ov-strip-foot">
          One mark per day. Lead time <b>{hrs(o.leadTimeHoursMedian)}</b> · time to restore <b>{hrs(o.timeToRestoreHoursMean)}</b> ·
          change success <b>{o.changeFailureRate == null ? '—' : pct(1 - o.changeFailureRate)}</b> · <Link to="/metrics">DORA metrics</Link>
        </p>
      </section>

      <div className="ov-columns">
        <section className="ov-main" aria-labelledby="ov-projects">
          <div className="ov-section-head">
            <h2 id="ov-projects">Projects</h2>
            {projects.length > 0 && <Link to="/projects">{projects.length > PROJECT_ROWS ? `See all ${projects.length}` : 'All projects'}</Link>}
          </div>
          {projects.length === 0 ? <div className="card"><Empty icon="rocket" title="No projects yet">
            {community ? 'No project summaries are available.' : <><Link to="/new">Connect your first GitHub repository</Link> to see it flow through dev, staging and prod.</>}
          </Empty></div> : (
            <ul className="ov-projects">
              {projects.slice(0, PROJECT_ROWS).map(({ project: p, lastRun, environments }) => {
                const [ciWord, ciTone] = lastRun?.id ? (CI_WORD[lastRun.status] || [`CI ${String(lastRun.status).toLowerCase()}`, 'gray']) : ['No CI runs yet', 'gray']
                const body = <>
                  <span className="ov-project-name">
                    <Avatar name={p.name} size={38} />
                    <span><b>{p.name}</b><span className={`ov-ci tone-${ciTone}`}>{ciWord}</span></span>
                  </span>
                  <span className="ov-track" aria-label={`${p.name} environments`}>
                    {ENVS.map((env, i) => {
                      const stop = envStop(environments.find(e => e.environment === env))
                      return (
                        <span key={env} className="ov-stop">
                          <span className="ov-stop-body">
                            <span className="ov-env"><span className={`dot ${stop.tone}`} aria-hidden="true" />{env}</span>
                            <code>{stop.text}</code>
                            {stop.tone !== 'green' && stop.tone !== 'gray' && <span className={`ov-state tone-${stop.tone}`}>{TONE_WORD[stop.tone]}</span>}
                            {stop.tone === 'green' && <span className="sr-only">healthy</span>}
                          </span>
                          {i < ENVS.length - 1 && <span className="ov-link" aria-hidden="true" />}
                        </span>
                      )
                    })}
                  </span>
                </>
                return <li key={p.id}>{community ? <div className="ov-project">{body}</div> : <Link className="ov-project" to={`/projects/${p.id}`}>{body}</Link>}</li>
              })}
            </ul>
          )}
        </section>

        {!community && <aside className="ov-side">
          <section className="ov-needs" aria-labelledby="ov-needs">
            <h2 id="ov-needs">Needs you</h2>
            {needs.length === 0 ? <p className="ov-needs-sub">Nothing needs you right now.</p> : <>
              <p className="ov-needs-sub">{plural(needs.length, 'thing is', 'things are')} waiting on a decision.</p>
              <ul>
                {needs.map(n => (
                  <li key={n.key}>
                    <span className={`ov-needs-mark ${n.tone}`} aria-hidden="true"><Icon name={n.mark} size={16} /></span>
                    <span className="ov-needs-text"><b>{n.title}</b><span>{n.sub}</span></span>
                    <Link className="ov-needs-action" to={n.to} aria-label={`${n.action}: ${n.title}`}>{n.action}</Link>
                  </li>
                ))}
              </ul>
            </>}
          </section>

          <section className="card ov-activity" aria-labelledby="ov-activity">
            <div className="ov-section-head"><h2 id="ov-activity">Activity</h2><Link to="/audit">Audit log</Link></div>
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
          </section>
        </aside>}
      </div>
    </>
  )
}
