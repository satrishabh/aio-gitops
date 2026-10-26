import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, Skeleton } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

const STATUS = { Successful: ['Healthy', 'Connected'], Failed: ['Degraded', 'Failed'], NotRegistered: ['Missing', 'Not registered'] }

export default function Clusters() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/clusters'), 15000)
  if (!data) return error ? <p className="error">{error}</p> : <Skeleton h={220} />
  const hubOnly = data.length === 1 && data[0].hub

  return (
    <>
      <PageHead title="Clusters" sub="Where each environment deploys. The hub runs the platform, Tekton CI and Argo CD; apps run on workload clusters, reached only through Argo CD." />
      <Stale stale={stale} error={error} onRetry={refresh} />

      {hubOnly && (
        <div className="banner"><Icon name="layers" size={15} />
          Single-cluster mode: every environment deploys to the hub. Add workload clusters with{' '}
          <code>bootstrap/add-cluster.sh</code> and list them in <code>values-platform.yaml</code>.
        </div>
      )}

      {data.length === 0 ? <Empty icon="layers" title="No clusters configured" /> : (
        <div className="card flush">
          <table>
            <thead><tr><th>Cluster</th><th>Environments</th><th>Connection</th><th>Kubernetes</th><th>Ingress domain</th><th>Blue-green</th><th>Argo apps</th></tr></thead>
            <tbody>
              {data.map(c => {
                const [tone, label] = STATUS[c.status] || ['Unknown', c.status || 'Unknown']
                return (
                  <tr key={c.name}>
                    <td><b>{c.name}</b>{c.hub && <> <span className="badge gray quiet">hub</span></>}
                      <div className="muted mono" style={{ fontSize: '0.75rem' }}>{c.server}</div></td>
                    <td>{c.envs.map(e => <span key={e} className={`badge ${e === 'prod' ? 'amber' : 'blue'} quiet`} style={{ marginRight: 4 }}>{e}</span>)}</td>
                    <td><Badge value={tone} label={label} />
                      {c.message && <div className="muted" style={{ fontSize: '0.75rem', maxWidth: 320 }}>{c.message}</div>}</td>
                    <td className="mono">{c.version || '—'}</td>
                    <td>{c.baseDomain ? <code>{c.baseDomain}</code> : <span className="muted">none (no ingress)</span>}</td>
                    <td>{c.rollouts ? <span className="badge green quiet">Argo Rollouts</span> : <span className="muted">rolling update</span>}</td>
                    <td className="mono">{c.argoApps ?? '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint">
        Changing which cluster an environment maps to moves its apps on the next platform start (dev, staging) — the old copy is
        deleted from the previous cluster. Prod only moves when an approver clicks <b>Repair environments</b> on a project.
      </p>
    </>
  )
}
