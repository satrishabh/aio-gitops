import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { Avatar, Empty, Loading, PageHead, Skeleton, Tabs, ago } from '../components/ui.jsx'

const CATEGORIES = [
  ['ui-dashboard', 'UI dashboards'], ['admin', 'Admin panels'], ['landing', 'Landing pages'],
  ['react-vite', 'React + Vite'], ['nextjs', 'Next.js'], ['vue', 'Vue'], ['spring-boot', 'Spring Boot'],
  ['fastapi', 'FastAPI'], ['go', 'Go'], ['node-express', 'Node / Express'],
]
const LANGS = ['', 'TypeScript', 'JavaScript', 'Java', 'Python', 'Go', 'Vue', 'HTML']
const STEPS = ['Create repository from template', 'Add Dockerfile if needed', 'Onboard project (GitOps + Argo CD)', 'Start first CI run']

/** Card-shaped placeholders matching a template card: avatar + name, description lines, tags, actions. */
function TemplatesSkeleton() {
  return (
    <Loading label="Loading templates" className="tpl-grid">
      {[0, 1, 2, 3, 4, 5].map(i => (
        <div key={i} className="card tpl sk-tpl">
          <div className="tpl-head"><Skeleton bare h={34} w={34} className="round" />
            <div className="sk-stack"><Skeleton bare h={13} w="60%" className="line" /><Skeleton bare h={10} w="35%" className="line" /></div></div>
          <div className="sk-stack"><Skeleton bare h={10} className="line" /><Skeleton bare h={10} w="85%" className="line" /></div>
          <div className="sk-row"><Skeleton bare h={20} w={70} className="line" /><Skeleton bare h={20} w={56} className="line" /></div>
          <div className="sk-row sk-end"><Skeleton bare h={30} w={110} /></div>
        </div>
      ))}
    </Loading>
  )
}

export default function Templates() {
  const [source, setSource] = useState('starters')
  const [category, setCategory] = useState('ui-dashboard')
  const [language, setLanguage] = useState('')
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [items, setItems] = useState(null)
  const [error, setError] = useState('')
  const [picked, setPicked] = useState(null)

  useEffect(() => {
    setItems(null); setError('')
    const p = new URLSearchParams({ source })
    if (source === 'community') {
      if (category) p.set('category', category)
      if (language) p.set('language', language)
      if (query) p.set('q', query)
    }
    api(`/api/templates?${p}`).then(setItems).catch(e => { setError(e.message); setItems([]) })
  }, [source, category, language, query])

  return (
    <>
      <PageHead title="Templates" sub="Start a new app instantly: pick a template, the platform creates the GitHub repo, wires CI/CD and deploys to dev." />
      <Tabs label="Template source" value={source} onChange={setSource} tabs={[
        { id: 'starters', label: 'AIO Starters', icon: 'zap' },
        { id: 'mine', label: 'Your templates', icon: 'github' },
        { id: 'community', label: 'Community (GitHub)', icon: 'search' },
      ]} />

      {source === 'community' && (
        <div className="card tpl-filters">
          <div className="chips">
            {CATEGORIES.map(([id, label]) => (
              <button key={id} type="button" aria-pressed={category === id} className={`chip${category === id ? ' active' : ''}`} onClick={() => setCategory(category === id ? '' : id)}>{label}</button>
            ))}
          </div>
          <form className="row tpl-search" onSubmit={e => { e.preventDefault(); setQuery(q) }}>
            <div className="search"><Icon name="search" size={15} />
              <input type="search" aria-label="Search open-source templates" placeholder="Search open-source templates, e.g. tailwind, shadcn, material…" value={q} onChange={e => setQ(e.target.value)} />
            </div>
            <select aria-label="Language" value={language} onChange={e => setLanguage(e.target.value)}>
              {LANGS.map(l => <option key={l} value={l}>{l || 'Any language'}</option>)}
            </select>
            <button className="btn">Search</button>
          </form>
          <p className="muted small-print tpl-note">
            Live results from GitHub repositories marked as templates, sorted by stars. Each keeps its own license — check it before use.
          </p>
        </div>
      )}

      {source === 'mine' && (
        <p className="muted tpl-note-mine">
          Repos you mark as templates with the <code>aio-template</code> topic. Use <b>Save as template</b> in a project's Settings to add one.
        </p>
      )}

      {error && <p className="error">{error}</p>}
      {items == null ? <TemplatesSkeleton />
        : items.length === 0 ? (
          <div className="card"><Empty icon={source === 'community' ? 'search' : 'layers'} title="No templates found">
            {source === 'mine' ? 'Save one of your projects as a template to reuse it here.' : 'Try another category or search term.'}
          </Empty></div>
        ) : (
          <div className="tpl-grid">
            {items.map(t => (
              <div key={t.fullName} className="card tpl">
                <div className="tpl-head">
                  {t.avatarUrl ? <img src={t.avatarUrl} alt="" /> : <Avatar name={t.name} size={34} />}
                  <div className="tpl-name">
                    <b title={t.fullName}>{t.source === 'starters' ? t.name.replace('aio-template-', '') : t.name}</b>
                    <span className="muted">{t.owner}</span>
                  </div>
                </div>
                <p className="tpl-desc">{t.description || 'No description.'}</p>
                <div className="tpl-meta">
                  {t.aioReady && <Badge tone="green" label="AIO-ready" />}
                  {t.language && <Badge tone="gray" label={t.language} quiet />}
                  {t.license && t.license !== 'NOASSERTION' && <Badge tone="gray" label={t.license} quiet />}
                  <span className="muted tpl-stats"><Icon name="star" size={12} /><span className="sr-only">Stars:</span> {t.stars} · {ago(t.pushedAt)}</span>
                </div>
                <div className="tpl-actions">
                  <a className="btn small ghost" href={t.url} target="_blank" rel="noreferrer"><Icon name="external" size={13} />View</a>
                  {t.homepage && <a className="btn small ghost" href={t.homepage} target="_blank" rel="noreferrer">Demo</a>}
                  <div className="spacer" />
                  <button className="btn small primary" onClick={() => setPicked(t)}><Icon name="rocket" size={13} />Use template</button>
                </div>
              </div>
            ))}
          </div>
        )}

      {picked && <UsePanel template={picked} onClose={() => setPicked(null)} />}
    </>
  )
}

