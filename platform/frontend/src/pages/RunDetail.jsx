import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, duration, fmtTime } from '../api.js'
import Badge, { Vulns } from '../components/Badge.jsx'

export default function RunDetail() {
  const { id } = useParams()
  const [data, setData] = useState(null)
  const [selected, setSelected] = useState(null)
  const [logs, setLogs] = useState('')
  const [error, setError] = useState('')
  const logRef = useRef(null)

  // Initial load + live updates over SSE
  useEffect(() => {
    api(`/api/runs/${id}`).then(setData).catch(e => setError(e.message))
    const es = new EventSource(`/api/runs/${id}/stream`, { withCredentials: true })
    es.addEventListener('run', ev => {
      const upd = JSON.parse(ev.data)
      setData(d => (d ? { ...d, ...upd } : d))
    })
    es.onerror = () => es.close()
    return () => es.close()
  }, [id])

  // Auto-select the running (or last failed) step
  useEffect(() => {
    if (!data || selected) return
    const s = data.steps.find(x => x.status === 'RUNNING') || data.steps.find(x => x.status === 'FAILED')
    if (s) setSelected(s.id)
  }, [data, selected])

  // Poll logs of the selected step while it is running
  useEffect(() => {
    if (!selected) return
    let stop = false
    const load = () => api(`/api/runs/${id}/steps/${selected}/logs`, { text: true })
      .then(t => { if (!stop) setLogs(t) }).catch(e => setLogs(e.message))
    load()
    const step = data?.steps.find(s => s.id === selected)
    const t = step && ['RUNNING', 'WAITING'].includes(step.status) ? setInterval(load, 3000) : null
    return () => { stop = true; if (t) clearInterval(t) }
  }, [id, selected, data?.steps.find(s => s.id === selected)?.status])

  useEffect(() => { if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight }, [logs])

  if (!data) return error ? <p className="error">{error}</p> : <p>Loading…</p>
  const { run, project, steps } = data
  const tasks = [...new Set(steps.map(s => s.taskName))]

  return (
    <>
      <div className="card">
        <h2>
          <Link to={`/projects/${project.id}`}>{project.name}</Link> · run #{run.id} <Badge value={run.status} />
        </h2>
        <div className="facts">
          <div><span>Trigger</span>{run.trigger} by {run.triggeredBy}</div>
          <div><span>Ref</span><code>{run.gitRef}</code></div>
          <div><span>Commit</span><code>{run.commitSha?.slice(0, 12) || '—'}</code></div>
          <div><span>Deploys to dev</span>{run.deploy ? 'yes' : 'no (PR)'}</div>
          <div><span>Started</span>{fmtTime(run.startedAt)}</div>
          <div><span>Duration</span>{duration(run.startedAt, run.finishedAt)}</div>
          <div><span>Image</span><code>{run.imageUrl || '—'}</code></div>
          <div><span>Digest</span><code>{run.imageDigest?.slice(0, 19) || '—'}</code></div>
          <div><span>Vulnerabilities</span><Vulns run={run} /></div>
          <div><span>PipelineRun</span><code>{run.pipelineRunName || '—'}</code></div>
        </div>
        {run.message && <p className={run.status === 'FAILED' ? 'error' : 'muted'}>{run.message}</p>}
        {['PENDING', 'RUNNING'].includes(run.status) && (
          <button className="btn danger" onClick={() => api(`/api/runs/${id}/cancel`, { method: 'POST' }).catch(e => setError(e.message))}>
            Cancel run
          </button>
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
