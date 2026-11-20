import { Link } from 'react-router-dom'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Empty, PageHead, Skeleton, envTone, isStopped } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

const ENVS = ['dev', 'staging', 'prod']

export default function PipelinesCd({ me }) {
  const { data, error, stale, refresh } = usePoll(() => Promise.all([api('/api/projects'), api('/api/clusters').catch(() => [])]), 8000)
  const { busy, run } = useAction(refresh)
  if (!data) return error ? <p className="error">{error}</p> : <Skeleton h={260} />
  const [projects, clusters] = data
  const clusterOf = Object.fromEntries(clusters.flatMap(c => c.envs.map(e => [e, c.name])))

  return (
    <>
      <PageHead title="CD pipelines" sub="Argo CD applications for every project and environment. Open one to edit its sync policy and values." />
      <Stale stale={stale} error={error} onRetry={refresh} />
      {projects.length === 0 ? <Empty icon="rocket" title="No projects yet"><Link to="/new">Create a project</Link> to get CD applications.</Empty> : (
        <div className="card flush">
          <table className="fl-table fl-cd-table">
            <thead><tr><th>Project</th><th>Environment</th><th>Cluster</th><th>Sync</th><th>Health</th><th>Image</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {projects.flatMap(({ project: p, environments }) => ENVS.map((env, i) => {
                const s = environments.find(e => e.environment === env)
                const locked = env === 'prod' && !me?.approver
                const key = `sync-${p.id}-${env}`
                return (
                  <tr key={`${p.id}-${env}`} className={i === 0 ? 'fl-group-start' : 'fl-group-rest'}>
                    <td className="fl-cd-project" data-label="Project">{i === 0 && <Link to={`/projects/${p.id}`} className="fl-project-link">
                      <Avatar name={p.name} size={26} /><b>{p.name}</b></Link>}</td>
                    <td data-label="Environment" className="fl-cd-env"><span className="fl-env-cell"><span className={`dot ${envTone(s)}`} />
                      <Link to={`/projects/${p.id}/cd/${env}`}>{env}</Link></span></td>
                    <td data-label="Cluster" className="muted">{clusterOf[env] || '—'}</td>
                    <td data-label="Sync">{s?.syncStatus ? <Badge value={s.syncStatus} /> : <span className="muted">—</span>}</td>
                    <td data-label="Health">{s?.healthStatus ? <Badge value={s.healthStatus} /> : <span className="muted">—</span>}</td>
                    <td data-label="Image"><code>{s?.replicas === 0 ? (isStopped(s) ? 'scaled down' : 'not deployed') : (s?.imageTag || s?.desiredTag || '—').slice(0, 12)}</code></td>
                    <td className="fl-cell-actions">
                      <button className={`btn small${busy === key ? ' busy' : ''}`} disabled={!!busy || locked}
                              title={locked ? 'Only approvers can sync prod' : 'Apply the GitOps state now'}
                              aria-label={`Sync ${p.name} ${env}${locked ? ' (approvers only)' : ''}`}
                              onClick={() => run(key, () => api(`/api/projects/${p.id}/environments/${env}/cd/sync`, { method: 'POST' }), `Sync of ${p.name} ${env} started`)}>
                        <Icon name="refresh" size={13} />Sync</button>{' '}
                      <Link className="btn small ghost" to={`/projects/${p.id}/cd/${env}`} aria-label={`Open ${p.name} ${env} CD`}>Open</Link>
                    </td>
                  </tr>
                )
              }))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
