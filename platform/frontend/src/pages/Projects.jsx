import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, Loading, PageHead, Skeleton, ago, envTone, isStopped } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']
const SUB = 'each deploys as its own release into the shared dev, staging and prod namespaces'
// The dot colour is paired with a word, so health never depends on hue alone.
const ENV_WORD = { green: 'healthy', blue: 'syncing', amber: 'out of sync' }
const envWord = (s, tone) => (tone === 'red' ? (s.healthStatus || 'degraded').toLowerCase() : ENV_WORD[tone] || null)

/** Card-shaped placeholders: avatar + name, three environment chips, footer line. */
function ProjectsSkeleton() {
  return (
    <Loading label="Loading projects" className="project-grid">
      {[0, 1, 2, 3, 4, 5].map(i => (
        <div key={i} className="card project-card sk-project">
          <div className="title">
            <Skeleton bare h={38} w={38} className="round" />
            <div className="sk-stack"><Skeleton bare h={14} w="55%" className="line" /><Skeleton bare h={10} w="75%" className="line" /></div>
          </div>
          <div className="env-strip">{ENVS.map(e => <Skeleton key={e} bare h={50} />)}</div>
          <div className="card-foot"><Skeleton bare h={10} w="45%" className="line" /></div>
        </div>
      ))}
    </Loading>
  )
}

export default function Projects({ me }) {
  const community = me?.accessTier === 'community'
  const ProjectCard = community ? 'div' : Link
  const { data: items, error, stale, refresh } = usePoll(() => api('/api/projects'), 10000)
  const [q, setQ] = useState('')

  if (error && !items) return <><Stale stale={stale} error={error} onRetry={refresh} /><PageHead title="Projects" /></>
  if (!items) return <><PageHead title="Projects" sub={`Each project ${SUB.replace('each ', '')}.`} /><ProjectsSkeleton /></>
  const shown = items.filter(({ project: p }) => `${p.name} ${p.repoOwner}/${p.repoName}`.toLowerCase().includes(q.trim().toLowerCase()))

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Projects" sub={`${items.length} project${items.length === 1 ? '' : 's'} · ${SUB}`}>
        <div className="search page-search">
          <Icon name="search" size={15} /><input type="search" aria-label="Search projects" placeholder="Search projects…" value={q} onChange={e => setQ(e.target.value)} />
        </div>
        {!community && <Link to="/new" className="btn primary"><Icon name="plus" size={15} />New project</Link>}
      </PageHead>

      {items.length === 0 ? (
        <div className="card"><Empty icon="rocket" title="No projects yet"
          action={community ? null : <Link className="btn primary" to="/new"><Icon name="plus" size={15} />Add repository</Link>}>
          {community ? 'No project summaries are available.' : 'Pick a GitHub repository — we will build, scan, sign and deploy it for you.'}
        </Empty></div>
      ) : shown.length === 0 ? <div className="card"><Empty icon="search" title="No matching projects" action={<button className="btn" onClick={() => setQ('')}>Clear search</button>}>Try a different project or repository name.</Empty></div> : (
        <div className="project-grid">
          {shown.map(({ project: p, lastRun, environments }) => (
            <ProjectCard key={p.id} to={community ? undefined : `/projects/${p.id}`} className="card project-card">
              <div className="title">
                <Avatar name={p.name} />
                <div className="project-card-name">
                  <h3>{p.name}</h3>
                  <div className="repo"><Icon name="github" size={12} /> {p.repoOwner}/{p.repoName} · <Icon name="git" size={12} /> {p.defaultBranch}</div>
                </div>
                {lastRun?.id && <Badge value={lastRun.status} />}
              </div>
              <div className="env-strip">
                {ENVS.map(env => {
                  const s = environments.find(e => e.environment === env)
                  const tone = envTone(s)
                  const word = s && s.replicas !== 0 ? envWord(s, tone) : null
                  return (
                    <div key={env} className={`env-chip env-${tone}`}>
                      <div className="name"><span className={`dot ${tone}`} aria-hidden="true" />{env}</div>
                      <div className="tag">{s?.replicas === 0 ? <span className="muted">{isStopped(s) ? 'scaled down' : 'not deployed'}</span> : (s?.desiredTag || s?.imageTag || '—')}</div>
                      {word && <div className={`env-word tone-${tone}`}>{word}</div>}
                    </div>
                  )
                })}
              </div>
              <div className="card-foot">
                <Icon name="clock" size={13} />
                {lastRun?.id ? <>Run #{lastRun.id} · {ago(lastRun.createdAt)}</> : 'No CI runs yet'}
                <span className="card-foot-end">scan gate <code>{p.scanSeverity}</code></span>
              </div>
            </ProjectCard>
          ))}
        </div>
      )}
    </>
  )
}
