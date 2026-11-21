import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, SkeletonTable } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'
import CloudTargets from '../components/CloudTargets.jsx'

const STATUS = { Successful: ['Healthy', 'Connected'], Failed: ['Degraded', 'Failed'], NotRegistered: ['Missing', 'Not registered'] }
const SUB = 'Where each environment deploys. The hub runs the platform, Tekton CI and Argo CD; apps run on workload clusters, reached only through Argo CD.'

export default function Clusters() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/clusters'), 15000)
  if (!data) return <><PageHead title="Clusters" sub={SUB} />
    {error ? <p className="error" role="alert">{error}</p> : <SkeletonTable rows={3} cols={7} label="Loading clusters" />}</>
  const hubOnly = data.length === 1 && data[0].hub

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Clusters" sub={SUB} />

      {hubOnly && (
        <div className="banner info-banner"><Icon name="layers" size={15} />
          <span>Single-cluster mode: every environment deploys to the hub. Add workload clusters with{' '}
          <code>bootstrap/add-cluster.sh</code> and list them in <code>values-platform.yaml</code>.</span>
        </div>
      )}

      {data.length === 0 ? <div className="card"><Empty icon="server" title="No clusters configured">
        Add a workload cluster with <code>bootstrap/add-cluster.sh</code>, then list it in <code>values-platform.yaml</code>.
      </Empty></div> : (
        <div className="card flush">
          <table className="stack-table clusters-table">
            <thead><tr><th>Cluster</th><th>Environments</th><th>Connection</th><th>Kubernetes</th><th>Ingress domain</th><th>Blue-green</th><th>Argo apps</th></tr></thead>
            <tbody>
              {data.map(c => {
                const [tone, label] = STATUS[c.status] || ['Unknown', c.status || 'Unknown']
                return (
                  <tr key={c.name}>
                    <td className="stack-title"><span className="cluster-name"><Icon name="server" size={15} /><b>{c.name}</b>{c.hub && <Badge tone="gray" label="hub" quiet />}</span>
                      <div className="muted mono small-print">{c.server}</div></td>
                    <td data-label="Environments"><span className="badge-row">{c.envs.map(e => <Badge key={e} tone={e === 'prod' ? 'amber' : 'blue'} label={e} quiet />)}</span></td>
                    <td data-label="Connection"><Badge value={tone} label={label} />
                      {c.message && <div className="muted small-print cluster-message">{c.message}</div>}</td>
                    <td data-label="Kubernetes" className="mono">{c.version || '—'}</td>
                    <td data-label="Ingress domain">{c.baseDomain ? <code>{c.baseDomain}</code> : <span className="muted">none (no ingress)</span>}
                      {c.ingressClass && <div className="muted mono small-print">class {c.ingressClass} · {c.ingressController}</div>}</td>
                    <td data-label="Blue-green">{c.rollouts ? <Badge tone="green" label="Argo Rollouts" quiet /> : <span className="muted">rolling update</span>}</td>
                    <td data-label="Argo apps" className="mono">{c.argoApps ?? '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint page-hint">
        Changing which cluster an environment maps to moves its apps on the next platform start (dev, staging) — the old copy is
        deleted from the previous cluster. Prod only moves when an approver clicks <b>Repair environments</b> on a project.
      </p>
      <CloudTargets />
    </>
  )
}
