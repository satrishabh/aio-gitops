import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { useToast } from '../components/Toast.jsx'

const PRESETS = {
  '': { testImage: '', testCommand: '' },
  java: { testImage: 'maven:3.9-eclipse-temurin-21', testCommand: 'mvn -B verify' },
  gradle: { testImage: 'gradle:8-jdk21', testCommand: 'gradle test --no-daemon' },
  node: { testImage: 'node:20', testCommand: 'npm ci && npm test' },
  python: { testImage: 'python:3.12', testCommand: 'pip install -r requirements.txt && pytest' },
  go: { testImage: 'golang:1.22', testCommand: 'go test ./...' },
  dotnet: { testImage: 'mcr.microsoft.com/dotnet/sdk:8.0', testCommand: 'dotnet test' },
}

export default function NewProject() {
  const nav = useNavigate()
  const toast = useToast()
  const [form, setForm] = useState({
    repoUrl: '', name: '', dockerfile: './Dockerfile', contextDir: '.',
    testImage: '', testCommand: '', registerWebhook: true, scanSeverity: 'HIGH,CRITICAL',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [repos, setRepos] = useState(null)
  const [repoError, setRepoError] = useState('')
  const [q, setQ] = useState('')
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  useEffect(() => {
    api('/api/github/repos').then(setRepos).catch(e => setRepoError(e.message))
  }, [])
  const shown = useMemo(() => (repos || [])
    .filter(r => r.fullName.toLowerCase().includes(q.toLowerCase())).slice(0, 50), [repos, q])

  async function submit(e) {
    e.preventDefault()
    setBusy(true); setError('')
    try {
      const p = await api('/api/projects', { method: 'POST', body: form })
      toast('ok', `Project ${p.name} created — starting first CI run`)
      const run = await api(`/api/projects/${p.id}/runs`, { method: 'POST', body: {} })
      nav(`/runs/${run.id}`)
    } catch (err) {
      setError(err.message)
      toast('error', err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h2>New project</h2>
      <p className="muted">
        Pick (or paste) a GitHub repository that contains a Dockerfile. The platform creates the namespaces, GitOps
        values and Argo CD apps (dev / staging / prod), then starts the first CI run.
      </p>
      <label>Your repositories
        <input placeholder={repos ? `Search ${repos.length} repos…` : 'Loading repos…'} value={q} onChange={e => setQ(e.target.value)} />
      </label>
      {repoError && <p className="muted">Could not list repos ({repoError}) — paste the URL below.</p>}
      {repos && (
        <div className="picker">
          {shown.map(r => (
            <button type="button" key={r.fullName} className={form.repoUrl === r.url ? 'active' : ''}
                    onClick={() => setForm({ ...form, repoUrl: r.url, name: '' })}>
              <strong>{r.fullName}</strong> {r.private && <span className="muted">(private)</span>}
              {r.description && <span className="muted"> — {r.description}</span>}
            </button>
          ))}
          {shown.length === 0 && <p className="muted" style={{ padding: '6px 10px' }}>No matches.</p>}
        </div>
      )}
      <label>GitHub repository URL
        <input required placeholder="https://github.com/org/repo" value={form.repoUrl} onChange={set('repoUrl')} />
      </label>
      <label>Project name <span className="muted">(defaults to repo name)</span>
        <input value={form.name} onChange={set('name')} />
      </label>
      <div className="row">
        <label>Dockerfile<input value={form.dockerfile} onChange={set('dockerfile')} /></label>
        <label>Build context<input value={form.contextDir} onChange={set('contextDir')} /></label>
      </div>
      <label>Stack preset (fills test settings)
        <select onChange={e => setForm({ ...form, ...PRESETS[e.target.value] })}>
          <option value="">none / tests run inside Dockerfile</option>
          {Object.keys(PRESETS).filter(Boolean).map(k => <option key={k} value={k}>{k}</option>)}
        </select>
      </label>
      <div className="row">
        <label>Test image<input placeholder="alpine:3.20" value={form.testImage} onChange={set('testImage')} /></label>
        <label>Test command<input placeholder="(skip)" value={form.testCommand} onChange={set('testCommand')} /></label>
      </div>
      <label>Vulnerability gate
        <select value={form.scanSeverity} onChange={set('scanSeverity')}>
          <option value="HIGH,CRITICAL">Block on HIGH or CRITICAL (default)</option>
          <option value="CRITICAL">Block on CRITICAL only</option>
          <option value="NONE">Report only (never block)</option>
        </select>
      </label>
      <label className="check">
        <input type="checkbox" checked={form.registerWebhook} onChange={set('registerWebhook')} />
        Register GitHub webhook (build on push to default branch and on pull requests)
      </label>
      {error && <p className="error">{error}</p>}
      <button className={`btn primary${busy ? ' busy' : ''}`} disabled={busy}>{busy ? 'Creating…' : 'Create & run CI'}</button>
    </form>
  )
}
