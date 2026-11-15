import { useEffect, useState } from 'react'
import { api } from '../api.js'
import Icon from './Icon.jsx'

let statusPromise = null
/** Assistant status, fetched once per page load. */
const assistantStatus = () => (statusPromise ??= api('/api/assistant/status').catch(() => ({ enabled: false })))

/**
 * "Explain with AIO" (GenAI slice G2): asks the read-only assistant for the likely cause of one failed run or
 * deployment and what to try next. Renders nothing while the assistant is off.
 */
export default function ExplainFailure({ kind, id }) {
  const [status, setStatus] = useState(null)
  const [answer, setAnswer] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => { assistantStatus().then(setStatus) }, [])
  if (!status?.enabled) return null

  async function explain() {
    setBusy(true)
    setError(null)
    try {
      setAnswer(await api(`/api/assistant/explain/${kind}/${id}`, { method: 'POST' }))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="explain">
      {!answer && (
        <button className={`btn${busy ? ' busy' : ''}`} disabled={busy} onClick={explain}>
          <Icon name="star" size={14} />{busy ? 'Looking into it…' : 'Explain with AIO'}</button>
      )}
      {error && <p className="muted" role="alert">{error}</p>}
      {answer && (
        <div className="bubble assistant" aria-live="polite">
          <div className="bubble-text">{answer.answer}</div>
          {answer.sources?.length > 0 && (
            <div className="bubble-sources">
              <span className="muted">Sources:</span>
              <ol>{answer.sources.map(s => <li key={s.n} value={s.n}>{s.ref}</li>)}</ol>
            </div>
          )}
          <p className="muted assistant-note">
            AI suggestion from {status.sharesLogs ? 'the run’s status, steps and redacted last log lines' : 'the run’s status and steps (no logs)'}.
            It can be wrong and changes nothing; you decide what to do.
          </p>
        </div>
      )}
    </div>
  )
}
