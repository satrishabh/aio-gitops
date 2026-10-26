import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { Avatar, PageHead, ago } from '../components/ui.jsx'
import RuntimeFields, { RUNTIME_DEFAULTS, cleanEnv } from '../components/RuntimeFields.jsx'
import { PRESETS } from '../projectOptions.js'

export default function NewProject() {
  const nav = useNavigate()
  const toast = useToast()
  const [form, setForm] = useState({
    repoUrl: '', name: '', dockerfile: './Dockerfile', contextDir: '.',
    testImage: '', testCommand: '', registerWebhook: true, scanSeverity: 'HIGH,CRITICAL',
    envVars: [], ...RUNTIME_DEFAULTS,
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [repos, setRepos] = useState(null)
  const [repoError, setRepoError] = useState('')
  const [q, setQ] = useState('')
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  useEffect(() => { api('/api/github/repos').then(setRepos).catch(e => setRepoError(e.message)) }, [])
  const shown = useMemo(() => (repos || []).filter(r => r.fullName.toLowerCase().includes(q.toLowerCase())).slice(0, 50), [repos, q])

  async function submit(e) {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      const p = await api('/api/projects', { method: 'POST', body: { ...form, envVars: cleanEnv(form.envVars) } })
      toast('ok', `Project ${p.name} created — starting first CI run`)
      const run = await api(`/api/projects/${p.id}/runs`, { method: 'POST', body: {} })
      nav(`/runs/${run.id}`)
    } catch (err) {
      setError(err.message); toast('error', err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHead title="New project" sub="Connect a GitHub repository that has a Dockerfile — any language works.">
        <Link to="/templates" className="btn"><Icon name="layers" size={14} />Start from a template</Link>
      </PageHead>
      <div className="steps-indicator">
        <div><b><Icon name="github" size={14} /> 1 · Repository</b>pick or paste</div>
        <div><b><Icon name="settings" size={14} /> 2 · Build &amp; run</b>Dockerfile, tests, env vars, CPU &amp; memory</div>
        <div><b><Icon name="rocket" size={14} /> 3 · Go</b>namespaces, GitOps, Argo CD, first run</div>
      </div>
      <form onSubmit={submit} className="grid span">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 20, minWidth: 0 }}>
        <div className="card form" style={{ maxWidth: 'none', margin: 0 }}>
          <div className="card-head"><h3>Repository</h3>{repos && <span className="muted">{repos.length} repos available</span>}</div>
          <div className="search"><Icon name="search" size={15} />
            <input type="search" aria-label="Search your repositories" placeholder={repos ? 'Search your repositories…' : 'Loading your repositories…'} value={q} onChange={e => setQ(e.target.value)} />
          </div>
          {repoError && <p className="muted">Could not list repos ({repoError}) — paste the URL below.</p>}
          {repos && (
            <div className="picker">
              {shown.map(r => (
                <button type="button" key={r.fullName} className={form.repoUrl === r.url ? 'active' : ''} aria-pressed={form.repoUrl === r.url}
                        onClick={() => setForm({ ...form, repoUrl: r.url, name: '' })}>
                  <Avatar name={r.fullName.split('/')[1]} size={30} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <b>{r.fullName}</b> {r.private && <span className="badge gray" style={{ marginLeft: 6 }}>private</span>}
                    <div className="desc">{r.description || `default branch ${r.defaultBranch}`} · pushed {ago(r.pushedAt)}</div>
                  </div>
                  {form.repoUrl === r.url && <Icon name="check" size={16} style={{ color: 'var(--accent)' }} />}
                </button>
              ))}
              {shown.length === 0 && <p className="muted" style={{ padding: '8px 12px' }}>No matches.</p>}
            </div>
          )}
          <label>GitHub repository URL
            <input required placeholder="https://github.com/org/repo" value={form.repoUrl} onChange={set('repoUrl')} />
          </label>
          <label>Project name <span className="muted">(defaults to the repo name)</span>
            <input value={form.name} onChange={set('name')} />
          </label>
        </div>

        <div className="card form" style={{ maxWidth: 'none', margin: 0 }}>
          <div className="card-head"><h3>Runtime</h3><span className="muted">initial values — editable later in Settings</span></div>
          <RuntimeFields value={form} onChange={v => setForm({ ...form, ...v })} />
        </div>
        </div>

        <div className="card form" style={{ maxWidth: 'none', margin: 0 }}>
          <div className="card-head"><h3>Build &amp; test</h3></div>
          <div className="row">
            <label>Dockerfile<input value={form.dockerfile} onChange={set('dockerfile')} /></label>
            <label>Build context<input value={form.contextDir} onChange={set('contextDir')} /></label>
          </div>
          <label>Stack preset
            <select onChange={e => setForm({ ...form, ...PRESETS[e.target.value] })}>
              <option value="">none — tests run inside the Dockerfile</option>
              {Object.keys(PRESETS).filter(Boolean).map(k => <option key={k} value={k}>{k}</option>)}
            </select>
          </label>
          <label>Test image<input placeholder="alpine:3.20" value={form.testImage} onChange={set('testImage')} /></label>
          <label>Test command<input placeholder="(skip)" value={form.testCommand} onChange={set('testCommand')} /></label>
          <label>Vulnerability gate
            <select value={form.scanSeverity} onChange={set('scanSeverity')}>
              <option value="HIGH,CRITICAL">Block on HIGH or CRITICAL (default)</option>
              <option value="CRITICAL">Block on CRITICAL only</option>
              <option value="NONE">Report only (never block)</option>
            </select>
          </label>
          <label className="check">
            <input type="checkbox" checked={form.registerWebhook} onChange={set('registerWebhook')} />
            Build on every push &amp; pull request (GitHub webhook)
          </label>
          {error && <p className="error">{error}</p>}
          <button className={`btn primary${busy ? ' busy' : ''}`} disabled={busy} style={{ width: '100%', justifyContent: 'center', padding: 11 }}>
            <Icon name="rocket" size={15} />{busy ? 'Creating…' : 'Create & run first CI'}
          </button>
        </div>
      </form>
    </>
  )
}
