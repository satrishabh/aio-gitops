import { useState } from 'react'
import { api } from '../api.js'

export default function DeliverySettings({ project, onSaved }) {
  const [connection, setConnection] = useState({ name: '', host: 'ghcr.io', repository: '', access: 'push', username: '', token: '' })
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const base = `/api/projects/${project.id}/delivery-config`
  async function perform(action) {
    setBusy(true); setError(''); setMessage('')
    try { await action() } catch (e) { setError(e.message) } finally { setBusy(false) }
  }
  const field = (key, value) => setConnection(c => ({ ...c, [key]: value }))
  return <details className="card" style={{ padding: 16 }}>
    <summary>Registry and delivery configuration</summary>
    <p className="muted">Commit .aio/project.yaml to your default branch, register separate push and pull connections below, then approve the configuration. External registries currently support build-only runs. Registry registration stores credentials; the first build verifies access.</p>
    <p>Configuration: <code>{project.deliveryConfigHash ? project.deliveryConfigHash.slice(0, 12) : 'Platform defaults'}</code> · Requested artifact tier: {project.requestedArtifactTier || 'normal'}</p>
    <form className="form" autoComplete="off" onSubmit={e => { e.preventDefault(); perform(async () => {
      await api(`${base}/connections`, { method: 'POST', body: connection })
      setConnection(c => ({ ...c, token: '' })); setMessage('Connection registered. Register the other access type with a separate credential.')
    }) }}>
      <label>Connection name<input required maxLength={32} value={connection.name} onChange={e => field('name', e.target.value)} /></label>
      <label>Registry host<input required value={connection.host} onChange={e => field('host', e.target.value)} /></label>
      <label>Repository path<input required placeholder="team/application" value={connection.repository} onChange={e => field('repository', e.target.value)} /></label>
      <label>Access<select value={connection.access} onChange={e => field('access', e.target.value)}><option value="push">Build push</option><option value="pull">Scan pull (read only)</option></select></label>
      <label>Registry username<input required autoComplete="off" value={connection.username} onChange={e => field('username', e.target.value)} /></label>
      <label>Registry token<input required type="password" autoComplete="new-password" value={connection.token} onChange={e => field('token', e.target.value)} /></label>
      <button className="btn" disabled={busy}>Register or rotate connection</button>
      <button type="button" className="btn" disabled={busy || !connection.name} onClick={() => perform(async () => {
        await api(`${base}/connections/${encodeURIComponent(connection.name)}`, { method: 'DELETE' }); setMessage('Connection revoked for new builds.')
      })}>Revoke named connection</button>
    </form>
    <button type="button" className="btn primary" disabled={busy} onClick={() => perform(async () => {
      await api(base, { method: 'POST' }); setMessage('Default-branch configuration approved. New runs will use the reviewed configuration.'); onSaved()
    })}>Approve repository configuration</button>
    {message && <p role="status">{message}</p>}{error && <p role="alert" className="error">{error}</p>}
  </details>
}
