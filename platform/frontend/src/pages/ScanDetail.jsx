import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Skeleton, ago } from '../components/ui.jsx'
import { useAction } from '../hooks.jsx'
import { Counts, SCANNERS } from './Security.jsx'

const ACTIVE = ['PENDING', 'RUNNING']
const SETUP = ['clone', 'trivy-db']
const SCANNER_IDS = SCANNERS.map(s => s.id)
const LABEL = Object.fromEntries(SCANNERS.map(s => [s.id, s.label]))
const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']
const PAGE = 50

function taskStatus(steps) {
  const st = steps.map(s => s.status)
  if (st.includes('FAILED')) return 'FAILED'
  if (st.includes('RUNNING')) return 'RUNNING'
  if (st.every(s => s === 'SKIPPED')) return 'SKIPPED'
  if (st.every(s => s === 'SUCCEEDED' || s === 'SKIPPED')) return 'SUCCEEDED'
  return 'WAITING'
}

function colorLine(line, i) {
  const l = line.toLowerCase()
  const cls = /\b(error|failed|fatal)\b/.test(l) ? 'ln-err' : /\b(warn|high|critical)\b/.test(l) ? 'ln-warn'
    : /\b(success|succeeded|passed|done)\b/.test(l) ? 'ln-ok' : ''
  return <span key={i} className={cls}>{line}{'\n'}</span>
}