function UsePanel({ template, onClose }) {
  const nav = useNavigate()
  const toast = useToast()
  const [ins, setIns] = useState(null)
  const [insErr, setInsErr] = useState('')
  const base = template.name.replace(/^aio-template-/, 'my-')
  const [form, setForm] = useState({ name: base, privateRepo: true, scanSeverity: 'HIGH,CRITICAL' })
  const [step, setStep] = useState(-1)
  const [error, setError] = useState('')

  useEffect(() => {
    api(`/api/templates/${template.fullName}/inspect`).then(setIns).catch(e => setInsErr(e.message))
  }, [template.fullName])

  async function create(e) {
    e.preventDefault()
    setError(''); setStep(0)
    // the server does all four steps in one call; advance the indicator while it works
    const timer = setInterval(() => setStep(s => Math.min(s + 1, 3)), 3500)
    try {
      const res = await api('/api/templates/create', { method: 'POST', body: { template: template.fullName, ...form } })
      clearInterval(timer); setStep(4)
      toast('ok', `${res.project.name} created on GitHub — first CI run started`)
      nav('/pipelines/ci')
    } catch (err) {
      clearInterval(timer); setStep(-1); setError(err.message); toast('error', err.message)
    }
  }

  const busy = step >= 0 && step < 4
  const drawerRef = useRef(null)
  const busyRef = useRef(busy)
  busyRef.current = busy
  useEffect(() => {
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const drawer = drawerRef.current
    drawer.focus()
    const onKey = e => {
      if (e.key === 'Escape' && !busyRef.current) { e.preventDefault(); onClose() }
      if (e.key !== 'Tab') return
      const controls = [...drawer.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]')]
      const first = controls[0], last = controls[controls.length - 1]
      if (!first) { e.preventDefault(); drawer.focus(); return }
      if (e.shiftKey && (document.activeElement === first || document.activeElement === drawer)) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && (document.activeElement === last || document.activeElement === drawer)) { e.preventDefault(); first.focus() }
    }
    drawer.addEventListener('keydown', onKey)
    return () => { drawer.removeEventListener('keydown', onKey); document.body.style.overflow = overflow; previous?.focus() }
  }, [])
  return (
    <div className="overlay" onClick={busy ? undefined : onClose}>
      <aside ref={drawerRef} role="dialog" aria-modal="true" aria-labelledby="template-dialog-title" tabIndex={-1} className="drawer" onClick={e => e.stopPropagation()}>
        <div className="card-head">
          <h3 id="template-dialog-title">Use {template.fullName}</h3>
          <button className="icon-btn" aria-label="Close template panel" onClick={onClose} disabled={busy}><Icon name="x" /></button>
        </div>
        {insErr && <p className="error">{insErr}</p>}
        {!ins && !insErr && <Skeleton h={70} />}
        {ins && (
          <div className="inspect">
            <div className="tpl-meta">
              <Badge tone="gray" label={ins.stack} quiet />
              {ins.hasDockerfile ? <Badge tone="green" label="Dockerfile included" />
                : ins.canContainerize ? <Badge tone="blue" label="Dockerfile will be added" quiet />
                  : <Badge tone="red" label="Not auto-deployable" />}
              {!ins.isTemplate && <Badge tone="red" label="not a template repo" />}
              {ins.license && <Badge tone="gray" label={ins.license} quiet />}
            </div>
            <p className="muted small-print">{ins.note}</p>
          </div>
        )}
        <form className="form tpl-form" onSubmit={create}>
          <label>New repository / project name
            <input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} disabled={busy} />
          </label>
          <label>Vulnerability gate
            <select value={form.scanSeverity} onChange={e => setForm({ ...form, scanSeverity: e.target.value })} disabled={busy}>
              <option value="HIGH,CRITICAL">Block on HIGH or CRITICAL (default)</option>
              <option value="CRITICAL">Block on CRITICAL only</option>
              <option value="NONE">Report only (never block)</option>
            </select>
          </label>
          <label className="check">
            <input type="checkbox" checked={form.privateRepo} onChange={e => setForm({ ...form, privateRepo: e.target.checked })} disabled={busy} />
            Private repository
          </label>
          <ol className="tpl-steps">
            {STEPS.map((s, i) => (
              <li key={s} className={step > i ? 'done' : step === i ? 'active' : ''}>
                <span>{step > i ? <Icon name="check" size={12} /> : i + 1}</span>{s}
              </li>
            ))}
          </ol>
          {error && <p className="error">{error}</p>}
          <button className={`btn primary block${busy ? ' busy' : ''}`} disabled={busy || !ins || !ins.canContainerize || !ins.isTemplate}>
            <Icon name="rocket" size={15} />{busy ? 'Creating…' : 'Create repo & deploy'}
          </button>
          <p className="muted small-print tpl-alt">
            Prefer an existing repo? <Link to="/new">Onboard it instead</Link>.
          </p>
        </form>
      </aside>
    </div>
  )
}
