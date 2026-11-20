import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Avatar, Skeleton, ago } from '../components/ui.jsx'
import { useAction } from '../hooks.jsx'
import { useToast } from '../components/Toast.jsx'
import { DetailHead, Timeline } from '../components/flow/FlowParts.jsx'

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

/** The first log line that reads like an error, trimmed for the failure banner. */
function firstErrorLine(text) {
  const line = (text || '').split('\n').map(l => l.trim()).find(l => /\b(error|failed|fatal|exception)\b/i.test(l))
  return line ? (line.length > 240 ? `${line.slice(0, 240)}…` : line) : null
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
  const terminalRef = useRef(null)
  const [firstError, setFirstError] = useState(null)
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
  const pendingRelease = ['READY', 'RETRY_WAIT', 'WAITING_BUILD'].includes(data?.run?.releaseStatus) && data?.run?.status === 'SUCCEEDED'
  useEffect(() => {
    if (!pendingRelease) return
    let closed = false, loading = false
    const timer = setInterval(async () => {
      if (loading) return
      loading = true
      try { const update = await api(`/api/runs/${id}`); if (!closed) setData(update) } catch (e) { if (!closed) setError(e.message) }
      finally { loading = false }
    }, 5000)
    return () => { closed = true; clearInterval(timer) }
  }, [id, pendingRelease])
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
  // On failure, read the failed step's log once to show its first error line at the top of the page
  const failedId = data?.steps.find(s => s.status === 'FAILED' && s.stepName !== '-')?.id
  useEffect(() => {
    setFirstError(null)
    if (!failedId) return undefined
    let stop = false
    api(`/api/runs/${id}/steps/${failedId}/logs`, { text: true })
      .then(t => { if (!stop) setFirstError(firstErrorLine(t)) }).catch(() => {})
    return () => { stop = true }
  }, [id, failedId])

  /** Select a step and bring its log into view. */
  function openLog(stepId) {
    setSelected(stepId)
    requestAnimationFrame(() => {
      const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
      terminalRef.current?.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' })
      logRef.current?.focus({ preventScroll: true })
    })
  }

  if (!data) return error ? <p className="error">{error}</p> : <><Skeleton h={160} /><Skeleton h={90} /><Skeleton h={400} /></>
  const { run: r, project, steps } = data
  const finished = !ACTIVE.includes(r.status)
  const gateFailed = r.status === 'FAILED' && steps.some(s => s.taskName === 'scan' && s.stepName === 'gate' && s.status === 'FAILED')
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })
  const failedStep = steps.find(s => s.id === failedId)
  const done = tasks.filter(t => t.status === 'SUCCEEDED').length

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

      {r.status === 'FAILED' && (failedStep || r.message) && (
        <section className="fl-fail" role="alert" aria-labelledby="fail-h">
          <span className="fl-fail-mark" aria-hidden="true"><Icon name="x" size={18} stroke={3} /></span>
          <div className="fl-fail-body">
            <h2 id="fail-h">Failed{failedStep ? <> at <b>{failedStep.taskName} › {failedStep.stepName}</b></> : ''}</h2>
            {(firstError || r.message) && <code className="fl-fail-line">{firstError || r.message}</code>}
            {firstError && r.message && firstError !== r.message && <p>{r.message}</p>}
          </div>
          {failedStep && <button type="button" className="btn fl-fail-open" onClick={() => openLog(failedStep.id)}>
            <Icon name="terminal" size={14} />Open log</button>}
        </section>
      )}

      <DetailHead
        avatar={<Avatar name={project.name} size={46} />}
        title={<>
          <Link to={`/projects/${project.id}`} className="fl-title-link">{project.name}</Link>
          <span className="fl-title-id">#{r.id}</span>
          <Badge value={r.status} />{r.gateOverridden && <Badge value="RISK ACCEPTED" />}
          {live === 'live' && <span className="live-dot">streaming</span>}
        </>}
        sub={<div className="fl-sub-line">
          {r.trigger.toLowerCase()} by <b>{r.triggeredBy}</b> · {ago(r.createdAt)}
          {r.rerunOf && <> · re-run of <Link to={`/runs/${r.rerunOf}`}>#{r.rerunOf}</Link></>}
        </div>}
        below={<>
          {showRisk && (
            <div className="form fl-risk">
              <label>Why is it acceptable to ship with these vulnerabilities? <span className="muted">(recorded in the audit log)</span>
                <textarea rows="2" value={reason} onChange={e => setReason(e.target.value)} />
              </label>
              <button {...btn('risk', 'btn danger')} disabled={!reason.trim() || !!busy} onClick={() => rerun(reason.trim())}>Re-run with gate skipped</button>{' '}
              <button className="btn ghost" onClick={() => setShowRisk(false)}>Cancel</button>
            </div>
          )}
          <dl className="fl-facts">
            <div><dt>Ref</dt><dd><code>{r.gitRef}</code></dd></div>
            <div><dt>Commit</dt><dd><code>{r.commitSha?.slice(0, 12) || '—'}</code></dd></div>
            <div><dt>Duration</dt><dd className="mono">{duration(r.startedAt, r.finishedAt)}</dd></div>
            <div><dt>Vulnerabilities</dt><dd><Vulns run={r} /></dd></div>
            <div><dt>Image</dt><dd><code>{r.imageUrl ? r.imageUrl.split('/').pop() : '—'}</code></dd></div>
            <div><dt>Scan gate</dt><dd><code>{r.scanSeverity || 'HIGH,CRITICAL'}</code></dd></div>
            {r.deliveryMode === 'INDEPENDENT' && <div><dt>GitOps release</dt><dd>{r.releaseStatus || 'NOT_REQUESTED'}</dd></div>}
            {r.deliveryMode === 'INDEPENDENT' && <div><dt>Artifact trust</dt><dd>Normal · elevated verification pending</dd></div>}
            <div><dt>Deploys to dev</dt><dd>{r.deploy ? <Link to={`/projects/${project.id}/cd/dev`}>yes — view CD</Link> : 'no (pull request)'}</dd></div>
            <div><dt>Started</dt><dd>{fmtTime(r.startedAt)}</dd></div>
          </dl>
          {r.overrideReason && <p className="muted">Risk accepted: “{r.overrideReason}”</p>}
        </>}
      >
        {!finished && <button {...btn('cancel', 'btn danger')} onClick={() => run('cancel', () => api(`/api/runs/${id}/cancel`, { method: 'POST' }), 'Cancel requested')}>
          <Icon name="stop" size={13} />Cancel</button>}
        {finished && <button {...btn('rerun', 'btn primary')} onClick={() => rerun(null)}><Icon name="refresh" size={14} />Re-run</button>}
        {r.status === 'SUCCEEDED' && r.releaseStatus === 'BLOCKED' && <button {...btn('retry-release')} onClick={() => run('retry-release', async () => {
          const updated = await api(`/api/runs/${id}/release/retry`, { method: 'POST' }); setData(d => ({ ...d, run: updated }))
        }, 'Release queued without rebuilding')}>Retry GitOps release</button>}
        {finished && gateFailed && me?.approver && !showRisk && (
          <button className="btn" disabled={!!busy} onClick={() => setShowRisk(true)}><Icon name="bug" size={14} />Accept risk…</button>
        )}
      </DetailHead>

      <section className="fl-panel" aria-labelledby="pipeline-h">
        <div className="fl-panel-head">
          <h2 id="pipeline-h">Pipeline</h2>
          <span className="muted">{done} of {tasks.length} tasks passed · select a task to open its log</span>
        </div>
        {tasks.length === 0 ? <p className="muted">Waiting for Tekton to schedule tasks…</p> : (
          <Timeline label="Pipeline tasks" onSelect={t => openLog(t.stepId)} items={tasks.map(t => {
            const first = t.steps.find(x => x.stepName !== '-')
            return {
              key: t.name, name: t.name.replace('-', ' '), status: t.status, icon: TASK_ICON[t.name] || 'zap',
              meta: t.status === 'SKIPPED' ? null : duration(t.start, t.end), stepId: first?.id, disabled: !first,
              active: selStep?.taskName === t.name,
            }
          })} />
        )}
      </section>

      <div className="run-grid fl-run-grid">
        <section className="fl-panel fl-steps" aria-labelledby="steps-h">
          <div className="fl-panel-head"><h2 id="steps-h">Steps</h2></div>
          {tasks.map(t => (
            <div key={t.name} className="task">
              <div className="task-name">{t.name}</div>
              {t.steps.map(s => (
                <button key={s.id} className={`step ${selected === s.id ? 'active' : ''}`} aria-pressed={selected === s.id} disabled={s.stepName === '-'} onClick={() => setSelected(s.id)}>
                  <Badge value={s.status} label={s.stepName === '-' ? 'skipped' : s.stepName} />
                  <span className="dur">{s.stepName === '-' ? '' : duration(s.startedAt, s.finishedAt)}</span>
                </button>
              ))}
            </div>
          ))}
        </section>
        <div className="terminal" ref={terminalRef}>
          <div className="terminal-bar">
            <i style={{ background: '#ff5f57' }} /><i style={{ background: '#febc2e' }} /><i style={{ background: '#28c840' }} />
            <span className="title">{selStep ? `${selStep.taskName} › ${selStep.stepName}` : 'logs'}</span>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="checkbox" checked={follow} onChange={e => setFollow(e.target.checked)} style={{ width: 14, height: 14, margin: 0, accentColor: 'var(--accent)' }} />follow
            </label>
            <button className="icon-btn" title="Copy" aria-label="Copy logs" disabled={!logs} onClick={() => navigator.clipboard.writeText(logs).then(() => toast('ok', 'Logs copied'))}><Icon name="list" size={14} /></button>
            <button className="icon-btn" title="Download" aria-label="Download logs" disabled={!logs} onClick={download}><Icon name="arrow" size={14} style={{ transform: 'rotate(90deg)' }} /></button>
          </div>
          <pre ref={logRef} tabIndex={0} aria-label={selStep ? `Log of ${selStep.taskName} ${selStep.stepName}` : 'Log'}>{selected ? (logs ? logs.split('\n').map(colorLine) : 'Waiting for output…') : 'Select a pipeline stage or step to view its logs.'}</pre>
        </div>
      </div>
    </>
  )
}
