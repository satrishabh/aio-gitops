import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { activity } from '../activity.js'
import { ago } from './ui.jsx'

/** Step state -> badge colour and label. Text always carries the meaning; colour only reinforces it. */
const STEP = {
  done: ['green', 'Done'], running: ['blue', 'Running'], pending: ['gray', 'Pending'], paused: ['amber', 'Needs decision'],
  failed: ['red', 'Failed'], skipped: ['gray', 'Skipped'], compensated: ['orange', 'Undone'], undoing: ['orange', 'Undoing'],
}

const NAMES = {
  'capture-current-tag': 'Record current version', 'commit-tag': 'Commit to GitOps', sync: 'Argo CD sync',
  'verify-healthy': 'Verify healthy', 'smoke-test': 'Smoke test',
}

/** Live polling only while the deployment is in flight. */
const isLive = s => ['RUNNING', 'COMPENSATING'].includes(s)

/**
 * Execution timeline of one promotion (pipeline view): the steps with their state, the event log, and, when the
 * deployment paused or its revert failed, the approver's decision buttons.
 */
export default function Execution({ promotionId, canDecide, onChanged }) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(null)

  const load = () => api(`/api/promotions/${promotionId}/execution`).then(d => { setData(d); setError(null) }).catch(e => setError(e.message))

  useEffect(() => { load() }, [promotionId])
  useEffect(() => {
    if (!isLive(data?.saga?.status)) return undefined
    const t = setInterval(() => activity.paused() || load(), 4000)
    return () => clearInterval(t)
  }, [data?.saga?.status])

  async function decide(action, question) {
    if (question && !window.confirm(question)) return
    setBusy(action)
    try {
      await api(`/api/promotions/${promotionId}/intervene`, { method: 'POST', body: { action } })
      await load()
      onChanged?.()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(null)
    }
  }

  if (error && !data) return <p className="muted" role="alert">{error}</p>
  if (!data) return <p className="muted">Loading execution…</p>
  if (!data.saga) return <p className="muted">Not started yet: this request has not been applied.</p>

  const { saga, steps, events } = data
  const paused = saga.status === 'PAUSED'
  const stuck = saga.status === 'FAILED'

  return (
    <div className="execution">
      <ol className="exec-steps" aria-label="Deployment steps">
        {steps.map((s, i) => {
          const [tone, label] = STEP[s.state] || ['gray', s.state]
          return (
            <li key={s.name} className={`exec-step ${s.state}`}>
              <span className="exec-index" aria-hidden="true">{i + 1}</span>
              <span className="exec-name">{NAMES[s.name] || s.name}</span>
              <span className={`badge ${tone} quiet`}>{label}</span>
            </li>
          )
        })}
      </ol>

      {(paused || stuck) && (
        <div className="exec-decision" role="group" aria-label="Decision needed">
          <p style={{ margin: '0 0 8px' }}>
            {paused ? <>A step failed. Decide what happens next; without a decision it rolls back {saga.nextAttemptAt ? `at ${new Date(saga.nextAttemptAt).toLocaleTimeString()}` : 'by itself'}.</>
              : <>The automatic revert failed. Retry it, or mark it resolved once you fixed it by hand.</>}
          </p>
          {saga.lastError && <code className="exec-error">{saga.lastError}</code>}
          {canDecide ? (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
              <button className={`btn primary${busy === 'retry' ? ' busy' : ''}`} disabled={!!busy} onClick={() => decide('retry')}>Retry step</button>
              {paused && <button className={`btn${busy === 'skip' ? ' busy' : ''}`} disabled={!!busy}
                onClick={() => decide('skip', 'Skip this step and treat it as passed? Only do this if you checked the app yourself.')}>Skip step</button>}
              {paused && <button className={`btn danger${busy === 'rollback' ? ' busy' : ''}`} disabled={!!busy}
                onClick={() => decide('rollback', 'Roll back to the previous version now?')}>Roll back</button>}
              {stuck && <button className={`btn${busy === 'resolve' ? ' busy' : ''}`} disabled={!!busy}
                onClick={() => decide('resolve', 'Mark as resolved? Confirm you restored the previous version by hand.')}>Mark resolved</button>}
            </div>
          ) : <p className="muted" style={{ margin: '8px 0 0' }}>An approver must decide.</p>}
          {error && <p className="muted" role="alert" style={{ margin: '8px 0 0' }}>{error}</p>}
        </div>
      )}

      <details className="exec-log">
        <summary>Event log ({events.length})</summary>
        <ul>
          {events.map((e, i) => (
            <li key={i}>
              <span className="muted" title={e.at}>{ago(e.at)}</span>{' '}
              <b>{e.outcome.replaceAll('_', ' ').toLowerCase()}</b>
              {e.step && <> · {NAMES[e.step] || e.step}</>}
              {e.message && <span className="muted"> — {e.message}</span>}
            </li>
          ))}
        </ul>
      </details>
    </div>
  )
}
