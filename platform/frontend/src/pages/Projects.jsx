import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, PageHead, Skeleton, ago, envTone } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']

export default function Projects() {
  const { data: items, error, stale, refresh } = usePoll(() => api('/api/projects'), 10000)
  const [q, setQ] = useState('')

  if (!items) return <><PageHead title="Projects" /><div className="project-grid"><Skeleton h={190} /><Skeleton h={190} /><Skeleton h={190} /></div></>
  const shown = items.filter(({ project: p }) => `${p.name} ${p.repoOwner}/${p.repoName}`.toLowerCase().includes(q.toLowerCase()))

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Projects" sub={`${items.length} project${items.length === 1 ? '' : 's'} · each deploys as its own release into the shared dev, staging and prod namespaces`}>
        <div className="search" style={{ width: 260 }}>
          <Icon name="search" size={15} /><input placeholder="Search projects…" value={q} onChange={e => setQ(e.target.value)} />
        </div>
        <Link to="/new" className="btn primary"><Icon name="plus" size={15} />New project</Link>
      </PageHead>

      {items.length === 0 ? (
        <div className="card"><Empty icon="rocket" title="No projects yet">
          Pick a GitHub repository with a Dockerfile — we will build, scan and deploy it for you.
          <div style={{ marginTop: 14 }}><Link className="btn primary" to="/new"><Icon name="plus" size={15} />Add repository</Link></div>
        </Empty></div>
      ) : (
        <div className="project-grid">
          {shown.map(({ project: p, lastRun, environments }) => (
            <Link key={p.id} to={`/projects/${p.id}`} className="card project-card">
              <div className="title">
                <Avatar name={p.name} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <h3>{p.name}</h3>
                  <div className="repo"><Icon name="github" size={12} /> {p.repoOwner}/{p.repoName} · <Icon name="git" size={12} /> {p.defaultBranch}</div>
                </div>
                {lastRun?.id && <Badge value={lastRun.status} />}
              </div>
              <div className="env-strip">
                {ENVS.map(env => {
                  const s = environments.find(e => e.environment === env)
                  const tone = envTone(s)
                  return (
                    <div key={env} className="env-chip">
                      <div className="name"><span className={`dot ${tone}`} />{env}</div>
                      <div className="tag">{s?.replicas === 0 ? <span className="muted">not deployed</span> : (s?.desiredTag || s?.imageTag || '—')}</div>
                    </div>
                  )
                })}
              </div>
              <div className="card-foot">
                <Icon name="clock" size={13} />
                {lastRun?.id ? <>Run #{lastRun.id} · {ago(lastRun.createdAt)}</> : 'No CI runs yet'}
                <span style={{ marginLeft: 'auto' }}>scan gate <code>{p.scanSeverity}</code></span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </>
  )
}
