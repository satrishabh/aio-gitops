import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Skeleton, ago } from '../components/ui.jsx'
import { useAction } from '../hooks.jsx'
import { useToast } from '../components/Toast.jsx'

const ACTIVE = ['PENDING', 'RUNNING']
const TASK_ICON = { clone: 'git', test: 'check', 'build-push': 'box', scan: 'shield', 'deploy-dev': 'rocket' }

/** Aggregate step statuses into one status per task. */
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
  const cls = /\b(error|failed|fatal|exception)\b/.test(l) ? 'ln-err'
    : /\b(warn|warning|high|critical)\b/.test(l) ? 'ln-warn'
      : /\b(success|succeeded|passed|pushed|done)\b/.test(l) ? 'ln-ok' : ''
  return <span key={i} className={cls}>{line}{'\n'}</span>
}

export default function RunDetail({ me }) {
  const { id } = useParams()
  const nav = useNavigate()
  const toast = useToast()
  const [data, setData] = useState(null)
  const [selected, setSelected] = useState(null)
  const [logs, setLogs] = useState('')
  const [error, setError] = useState('')
  const [live, setLive] = useState('connecting') // connecting | live | lost | done
  const [reason, setReason] = useState('')
  const [showRisk, setShowRisk] = useState(false)
  const [follow, setFollow] = useState(true)
  const logRef = useRef(null)
  const { busy, run } = useAction()

  // Initial load + live updates over SSE, reconnecting with backoff until the run is finished
  useEffect(() => {
    let es = null, timer = null, closed = false, attempt = 0
    setData(null); setSelected(null); setLogs(''); setError('')
    const load = () => api(`/api/runs/${id}`).then(d => { setData(d); return d })
    const retry = () => { setLive('lost'); timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempt++)) }

    function connect() {
      if (closed) return
      es = new EventSource(`/api/runs/${id}/stream`, { withCredentials: true })
      es.onopen = () => { attempt = 0; setLive('live') }
      es.addEventListener('run', ev => {
        const upd = JSON.parse(ev.data)
        setData(d => (d ? { ...d, ...upd } : d))
        if (!ACTIVE.includes(upd.run?.status)) { setLive('done'); es.close() }
      })
      es.onerror = () => {
        es.close()
        if (closed) return
        load().then(d => { if (!ACTIVE.includes(d.run.status)) setLive('done'); else retry() }).catch(retry)
      }
    }

    load().then(d => { if (ACTIVE.includes(d.run.status)) connect(); else setLive('done') }).catch(e => setError(e.message))
    return () => { closed = true; clearTimeout(timer); if (es) es.close() }
  }, [id])

  // Auto-select the running (or failed) step
  useEffect(() => {
    if (!data || selected) return
    const s = data.steps.find(x => x.status === 'RUNNING') || data.steps.find(x => x.status === 'FAILED')
    if (s) setSelected(s.id)
  }, [data, selected])

  const selStep = data?.steps.find(s => s.id === selected)
  const selStatus = selStep?.status
  useEffect(() => {
    if (!selected) return
    let stop = false
    const load = () => api(`/api/runs/${id}/steps/${selected}/logs`, { text: true })
      .then(t => { if (!stop) setLogs(t) }).catch(e => { if (!stop) setLogs(`(logs unavailable: ${e.message})`) })
    load()
    const t = ['RUNNING', 'WAITING'].includes(selStatus) ? setInterval(load, 3000) : null
    return () => { stop = true; if (t) clearInterval(t) }
  }, [id, selected, selStatus])

  useEffect(() => { if (follow && logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight }, [logs, follow])

  const tasks = useMemo(() => {
    if (!data) return []
    const names = [...new Set(data.steps.map(s => s.taskName))]
    return names.map(n => {
      const steps = data.steps.filter(s => s.taskName === n)
      const starts = steps.map(s => s.startedAt).filter(Boolean).sort()
      const ends = steps.map(s => s.finishedAt).filter(Boolean).sort()
      return { name: n, steps, status: taskStatus(steps), start: starts[0], end: ends.length === steps.length ? ends[ends.length - 1] : null }
    })
  }, [data])

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={160} /><Skeleton h={90} /><Skeleton h={400} /></>
  const { run: r, project, steps } = data
  const finished = !ACTIVE.includes(r.status)
  const gateFailed = r.status === 'FAILED' && steps.some(s => s.taskName === 'scan' && s.stepName === 'gate' && s.status === 'FAILED')
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })

  async function rerun(acceptRiskReason) {
    const n = await run(acceptRiskReason ? 'risk' : 'rerun',
      () => api(`/api/runs/${id}/rerun`, { method: 'POST', body: acceptRiskReason ? { acceptRiskReason } : {} }),
      n => `Run #${n.id} started${acceptRiskReason ? ' with the scan gate skipped (risk accepted)' : ''}`)
    if (n) nav(`/runs/${n.id}`)
  }

  function download() {
    const blob = new Blob([logs], { type: 'text/plain' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `${project.name}-run${r.id}-${selStep?.taskName}-${selStep?.stepName}.log`
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return (
    <>
      {live === 'lost' && <div className="banner"><Icon name="refresh" size={15} />Live updates lost — reconnecting…</div>}

      <div className="card" style={{ padding: '22px 24px' }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap' }}>
          <Avatar name={project.name} size={46} />
          <div style={{ flex: 1, minWidth: 220 }}>
            <h1 style={{ margin: 0, fontSize: '1.4rem', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <Link to={`/projects/${project.id}`} style={{ color: 'var(--text)' }}>{project.name}</Link>
              <span className="muted">#{r.id}</span>
              <Badge value={r.status} />{r.gateOverridden && <Badge value="RISK ACCEPTED" />}
              {live === 'live' && <span className="live-dot">streaming</span>}
            </h1>
            <div className="muted" style={{ marginTop: 4 }}>
              {r.trigger.toLowerCase()} by <b style={{ color: 'var(--text-2)' }}>{r.triggeredBy}</b> · {ago(r.createdAt)}
              {r.rerunOf && <> · re-run of <Link to={`/runs/${r.rerunOf}`}>#{r.rerunOf}</Link></>}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            {!finished && <button {...btn('cancel', 'btn danger')} onClick={() => run('cancel', () => api(`/api/runs/${id}/cancel`, { method: 'POST' }), 'Cancel requested')}>
              <Icon name="stop" size={13} />Cancel</button>}
            {finished && <button {...btn('rerun', 'btn primary')} onClick={() => rerun(null)}><Icon name="refresh" size={14} />Re-run</button>}
            {finished && gateFailed && me?.approver && !showRisk && (
              <button className="btn" disabled={!!busy} onClick={() => setShowRisk(true)}><Icon name="bug" size={14} />Accept risk…</button>
            )}
          </div>
        </div>

        {showRisk && (
          <div className="form" style={{ maxWidth: 'none', marginTop: 16, padding: 14, borderRadius: 12, border: '1px solid rgba(248,113,113,0.35)', background: 'var(--red-bg)' }}>
            <label>Why is it acceptable to ship with these vulnerabilities? <span className="muted">(recorded in the audit log)</span>
              <textarea rows="2" value={reason} onChange={e => setReason(e.target.value)} />
            </label>
            <button {...btn('risk', 'btn danger')} disabled={!reason.trim() || !!busy} onClick={() => rerun(reason.trim())}>Re-run with gate skipped</button>{' '}
            <button className="btn ghost" onClick={() => setShowRisk(false)}>Cancel</button>
          </div>
        )}

        <div className="facts" style={{ marginTop: 18 }}>
          <div><span>Ref</span><code>{r.gitRef}</code></div>
          <div><span>Commit</span><code>{r.commitSha?.slice(0, 12) || '—'}</code></div>
          <div><span>Duration</span><span className="mono" style={{ display: 'inline', textTransform: 'none', letterSpacing: 0, color: 'var(--text)', fontSize: '0.9rem' }}>{duration(r.startedAt, r.finishedAt)}</span></div>
          <div><span>Vulnerabilities</span><Vulns run={r} /></div>
          <div><span>Image</span><code>{r.imageUrl ? r.imageUrl.split('/').pop() : '—'}</code></div>
          <div><span>Scan gate</span><code>{r.scanSeverity || 'HIGH,CRITICAL'}</code></div>
          <div><span>Deploys to dev</span>{r.deploy ? 'yes' : 'no (pull request)'}</div>
          <div><span>Started</span>{fmtTime(r.startedAt)}</div>
        </div>
        {r.overrideReason && <p className="muted">Risk accepted: “{r.overrideReason}”</p>}
        {r.message && r.status === 'FAILED' && <div className="banner" style={{ background: 'var(--red-bg)', color: '#fecaca', borderColor: 'rgba(248,113,113,0.35)', marginTop: 14, marginBottom: 0 }}>
          <Icon name="x" size={15} />{r.message}</div>}
      </div>

      <div className="card">
        <div className="card-head"><h3>Pipeline</h3><span className="muted">{tasks.filter(t => t.status === 'SUCCEEDED').length}/{tasks.length} stages complete</span></div>
        {tasks.length === 0 ? <p className="muted">Waiting for Tekton to schedule tasks…</p> : (
          <div className="pipeline">
            {tasks.map(t => (
              <div key={t.name} className={`stage ${t.status === 'SUCCEEDED' ? 'done' : ''}`}>
                <button className={`node ${t.status} ${selStep?.taskName === t.name ? 'active' : ''}`}
                        onClick={() => { const s = t.steps.find(x => x.stepName !== '-'); if (s) setSelected(s.id) }}>
                  <span className="ring"><Icon name={t.status === 'SUCCEEDED' ? 'check' : t.status === 'FAILED' ? 'x' : TASK_ICON[t.name] || 'zap'} size={14} stroke={2.5} /></span>
                  <span className="label"><b>{t.name.replace('-', ' ')}</b><span>{t.status === 'SKIPPED' ? 'skipped' : duration(t.start, t.end)}</span></span>
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="run-grid">
        <div className="card">
          <div className="card-head"><h3>Steps</h3></div>
          {tasks.map(t => (
            <div key={t.name} className="task">
              <div className="task-name">{t.name}</div>
              {t.steps.map(s => (
                <button key={s.id} className={`step ${selected === s.id ? 'active' : ''}`} disabled={s.stepName === '-'} onClick={() => setSelected(s.id)}>
                  <Badge value={s.status} label={s.stepName === '-' ? 'skipped' : s.stepName} />
                  <span className="dur">{s.stepName === '-' ? '' : duration(s.startedAt, s.finishedAt)}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
        <div className="terminal">
          <div className="terminal-bar">
            <i style={{ background: '#ff5f57' }} /><i style={{ background: '#febc2e' }} /><i style={{ background: '#28c840' }} />
            <span className="title">{selStep ? `${selStep.taskName} › ${selStep.stepName}` : 'logs'}</span>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="checkbox" checked={follow} onChange={e => setFollow(e.target.checked)} style={{ width: 14, height: 14, margin: 0, accentColor: '#7c5cff' }} />follow
            </label>
            <button className="icon-btn" title="Copy" disabled={!logs} onClick={() => navigator.clipboard.writeText(logs).then(() => toast('ok', 'Logs copied'))}><Icon name="list" size={14} /></button>
            <button className="icon-btn" title="Download" disabled={!logs} onClick={download}><Icon name="arrow" size={14} style={{ transform: 'rotate(90deg)' }} /></button>
          </div>
          <pre ref={logRef}>{selected ? (logs ? logs.split('\n').map(colorLine) : 'Waiting for output…') : 'Select a pipeline stage or step to view its logs.'}</pre>
        </div>
      </div>
    </>
  )
}
