import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'
import { useAction } from '../hooks.jsx'

const ACTIVE = ['PENDING', 'RUNNING']

export default function RunDetail({ me }) {
  const { id } = useParams()
  const nav = useNavigate()
  const [data, setData] = useState(null)
  const [selected, setSelected] = useState(null)
  const [logs, setLogs] = useState('')
  const [error, setError] = useState('')
  const [live, setLive] = useState('connecting') // connecting | live | lost | done
  const [reason, setReason] = useState('')
  const [showRisk, setShowRisk] = useState(false)
  const logRef = useRef(null)
  const { busy, run } = useAction()

  // Initial load + live updates over SSE, reconnecting with backoff until the run is finished
  useEffect(() => {
    let es = null, timer = null, closed = false, attempt = 0
    setData(null); setSelected(null); setLogs(''); setError('')
    const load = () => api(`/api/runs/${id}`).then(d => { setData(d); return d })

    const connect = () => {
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
        // The server also closes the stream when the run ends; check before reconnecting
        load().then(d => {
          if (!ACTIVE.includes(d.run.status)) { setLive('done'); return }
          setLive('lost')
          timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempt++))
        }).catch(() => { setLive('lost'); timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempt++)) })
      }
    }

    load().then(d => { if (ACTIVE.includes(d.run.status)) connect(); else setLive('done') })
      .catch(e => setError(e.message))
    return () => { closed = true; clearTimeout(timer); if (es) es.close() }
  }, [id])

  // Auto-select the running (or last failed) step
  useEffect(() => {
    if (!data || selected) return
    const s = data.steps.find(x => x.status === 'RUNNING') || data.steps.find(x => x.status === 'FAILED')
    if (s) setSelected(s.id)
  }, [data, selected])

  // Poll logs of the selected step while it is running
  const selStatus = data?.steps.find(s => s.id === selected)?.status
  useEffect(() => {
    if (!selected) return
    let stop = false
    const load = () => api(`/api/runs/${id}/steps/${selected}/logs`, { text: true })
      .then(t => { if (!stop) setLogs(t) }).catch(e => { if (!stop) setLogs(`(logs unavailable: ${e.message})`) })
    load()
    const t = ['RUNNING', 'WAITING'].includes(selStatus) ? setInterval(load, 3000) : null
    return () => { stop = true; if (t) clearInterval(t) }
  }, [id, selected, selStatus])

  useEffect(() => { if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight }, [logs])

  if (!data) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  const { run: r, project, steps } = data
  const tasks = [...new Set(steps.map(s => s.taskName))]
  const finished = !ACTIVE.includes(r.status)
  const gateFailed = r.status === 'FAILED' && steps.some(s => s.taskName === 'scan' && s.stepName === 'gate' && s.status === 'FAILED')
  const btn = (key, cls = 'btn') => ({ className: `${cls}${busy === key ? ' busy' : ''}`, disabled: !!busy })

  async function rerun(acceptRiskReason) {
    const n = await run(acceptRiskReason ? 'risk' : 'rerun',
      () => api(`/api/runs/${id}/rerun`, { method: 'POST', body: acceptRiskReason ? { acceptRiskReason } : {} }),
      n => `Run #${n.id} started${acceptRiskReason ? ' with the scan gate skipped (risk accepted)' : ''}`)
    if (n) nav(`/runs/${n.id}`)
  }

  return (
    <>
      {live === 'lost' && <div className="banner">Live updates lost — reconnecting…</div>}
      <div className="card">
        <h2>
          <Link to={`/projects/${project.id}`}>{project.name}</Link> · run #{r.id} <Badge value={r.status} />
          {r.gateOverridden && <> <Badge value="RISK ACCEPTED" /></>}
          {live === 'live' && <span className="muted"> · live</span>}
        </h2>
        <div className="facts">
          <div><span>Trigger</span>{r.trigger} by {r.triggeredBy}{r.rerunOf && <> (re-run of <Link to={`/runs/${r.rerunOf}`}>#{r.rerunOf}</Link>)</>}</div>
          <div><span>Ref</span><code>{r.gitRef}</code></div>
          <div><span>Commit</span><code>{r.commitSha?.slice(0, 12) || '—'}</code></div>
          <div><span>Deploys to dev</span>{r.deploy ? 'yes' : 'no (PR)'}</div>
          <div><span>Started</span>{fmtTime(r.startedAt)}</div>
          <div><span>Duration</span>{duration(r.startedAt, r.finishedAt)}</div>
          <div><span>Image</span><code>{r.imageUrl || '—'}</code></div>
          <div><span>Digest</span><code>{r.imageDigest?.slice(0, 19) || '—'}</code></div>
          <div><span>Vulnerabilities</span><Vulns run={r} /></div>
          <div><span>Scan gate</span><code>{r.scanSeverity || 'HIGH,CRITICAL'}</code></div>
          <div><span>PipelineRun</span><code>{r.pipelineRunName || '—'}</code>{r.logsArchived && <span className="muted"> (pruned, logs archived)</span>}</div>
        </div>
        {r.overrideReason && <p className="muted">Risk accepted by {r.triggeredBy}: {r.overrideReason}</p>}
        {r.message && <p className={r.status === 'FAILED' ? 'error' : 'muted'}>{r.message}</p>}
        <div className="row inline">
          {!finished && (
            <button {...btn('cancel', 'btn danger')} onClick={() => run('cancel', () => api(`/api/runs/${id}/cancel`, { method: 'POST' }), 'Cancel requested')}>
              Cancel run
            </button>
          )}
          {finished && <button {...btn('rerun')} onClick={() => rerun(null)}>Re-run</button>}
          {finished && gateFailed && me?.approver && !showRisk && (
            <button className="btn" disabled={!!busy} onClick={() => setShowRisk(true)}>Accept risk &amp; re-run…</button>
          )}
        </div>
        {showRisk && (
          <div className="form">
            <label>Why is it OK to ship with these vulnerabilities? (recorded in the audit log)
              <textarea rows="2" value={reason} onChange={e => setReason(e.target.value)} />
            </label>
            <button {...btn('risk', 'btn danger')} disabled={!reason.trim() || !!busy} onClick={() => rerun(reason.trim())}>
              Re-run with gate skipped
            </button>{' '}
            <button className="btn" onClick={() => setShowRisk(false)}>Cancel</button>
          </div>
        )}
      </div>

      <div className="run-grid">
        <div className="card steps">
          <h3>Pipeline</h3>
          {tasks.length === 0 && <p className="muted">Waiting for Tekton to schedule tasks…</p>}
          {tasks.map(task => (
            <div key={task} className="task">
              <div className="task-name">{task}</div>
              {steps.filter(s => s.taskName === task).map(s => (
                <button key={s.id} className={`step ${selected === s.id ? 'active' : ''}`}
                        disabled={s.stepName === '-'} onClick={() => setSelected(s.id)}>
                  <Badge value={s.status} /> {s.stepName === '-' ? '(skipped)' : s.stepName}
                  <span className="muted"> {duration(s.startedAt, s.finishedAt)}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
        <div className="card logs">
          <h3>Logs {selected && <span className="muted">— {steps.find(s => s.id === selected)?.taskName}/{steps.find(s => s.id === selected)?.stepName}</span>}</h3>
          <pre ref={logRef}>{selected ? logs : 'Select a step to view its logs.'}</pre>
        </div>
      </div>
    </>
  )
}