export default function ScanDetail() {
  const { id } = useParams()
  const nav = useNavigate()
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [live, setLive] = useState('connecting')
  const [selected, setSelected] = useState(null)
  const [logs, setLogs] = useState('')
  const logRef = useRef(null)
  const { busy, run } = useAction()

  // Initial load + SSE until the scan finishes (same reconnect strategy as CI runs)
  useEffect(() => {
    let es = null, timer = null, closed = false, attempt = 0
    setData(null); setSelected(null); setLogs(''); setError('')
    const load = () => api(`/api/scans/${id}`).then(d => { setData(d); return d })
    const retry = () => { setLive('lost'); timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempt++)) }
    function connect() {
      if (closed) return
      es = new EventSource(`/api/scans/${id}/stream`, { withCredentials: true })
      es.onopen = () => { attempt = 0; setLive('live') }
      es.addEventListener('scan', ev => {
        const upd = JSON.parse(ev.data)
        setData(d => (d ? { ...d, ...upd } : d))
        if (!ACTIVE.includes(upd.scan?.status)) { setLive('done'); es.close() }
      })
      es.onerror = () => {
        es.close()
        if (closed) return
        load().then(d => { if (!ACTIVE.includes(d.scan.status)) setLive('done'); else retry() }).catch(retry)
      }
    }
    load().then(d => { if (ACTIVE.includes(d.scan.status)) connect(); else setLive('done') }).catch(e => setError(e.message))
    return () => { closed = true; clearTimeout(timer); if (es) es.close() }
  }, [id])

  const steps = data?.steps || []
  useEffect(() => {
    if (selected) return
    const s = steps.find(x => x.status === 'RUNNING') || steps.find(x => x.status === 'FAILED')
    if (s) setSelected(s.id)
  }, [steps, selected])

  const selStep = steps.find(s => s.id === selected)
  const selStatus = selStep?.status
  useEffect(() => {
    if (!selected) return
    let stop = false
    const load = () => api(`/api/scans/${id}/steps/${selected}/logs`, { text: true })
      .then(t => { if (!stop) setLogs(t) }).catch(e => { if (!stop) setLogs(`(logs unavailable: ${e.message})`) })
    load()
    const t = ['RUNNING', 'WAITING'].includes(selStatus) ? setInterval(load, 3000) : null
    return () => { stop = true; if (t) clearInterval(t) }
  }, [id, selected, selStatus])
  useEffect(() => { if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight }, [logs])

  const tasks = useMemo(() => {
    const names = [...new Set(steps.map(s => s.taskName))]
    return Object.fromEntries(names.map(n => {
      const ts = steps.filter(s => s.taskName === n)
      const starts = ts.map(s => s.startedAt).filter(Boolean).sort()
      const ends = ts.map(s => s.finishedAt).filter(Boolean).sort()
      return [n, { name: n, steps: ts, status: taskStatus(ts), start: starts[0], end: ends.length === ts.length ? ends[ends.length - 1] : null }]
    }))
  }, [steps])

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={160} /><Skeleton h={120} /><Skeleton h={400} /></>
  const { scan: s, summary = {}, project, repo } = data
  const finished = !ACTIVE.includes(s.status)
  const chosen = s.scanners.split(',')
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })

  async function rerun() {
    const n = await run('rerun', () => api(`/api/scans/${id}/rerun`, { method: 'POST' }), n => `Scan #${n.id} started`)
    if (n) nav(`/scans/${n.id}`)
  }

  const Node = ({ name, label, icon }) => {
    const t = tasks[name] || { status: chosen.includes(name) || SETUP.includes(name) ? 'WAITING' : 'SKIPPED', steps: [] }
    return (
      <button className={`node ${t.status} ${selStep?.taskName === name ? 'active' : ''}`}
              onClick={() => { const x = t.steps.find(y => y.stepName !== '-'); if (x) setSelected(x.id) }}>
        <span className="ring"><Icon name={t.status === 'SUCCEEDED' ? 'check' : t.status === 'FAILED' ? 'x' : icon} size={14} stroke={2.5} /></span>
        <span className="label"><b>{label}</b><span>{t.status === 'SKIPPED' ? 'skipped' : duration(t.start, t.end)}</span></span>
      </button>
    )
  }

  return (
    <>
      {live === 'lost' && <div className="banner"><Icon name="refresh" size={15} />Live updates lost — reconnecting…</div>}

      <div className="card" style={{ padding: '22px 24px' }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 220 }}>
            <h1 style={{ margin: 0, fontSize: '1.4rem', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <Icon name="shield" size={20} /><span>{repo}</span><span className="muted">scan #{s.id}</span>
              <Badge value={s.status} />{live === 'live' && <span className="live-dot">streaming</span>}
            </h1>
            <div className="muted" style={{ marginTop: 4 }}>
              {s.trigger?.toLowerCase()} by <b style={{ color: 'var(--text-2)' }}>{s.triggeredBy}</b> · {ago(s.createdAt)}
              {project && <> · project <Link to={`/projects/${project.id}`}>{project.name}</Link></>}
              {s.rerunOf && <> · re-run of <Link to={`/scans/${s.rerunOf}`}>#{s.rerunOf}</Link></>}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            {finished && <a className="btn" href={`/api/scans/${id}/report.json`} download><Icon name="arrow" size={14} style={{ transform: 'rotate(90deg)' }} />report.json</a>}
            {!finished && <button {...btn('cancel', 'btn danger')} onClick={() => run('cancel', () => api(`/api/scans/${id}/cancel`, { method: 'POST' }), 'Cancel requested')}>
              <Icon name="stop" size={13} />Cancel</button>}
            {finished && <button {...btn('rerun', 'btn primary')} onClick={rerun}><Icon name="refresh" size={14} />Re-run</button>}
          </div>
        </div>
        <div className="facts" style={{ marginTop: 18 }}>
          <div><span>Repository</span><a href={s.repoUrl.replace(/\.git$/, '')} target="_blank" rel="noreferrer noopener">{repo}</a></div>
          <div><span>Ref</span><code>{s.gitRef}</code></div>
          <div><span>Commit</span><code>{s.commitSha?.slice(0, 12) || '—'}</code></div>
          <div><span>Image</span><code>{s.image ? s.image.split('/').pop() : '—'}</code></div>
          <div><span>Findings</span><Counts summary={summary} /></div>
          <div><span>Result</span><code>{s.failOn === 'NONE' ? 'report only' : `fail on ${s.failOn}`}</code></div>
          <div><span>Duration</span>{duration(s.startedAt, s.finishedAt)}</div>
          <div><span>Started</span>{fmtTime(s.startedAt)}</div>
        </div>
        {s.message && s.status !== 'SUCCEEDED' && <div className="banner" style={{ background: 'var(--red-bg)', color: 'var(--red)', marginTop: 14, marginBottom: 0 }}>
          <Icon name="x" size={15} />{s.message}</div>}
      </div>

      <div className="card">
        <div className="card-head"><h3>Pipeline</h3><span className="muted">scanners run in parallel</span></div>
        <div className="pipeline">
          <div className={`stage ${tasks.clone?.status === 'SUCCEEDED' ? 'done' : ''}`}><Node name="clone" label="clone" icon="git" /></div>
          <div className={`stage ${tasks['trivy-db']?.status === 'SUCCEEDED' ? 'done' : ''}`}><Node name="trivy-db" label="trivy db" icon="box" /></div>
          <div className="stage parallel">
            {SCANNER_IDS.filter(n => chosen.includes(n)).map(n => <Node key={n} name={n} label={LABEL[n]} icon="shield" />)}
          </div>
        </div>
      </div>

      <div className="scan-cards">
        {SCANNER_IDS.filter(n => chosen.includes(n)).map(n => (
          <div key={n} className="card scan-card">
            <div className="card-head"><h3>{LABEL[n]}</h3><Badge value={tasks[n]?.status || 'WAITING'} /></div>
            {summary[n] ? <Counts summary={{ [n]: summary[n] }} /> : <span className="muted">no results yet</span>}
          </div>
        ))}
      </div>

      <div className="run-grid">
        <div className="card">
          <div className="card-head"><h3>Steps</h3></div>
          {Object.values(tasks).map(t => (
            <div key={t.name} className="task">
              <div className="task-name">{LABEL[t.name] || t.name}</div>
              {t.steps.map(x => (
                <button key={x.id} className={`step ${selected === x.id ? 'active' : ''}`} disabled={x.stepName === '-'} onClick={() => setSelected(x.id)}>
                  <Badge value={x.status} label={x.stepName === '-' ? 'skipped' : x.stepName} />
                  <span className="dur">{x.stepName === '-' ? '' : duration(x.startedAt, x.finishedAt)}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
        <div className="terminal">
          <div className="terminal-bar">
            <i style={{ background: '#ff5f57' }} /><i style={{ background: '#febc2e' }} /><i style={{ background: '#28c840' }} />
            <span className="title">{selStep ? `${selStep.taskName} › ${selStep.stepName}` : 'logs'}</span>
          </div>
          <pre ref={logRef}>{selected ? (logs ? logs.split('\n').map(colorLine) : 'Waiting for output…') : 'Select a stage or step to view its logs.'}</pre>
        </div>
      </div>

      {finished && <Findings id={id} scanners={chosen} truncated={s.findingsTruncated} />}
    </>
  )
}

function Findings({ id, scanners, truncated }) {
  const [f, setF] = useState({ scanner: '', severity: '', q: '' })
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [res, setRes] = useState(null)
  const [error, setError] = useState('')

  useEffect(() => { const t = setTimeout(() => { setF(x => ({ ...x, q })); setPage(0) }, 300); return () => clearTimeout(t) }, [q])
  useEffect(() => {
    const p = new URLSearchParams({ scanner: f.scanner, severity: f.severity, q: f.q, page, size: PAGE })
    api(`/api/scans/${id}/findings?${p}`).then(r => { setRes(r); setError('') }).catch(e => setError(e.message))
  }, [id, f, page])
  const set = k => e => { setF({ ...f, [k]: e.target.value }); setPage(0) }

  return (
    <div className="card flush">
      <div className="card-head" style={{ padding: '16px 18px 0', flexWrap: 'wrap', gap: 10 }}>
        <h3>Findings {res && <span className="muted">({res.total})</span>}</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <select aria-label="Scanner" value={f.scanner} onChange={set('scanner')}>
            <option value="">All scanners</option>{scanners.map(n => <option key={n} value={n}>{LABEL[n]}</option>)}</select>
          <select aria-label="Severity" value={f.severity} onChange={set('severity')}>
            <option value="">All severities</option>{SEVERITIES.map(v => <option key={v}>{v}</option>)}</select>
          <input type="search" aria-label="Search findings" placeholder="CVE, package, file…" value={q} onChange={e => setQ(e.target.value)} />
        </div>
      </div>
      {truncated && <p className="hint" style={{ padding: '0 18px' }}>Only the first 5,000 findings were stored — download report.json for the full list.</p>}
      {error && <p className="error" style={{ padding: '0 18px' }}>{error}</p>}
      {!res ? <Skeleton h={200} /> : res.items.length === 0 ? <p className="muted" style={{ padding: 18 }}>No findings match.</p> : (
        <table>
          <thead><tr><th>Severity</th><th>Rule</th><th>Package / location</th><th>Installed → fixed</th><th>Title</th><th>Scanner</th></tr></thead>
          <tbody>
            {res.items.map(x => (
              <tr key={x.id}>
                <td><Badge value={x.severity} /></td>
                <td className="mono">{x.url && /^https?:\/\//.test(x.url) ? <a href={x.url} target="_blank" rel="noreferrer noopener">{x.ruleId}</a> : x.ruleId}</td>
                <td><code>{x.pkg || x.location || '—'}</code><div className="muted" style={{ fontSize: '0.72rem' }}>{x.target}{x.pkg && x.location ? ` · ${x.location}` : ''}</div></td>
                <td className="mono" style={{ fontSize: '0.8rem' }}>{x.installedVersion ? <>{x.installedVersion} → {x.fixedVersion || <span className="muted">no fix</span>}</> : '—'}</td>
                <td style={{ maxWidth: 420 }}>{x.title}</td>
                <td className="muted">{LABEL[x.scanner] || x.scanner}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {res && res.pages > 1 && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', padding: 14, justifyContent: 'flex-end' }}>
          <button className="btn small" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
          <span className="muted">page {page + 1} of {res.pages}</span>
          <button className="btn small" disabled={page + 1 >= res.pages} onClick={() => setPage(p => p + 1)}>Next</button>
        </div>
      )}
    </div>
  )
}
