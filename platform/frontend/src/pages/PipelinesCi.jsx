import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, duration } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, Skeleton, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

export default function PipelinesCi() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/runs?limit=50'), 6000)
  const [open, setOpen] = useState(false)
  return (
    <>
      <PageHead title="CI pipelines" sub="Tekton runs across every project: clone → test → build & push → image scan → deploy to dev.">
        <button className="btn primary" onClick={() => setOpen(o => !o)} aria-expanded={open}><Icon name="play" size={14} />Run CI</button>
      </PageHead>
      <Stale stale={stale} error={error} onRetry={refresh} />
      {open && <RunCi onClose={() => setOpen(false)} />}
      {!data ? <Skeleton h={260} /> : data.length === 0 ? <Empty icon="zap" title="No CI runs yet">Push to a project's repository or press Run CI.</Empty> : (
        <div className="card flush">
          <table>
            <thead><tr><th>Run</th><th>Project</th><th>Status</th><th>Ref</th><th>Vulnerabilities</th><th>By</th><th>Duration</th></tr></thead>
            <tbody>
              {data.map(({ run: r, project }) => (
                <tr key={r.id}>
                  <td><Link to={`/runs/${r.id}`}><b>#{r.id}</b></Link><div className="muted" style={{ fontSize: '0.75rem' }}>{ago(r.createdAt)}</div></td>
                  <td><Link to={`/projects/${project.id}`}>{project.name}</Link></td>
                  <td><Badge value={r.status} /></td>
                  <td><code>{r.gitRef}</code> <span className="muted mono">{r.commitSha?.slice(0, 7)}</span></td>
                  <td><Vulns run={r} /></td>
                  <td className="muted">{r.triggeredBy}</td>
                  <td className="mono">{duration(r.startedAt, r.finishedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function RunCi({ onClose }) {
  const nav = useNavigate()
  const [projects, setProjects] = useState([])
  const [form, setForm] = useState({ projectId: '', ref: '' })
  const { busy, run } = useAction()
  useEffect(() => { api('/api/projects').then(setProjects).catch(() => {}) }, [])
  const selected = projects.find(x => String(x.project.id) === form.projectId)

  async function submit(e) {
    e.preventDefault()
    const r = await run('ci', () => api(`/api/projects/${form.projectId}/runs`, { method: 'POST', body: { ref: form.ref.trim() || undefined } }),
      r => `CI run #${r.id} started`)
    if (r) nav(`/runs/${r.id}`)
  }

  return (
    <form className="card form" style={{ maxWidth: 'none' }} onSubmit={submit} aria-labelledby="runci-h">
      <div className="card-head"><h3 id="runci-h">Run CI</h3>
        <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}><Icon name="x" size={15} /></button></div>
      <div className="row">
        <label>Project<select required value={form.projectId} onChange={e => setForm({ ...form, projectId: e.target.value })}>
          <option value="">Choose…</option>
          {projects.map(x => <option key={x.project.id} value={x.project.id}>{x.project.name}</option>)}
        </select></label>
        <label>Branch, tag or commit<input placeholder={selected?.project.defaultBranch || 'main'} value={form.ref}
                                           onChange={e => setForm({ ...form, ref: e.target.value })} spellCheck="false" /></label>
      </div>
      <div style={{ display: 'flex', gap: 10 }}>
        <button type="submit" className={`btn primary${busy === 'ci' ? ' busy' : ''}`} disabled={!!busy || !form.projectId}><Icon name="play" size={14} />Start</button>
        <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
      </div>
    </form>
  )
}
