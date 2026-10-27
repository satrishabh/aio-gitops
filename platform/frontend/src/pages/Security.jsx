import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, duration } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, Skeleton, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'

export const SCANNERS = [
  { id: 'vuln', label: 'Dependencies', hint: 'CVEs in lockfiles & manifests' },
  { id: 'secret', label: 'Secrets', hint: 'leaked keys, tokens, passwords' },
  { id: 'misconfig', label: 'Misconfig', hint: 'Dockerfile, Kubernetes, Helm, Terraform' },
  { id: 'license', label: 'Licenses', hint: 'package licenses (copyleft, unknown)' },
  { id: 'image', label: 'Image', hint: 'OS + app packages in a container image' },
]
const FAIL_ON = [['NONE', 'Report only'], ['CRITICAL', 'Fail on critical'], ['HIGH,CRITICAL', 'Fail on high + critical'],
  ['MEDIUM,HIGH,CRITICAL', 'Fail on medium and above']]

/** critical/high/medium/low totals across all scanners of a scan summary. */
export function totals(summary = {}) {
  const t = { critical: 0, high: 0, medium: 0, low: 0 }
  Object.values(summary).forEach(s => Object.keys(t).forEach(k => { t[k] += s?.[k] || 0 }))
  return t
}

export function Counts({ summary }) {
  const t = totals(summary)
  const v = (cls, n, label) => <span className={`vuln ${cls}${!n ? ' zero' : ''}`} title={`${n} ${label}`} aria-hidden="true">{cls.toUpperCase()}{n}</span>
  return <span className="vulns" role="img" aria-label={`${t.critical} critical, ${t.high} high, ${t.medium} medium, ${t.low} low`}>
    {v('c', t.critical, 'critical')}{v('h', t.high, 'high')}{v('m', t.medium, 'medium')}{v('l', t.low, 'low')}</span>
}

