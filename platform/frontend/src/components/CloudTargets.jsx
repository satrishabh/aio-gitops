import providers from '../data/cloudProviders.json'

export default function CloudTargets() {
  return <section className="card cloud-targets" aria-labelledby="cloud-targets-title">
    <h3 id="cloud-targets-title">Cloud deployment targets</h3>
    <p>Use the portable AIO installation package on an existing Kubernetes cluster. Each provider needs its own networking, storage, identity and registry checks before production use.</p>
    <div className="table-scroll"><table className="stack-table" aria-label="Cloud target capabilities">
      <thead><tr><th>Provider</th><th>Target</th><th>Readiness</th><th>Requirements</th></tr></thead>
      <tbody>{providers.map(p => <tr key={p.id}>
        <td className="stack-title"><b>{p.name}</b></td><td data-label="Target">{p.target}</td><td data-label="Readiness">{p.status}</td>
        <td data-label="Requirements" className="stack-wide">{p.requirements}</td>
      </tr>)}</tbody>
    </table></div>
    <p className="hint">This catalog describes installation paths, not connected clusters. Current cluster connections are shown above. Cloudflare DNS/CDN can front a Kubernetes installation; Cloudflare edge deployments require a separate adapter.</p>
  </section>
}
