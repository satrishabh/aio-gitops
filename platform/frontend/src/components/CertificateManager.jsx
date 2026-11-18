import { api, fmtTime } from '../api.js'
import Badge from './Badge.jsx'
import { Empty, Skeleton } from './ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

export default function CertificateManager({ projectId, onDomains }) {
  const { data, error, stale, refresh } = usePoll(() => api(`/api/projects/${projectId}/domains`), 15000, [projectId])
  const domains = data?.domains || []
  return <>
    <Stale stale={stale} error={error} onRetry={refresh} />
    <section className="card" style={{ padding: 20 }} aria-labelledby="certificates-title">
      <div className="card-head">
        <h3 id="certificates-title">Certificate manager</h3>
        <button type="button" className="btn" onClick={refresh}>Refresh certificates</button>
      </div>
      <p>Check HTTPS certificates for this project's domains across environments. cert-manager requests and renews certificates on the destination cluster; this view shows AIO's latest observation.</p>
      <p className="hint">Add a hostname in Domains to request a certificate after DNS is ready. Refresh reloads the recorded observation; Last checked shows its age. Unknown expiry or no certificate does not prove that cert-manager is absent.</p>
      <button type="button" className="btn" onClick={onDomains}>Manage domains and certificate requests</button>
    </section>
    {!data ? error ? <p role="alert" className="error">{error}</p> : <Skeleton h={160} />
      : !domains.length ? <Empty icon="shield" title="No project certificates yet">Add a custom domain to start HTTPS issuance.</Empty>
        : <div className="card" style={{ overflowX: 'auto' }}>
          <table aria-label="Project certificates">
            <thead><tr><th>Domain / route</th><th>Environment</th><th>Status</th><th>Issuer</th><th>Expiry</th><th>Last checked</th></tr></thead>
            <tbody>{domains.map(d => {
              const expires = d.certExpiresAt ? new Date(d.certExpiresAt).getTime() : NaN
              const days = Number.isFinite(expires) ? Math.ceil((expires - Date.now()) / 86400000) : null
              const expired = Number.isFinite(expires) && expires <= Date.now()
              return <tr key={d.id}>
                <td><b>{d.host}</b><code>{d.path}</code><div className="muted">{d.certOwner ? 'Certificate owner route' : 'Shared host certificate'}</div></td>
                <td>{d.env}</td>
                <td><Badge value={expired ? 'FAILED' : d.status} label={expired ? 'Expired' : undefined} /><div className="muted">{d.message || 'Awaiting observation'}</div></td>
                <td><code>{d.issuer || 'Not recorded'}</code></td>
                <td>{days == null ? 'Unknown' : <><span>{fmtTime(d.certExpiresAt)}</span><div className={expired ? 'error' : 'muted'}>{expired ? 'Expired' : days <= 30 ? `Expires within ${days} days` : `${days} days remaining`}</div></>}</td>
                <td>{d.lastCheckedAt ? fmtTime(d.lastCheckedAt) : 'Not checked yet'}</td>
              </tr>
            })}</tbody>
          </table>
        </div>}
  </>
}
