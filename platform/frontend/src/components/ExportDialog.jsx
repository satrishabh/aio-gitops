import { useEffect, useState } from 'react'
import { api, download } from '../api.js'
import Dialog from './Dialog.jsx'
import Icon from './Icon.jsx'
import { useToast } from './Toast.jsx'
import { useEnvironments } from '../environments.js'

const CLOUDS = [['any', 'Any cluster'], ['aks', 'AKS'], ['gke', 'GKE'], ['eks', 'EKS'], ['oke', 'OKE'], ['onprem', 'On-premises']]

/**
 * Project → Export: what the zip holds, the "every switched-on connector" option, and the download
 * (GET /api/projects/{id}/export, fetched as a blob with the session cookie and CSRF header like every API call).
 */
export default function ExportDialog({ project, onClose }) {
  const { names } = useEnvironments()
  const toast = useToast()
  const [preview, setPreview] = useState(null)
  const [all, setAll] = useState(false)
  const [cloud, setCloud] = useState('any')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    api(`/api/projects/${project.id}/export/preview`).then(p => { if (alive) setPreview(p) }).catch(() => { if (alive) setPreview(false) })
    return () => { alive = false }
  }, [project.id])

  const envs = preview?.envs?.length ? preview.envs : names
  const used = preview?.connectors || []
  const installed = preview?.installedConnectors || []
  const extra = installed.filter(c => !used.includes(c))
  const connectors = all ? [...new Set([...used, ...installed])] : used

  async function go() {
    setBusy(true)
    setError('')
    try {
      const q = new URLSearchParams()
      if (all) q.set('allConnectors', 'true')
      if (cloud !== 'any') q.set('cloud', cloud)
      const qs = q.toString()
      const name = await download(`/api/projects/${project.id}/export${qs ? `?${qs}` : ''}`, `${project.name}-aio-export.zip`)
      toast('ok', `Downloaded ${name}`)
      onClose()
    } catch (e) {
      setError(e.message || 'Export failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={`Export ${project.name}`} busy={busy} onClose={onClose}
      sub="A zip that runs this project on any Kubernetes cluster without AIO."
      footer={<>
        <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
        <button type="button" className={`btn primary${busy ? ' busy' : ''}`} onClick={go} disabled={busy} data-autofocus="">
          <Icon name="download" size={14} />{busy ? 'Preparing…' : 'Download zip'}</button>
      </>}>
      <ul className="exp-list" aria-label="What the zip contains">
        <li><Icon name="layers" size={15} /><span><b>Helm chart</b> <code>chart/</code>, the one Argo CD deploys for every AIO app</span></li>
        <li><Icon name="file" size={15} /><span><b>Values</b> <code>values/</code> for {envs.join(', ')}, as they are in git now</span></li>
        <li><Icon name="rocket" size={15} /><span><b>Argo CD Applications</b> <code>argocd/</code> pointing at your own repository (<code>REPO_URL</code>)</span></li>
        <li><Icon name="play" size={15} /><span><b>GitHub Actions workflow</b>: test, build, Trivy scan, push, bump the dev image tag</span></li>
        <li><Icon name="plug" size={15} /><span><b>Connectors</b> <code>connectors/</code>: {connectors.length
          ? connectors.join(', ') : preview === null ? 'checking…' : 'none used'}{!all && used.length > 0 ? ' (used by the app, with what they require)' : ''}</span></li>
        <li><Icon name="lock" size={15} /><span><b>Secret templates</b> <code>secrets/</code>: keys only, never values</span></li>
        <li><Icon name="info" size={15} /><span><b>README</b>: prerequisites, install order, secrets to create, URLs</span></li>
      </ul>
      <form className="form exp-form" onSubmit={e => { e.preventDefault(); go() }}>
        <label className="check">
          <input type="checkbox" checked={all} onChange={e => setAll(e.target.checked)} disabled={busy} />
          <span>Include every switched-on connector{extra.length > 0 && <span className="muted"> (adds {extra.join(', ')})</span>}</span>
        </label>
        <label>Cloud for connector values
          <select value={cloud} onChange={e => setCloud(e.target.value)} disabled={busy}>
            {CLOUDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
      </form>
      <p className="hint">No secret values, tokens or webhook secrets are included. The download is recorded in the audit log.</p>
      {error && <p className="error" role="alert">{error}</p>}
    </Dialog>
  )
}
