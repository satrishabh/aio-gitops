import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { Avatar, PageHead, ago } from '../components/ui.jsx'
import RuntimeFields, { RUNTIME_DEFAULTS, cleanEnv } from '../components/RuntimeFields.jsx'
import { PRESETS } from '../projectOptions.js'
import { useEnvironments } from '../environments.js'

const STEPS = [
  { label: 'Repository', decides: 'Which GitHub repository to build, and the project name.' },
  { label: 'Build', decides: 'How the image is built and tested, and when a scan blocks it.' },
  { label: 'Environments', decides: 'Env vars, CPU and memory for dev, staging and prod.' },
  { label: 'Review', decides: 'Check your choices, then create the project and its first CI run.' },
]
const LAST = STEPS.length - 1
const GATES = { 'HIGH,CRITICAL': 'Block on HIGH or CRITICAL', CRITICAL: 'Block on CRITICAL only', NONE: 'Report only (never block)' }

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
  const [step, setStep] = useState(0)
  const { envs, gated } = useEnvironments()
  const formRef = useRef(null)
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  useEffect(() => { api('/api/github/repos').then(setRepos).catch(e => setRepoError(e.message)) }, [])
  const shown = useMemo(() => (repos || []).filter(r => r.fullName.toLowerCase().includes(q.toLowerCase())).slice(0, 50), [repos, q])

  const repoName = form.repoUrl.replace(/\.git$/, '').replace(/\/+$/, '').split('/').pop() || ''
  const env = cleanEnv(form.envVars)

  // Every step stays mounted (only hidden), so the browser's own constraints (required fields) are checked
  // per step on Next and across all steps on Create, exactly as the single form did before.
  const firstInvalid = idx => {
    const root = idx == null ? formRef.current : formRef.current?.querySelector(`[data-step="${idx}"]`)
    return root ? [...root.querySelectorAll('input, select, textarea')].find(el => !el.checkValidity()) : null
  }
  /** Show a step, then focus its heading (or report the control that needs fixing). */
  function go(idx, fix) {
    setStep(idx)
    requestAnimationFrame(() => {
      if (fix) fix.reportValidity()
      else formRef.current?.querySelector(`[data-step="${idx}"] h2`)?.focus()
    })
  }
  function next() {
    const bad = firstInvalid(step)
    if (bad) { bad.reportValidity(); return }
    go(Math.min(step + 1, LAST))
  }

  async function submit(e) {
    e.preventDefault()
    if (step < LAST) { next(); return }   // Enter in an earlier step moves on instead of creating the project
    const bad = firstInvalid()
    if (bad) { go(Number(bad.closest('[data-step]').dataset.step), bad); return }
    setBusy(true); setError('')
    try {
      const { project: p } = await api('/api/projects', { method: 'POST', body: { ...form, envVars: cleanEnv(form.envVars) } })
      toast('ok', `Project ${p.name} created — starting first CI run`)
      nav('/pipelines/ci')
    } catch (err) {
      setError(err.message); toast('error', err.message)
    } finally {
      setBusy(false)
    }
  }

  const back = idx => <button type="button" className="btn" onClick={() => go(idx - 1)}>
    <Icon name="arrow" size={14} style={{ transform: 'rotate(180deg)' }} />Back</button>

  const panel = (idx, children, finish) => (
    <section className="fl-step-panel" data-step={idx} hidden={step !== idx} aria-labelledby={`np-step-${idx}`}>
      <div className="fl-step-head">
        <span className="fl-step-n" aria-hidden="true">{idx + 1}</span>
        <div>
          <h2 id={`np-step-${idx}`} tabIndex={-1}>{STEPS[idx].label}</h2>
          <p>{STEPS[idx].decides}</p>
        </div>
      </div>
      {children}
      <div className="fl-step-nav">
        {idx > 0 && back(idx)}
        <span className="spacer" />
        {finish || <button type="button" className="btn primary" onClick={next}>Next: {STEPS[idx + 1].label}<Icon name="arrow" size={14} /></button>}
      </div>
    </section>
  )

  const summary = [
    { step: 0, rows: [
      ['Repository', form.repoUrl ? <code>{form.repoUrl}</code> : <span className="fl-missing">Not chosen yet</span>],
      ['Project name', form.name || (repoName ? <>{repoName} <span className="muted">(the repo name)</span></> : '—')],
    ] },
    { step: 1, rows: [
      ['Dockerfile', <><code>{form.dockerfile || '(none)'}</code> <span className="muted">· buildpacks if it is missing</span></>],
      ['Build context', <code>{form.contextDir}</code>],
      ['Tests', form.testCommand ? <><code>{form.testCommand}</code> in <code>{form.testImage || 'alpine:3.20'}</code></> : 'Run inside the Dockerfile'],
      ['Vulnerability gate', GATES[form.scanSeverity]],
      ['Build on push', form.registerWebhook ? 'Yes, through a GitHub webhook' : 'No, manual runs only'],
    ] },
    { step: 2, rows: [
      ['Env vars', env.length ? env.map(v => v.name).join(', ') : 'None'],
      ['CPU', `${form.cpuRequest} request · ${form.cpuLimit} limit`],
      ['Memory', `${form.memoryRequest} request · ${form.memoryLimit} limit`],
      ['Delivery', 'dev on every passing build → staging on Promote → prod after approval'],
    ] },
  ]

  return (
    <>
      <PageHead title="New project" sub="Connect any GitHub repository. With a Dockerfile we build it; without one, Cloud Native Buildpacks detect the language.">
        <Link to="/templates" className="btn"><Icon name="layers" size={14} />Start from a template</Link>
      </PageHead>

      <nav className="fl-stepper" aria-label="New project steps">
        <ol>
          {STEPS.map((s, i) => (
            <li key={s.label} className={i === step ? 'current' : i < step ? 'done' : ''}>
              <button type="button" onClick={() => go(i)} aria-current={i === step ? 'step' : undefined}>
                <span className="fl-step-n" aria-hidden="true">{i < step ? <Icon name="check" size={13} stroke={3} /> : i + 1}</span>
                <span className="fl-stepper-text">
                  <b>{s.label}{i < step && <span className="sr-only"> (visited)</span>}</b>
                  <span>{s.decides}</span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </nav>

      <form onSubmit={submit} ref={formRef} noValidate className="fl-wizard">
        {panel(0, <>
          <div className="fl-subhead"><h3>Your repositories</h3>{repos && <span className="muted">{repos.length} repos available</span>}</div>
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
          <div className="form fl-form">
            <label>GitHub repository URL
              <input required placeholder="https://github.com/org/repo" value={form.repoUrl} onChange={set('repoUrl')} />
            </label>
            <label>Project name <span className="muted">(defaults to the repo name{repoName ? `: ${repoName}` : ''})</span>
              <input value={form.name} onChange={set('name')} />
            </label>
          </div>
        </>)}

        {panel(1, <div className="form fl-form">
          <div className="row">
            <label>Dockerfile <span className="muted">(optional — buildpacks if missing)</span><input value={form.dockerfile} onChange={set('dockerfile')} /></label>
            <label>Build context<input value={form.contextDir} onChange={set('contextDir')} /></label>
          </div>
          <label>Stack preset
            <select onChange={e => setForm({ ...form, ...PRESETS[e.target.value] })}>
              <option value="">none — tests run inside the Dockerfile</option>
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
            Build on every push &amp; pull request (GitHub webhook)
          </label>
          <p className="fl-defaults"><Icon name="settings" size={13} />Defaults: <code>./Dockerfile</code> in <code>.</code>, tests inside the Dockerfile, block on HIGH or CRITICAL, build on every push.</p>
        </div>)}

        {panel(2, <>
          <ol className="fl-env-path" aria-label="How a build reaches each environment">
            {envs.map(e => <li key={e.name}><b>{e.name}</b><span>{!e.promoteFrom ? 'every passing build of the default branch'
              : gated(e.name) ? `promoted from ${e.promoteFrom}, after an approver accepts` : `when someone presses Promote on ${e.promoteFrom}`}</span></li>)}
          </ol>
          <div className="form fl-form">
            <p className="hint" style={{ margin: '0 0 16px' }}>Initial values, editable later in the project's Settings.</p>
            <RuntimeFields value={form} onChange={v => setForm({ ...form, ...v })} />
            <p className="fl-defaults"><Icon name="settings" size={13} />Defaults: no env vars; Small size, {RUNTIME_DEFAULTS.cpuRequest} to {RUNTIME_DEFAULTS.cpuLimit} CPU and {RUNTIME_DEFAULTS.memoryRequest} to {RUNTIME_DEFAULTS.memoryLimit} memory per pod.</p>
          </div>
        </>)}

        {panel(3, <>
          <div className="fl-review">
            {summary.map(s => (
              <div key={s.step} className="fl-review-block">
                <div className="fl-review-head"><h3>{STEPS[s.step].label}</h3>
                  <button type="button" className="btn small ghost" onClick={() => go(s.step)} aria-label={`Edit ${STEPS[s.step].label.toLowerCase()}`}>Edit</button></div>
                <dl>{s.rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
              </div>
            ))}
          </div>
          <p className="fl-review-note"><Icon name="rocket" size={14} />Creating the project sets up its namespaces, GitOps files and Argo CD apps, then starts the first CI run.</p>
          {error && <p className="error" role="alert">{error}</p>}
        </>, (
          <button type="submit" className={`btn primary fl-create${busy ? ' busy' : ''}`} disabled={busy}>
            <Icon name="rocket" size={15} />{busy ? 'Creating…' : 'Create & run first CI'}
          </button>
        ))}
      </form>
    </>
  )
}