export default function Security() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/scans?limit=50'), 6000)
  const [params] = useSearchParams()
  const [open, setOpen] = useState(!!params.get('project') || !!params.get('repo'))
  return (
    <>
      <PageHead title="Security scans" sub="Trivy scans of any GitHub repository or image — independent of CI/CD. Scanners run in parallel.">
        <button className="btn primary" onClick={() => setOpen(o => !o)} aria-expanded={open}><Icon name="shield" size={14} />New scan</button>
      </PageHead>
      <Stale stale={stale} error={error} onRetry={refresh} />
      {open && <NewScan onClose={() => setOpen(false)} />}
      {!data ? <Skeleton h={260} /> : data.length === 0 ? (
        <Empty icon="shield" title="No scans yet" action={<button className="btn primary" onClick={() => setOpen(true)}>Scan a repository</button>}>
          Scan any public GitHub repository, or a private one the platform's token can read.
        </Empty>
      ) : (
        <div className="card flush">
          <table>
            <thead><tr><th>Scan</th><th>Repository</th><th>Status</th><th>Scanners</th><th>Findings</th><th>By</th><th>Duration</th></tr></thead>
            <tbody>
              {data.map(({ scan: s, summary, repo, project }) => (
                <tr key={s.id}>
                  <td><Link to={`/scans/${s.id}`}><b>#{s.id}</b></Link><div className="muted" style={{ fontSize: '0.75rem' }}>{ago(s.createdAt)}</div></td>
                  <td><code>{repo}</code> <span className="muted">@ {s.gitRef}</span>
                    {project && <div style={{ fontSize: '0.75rem' }}><Link to={`/projects/${project.id}`}>{project.name}</Link></div>}
                    {s.image && <div className="muted mono" style={{ fontSize: '0.72rem' }}>{s.image.split('/').pop()}</div>}</td>
                  <td><Badge value={s.status} /></td>
                  <td className="muted" style={{ fontSize: '0.8rem' }}>{s.scanners.split(',').join(' · ')}</td>
                  <td><Counts summary={summary} />{s.findingsCount != null && <div className="muted" style={{ fontSize: '0.72rem' }}>{s.findingsCount} findings</div>}</td>
                  <td className="muted">{s.triggeredBy}</td>
                  <td className="mono">{duration(s.startedAt, s.finishedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function NewScan({ onClose }) {
  const nav = useNavigate()
  const [params] = useSearchParams()
  const [projects, setProjects] = useState([])
  const [form, setForm] = useState({
    source: params.get('project') ? 'project' : 'repo', repoUrl: params.get('repo') || '', projectId: params.get('project') || '',
    ref: '', image: '', scanners: ['vuln', 'secret', 'misconfig'], failOn: 'NONE', ignoreUnfixed: false,
  })
  const { busy, run } = useAction()
  useEffect(() => { api('/api/projects').then(setProjects).catch(() => {}) }, [])

  // Picking a project pre-fills its latest CI image, so the image scanner can be ticked straight away
  const selected = projects.find(x => String(x.project.id) === String(form.projectId))
  useEffect(() => {
    if (form.source === 'project' && selected?.lastRun?.imageUrl && !form.image) setForm(f => ({ ...f, image: selected.lastRun.imageUrl }))
  }, [selected, form.source])   // eslint-disable-line react-hooks/exhaustive-deps

  const set = k => e => setForm({ ...form, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const toggle = id => setForm(f => ({ ...f, scanners: f.scanners.includes(id) ? f.scanners.filter(x => x !== id) : [...f.scanners, id] }))
  const imageNeeded = form.scanners.includes('image') && !form.image.trim()

  async function submit(e) {
    e.preventDefault()
    const body = {
      repoUrl: form.source === 'repo' ? form.repoUrl.trim() : null,
      projectId: form.source === 'project' ? Number(form.projectId) : null,
      ref: form.ref.trim() || null, image: form.image.trim() || null,
      scanners: form.scanners, failOn: form.failOn, ignoreUnfixed: form.ignoreUnfixed,
    }
    const s = await run('scan', () => api('/api/scans', { method: 'POST', body }), s => `Scan #${s.id} started`)
    if (s) nav(`/scans/${s.id}`)
  }

  return (
    <form className="card form" style={{ maxWidth: 'none' }} onSubmit={submit} aria-labelledby="newscan-h">
      <div className="card-head"><h3 id="newscan-h">New security scan</h3>
        <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}><Icon name="x" size={15} /></button></div>
      <div className="chips" role="radiogroup" aria-label="What to scan" style={{ marginBottom: 12 }}>
        {[['repo', 'Any GitHub repository'], ['project', 'A platform project']].map(([v, l]) => (
          <button key={v} type="button" role="radio" aria-checked={form.source === v} className={`chip${form.source === v ? ' active' : ''}`}
                  onClick={() => setForm({ ...form, source: v })}>{l}</button>
        ))}
      </div>
      <div className="row">
        {form.source === 'repo'
          ? <label>Repository<input required placeholder="https://github.com/owner/repo" value={form.repoUrl} onChange={set('repoUrl')} spellCheck="false" /></label>
          : <label>Project<select required value={form.projectId} onChange={set('projectId')}>
              <option value="">Choose…</option>
              {projects.map(x => <option key={x.project.id} value={x.project.id}>{x.project.name}</option>)}
            </select></label>}
        <label>Branch, tag or commit<input placeholder={form.source === 'project' && selected ? selected.project.defaultBranch : 'main'}
                                           value={form.ref} onChange={set('ref')} spellCheck="false" /></label>
      </div>
      <fieldset className="fieldset">
        <legend>Scanners <span className="muted">(run in parallel)</span></legend>
        <div className="scanner-grid">
          {SCANNERS.map(s => (
            <label key={s.id} className="check"><input type="checkbox" checked={form.scanners.includes(s.id)} onChange={() => toggle(s.id)} />
              <span><b>{s.label}</b> <span className="muted">— {s.hint}</span></span></label>
          ))}
        </div>
        {form.scanners.includes('image') && (
          <label>Image<input placeholder="ghcr.io/owner/app:tag" value={form.image} onChange={set('image')} spellCheck="false"
                             aria-invalid={imageNeeded} /></label>
        )}
      </fieldset>
      <div className="row">
        <label>Result<select value={form.failOn} onChange={set('failOn')}>{FAIL_ON.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <label className="check" style={{ alignSelf: 'end' }}><input type="checkbox" checked={form.ignoreUnfixed} onChange={set('ignoreUnfixed')} />
          Hide vulnerabilities without a fix</label>
      </div>
      <p className="hint">Private repositories work when the platform's GitHub token can read them. Secret values are never stored — only the rule, file and line.</p>
      <div style={{ display: 'flex', gap: 10 }}>
        <button type="submit" {...{ className: `btn primary${busy === 'scan' ? ' busy' : ''}` }} disabled={!!busy || !form.scanners.length || imageNeeded}>
          <Icon name="play" size={14} />Start scan</button>
        <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
      </div>
    </form>
  )
}
