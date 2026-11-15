import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead } from '../components/ui.jsx'

const EXAMPLES = [
  'How do I get a prod deployment approved?',
  'What does “Needs decision” mean and what should I do?',
  'Why does AIO take a few minutes to open in the morning?',
  'Can I deploy during a freeze window?',
]

/**
 * "Ask AIO": a read-only assistant grounded in the curated AIO docs. It cites its sources and cannot change anything.
 * The conversation lives only in this tab; the server keeps no copy of the text.
 */
export default function Assistant() {
  const [status, setStatus] = useState(null)
  const [turns, setTurns] = useState([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const end = useRef(null)

  useEffect(() => { api('/api/assistant/status').then(setStatus).catch(e => setError(e.message)) }, [])
  useEffect(() => { end.current?.scrollIntoView({ block: 'end' }) }, [turns, busy])

  async function ask(text) {
    const q = (text ?? question).trim()
    if (!q || busy) return
    setQuestion('')
    setError(null)
    const history = turns.slice(-6).map(t => ({ role: t.role, content: t.content }))
    setTurns(t => [...t, { role: 'user', content: q }])
    setBusy(true)
    try {
      const a = await api('/api/assistant/ask', { method: 'POST', body: { question: q, history } })
      setTurns(t => [...t, { role: 'assistant', content: a.answer, sources: a.sources, grounded: a.grounded }])
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  const off = status && !status.enabled

  return (
    <>
      <PageHead title="Ask AIO"
                sub="Answers from the AIO docs, with sources. Read-only: it explains, and you act on the page it names." />
      {off && <div className="card" role="status"><Empty icon="star" title="The assistant is turned off">
        An admin can enable it with an AI provider key (AIO_ASSISTANT_ENABLED). Until then, the docs answer the same questions.</Empty></div>}

      {status?.enabled && (
        <div className="card assistant">
          <div className="assistant-log" aria-live="polite">
            {turns.length === 0 && (
              <div className="assistant-examples">
                <p className="muted">Try one of these:</p>
                {EXAMPLES.map(x => <button key={x} className="btn ghost" onClick={() => ask(x)}>{x}</button>)}
              </div>
            )}
            {turns.map((t, i) => (
              <div key={i} className={`bubble ${t.role}`}>
                <div className="bubble-text">{t.content}</div>
                {t.role === 'assistant' && t.sources?.length > 0 && (
                  <div className="bubble-sources">
                    <span className="muted">{t.grounded ? 'Sources' : 'Related, not cited'}:</span>
                    <ol>{t.sources.map(s => <li key={s.n} value={s.n}>{s.ref}</li>)}</ol>
                  </div>
                )}
              </div>
            ))}
            {busy && <div className="bubble assistant muted">Looking it up…</div>}
            <div ref={end} />
          </div>
          {error && <p className="muted" role="alert">{error}</p>}
          <form className="assistant-ask" onSubmit={e => { e.preventDefault(); ask() }}>
            <label htmlFor="assistant-q" className="sr-only">Your question</label>
            <textarea id="assistant-q" rows={2} maxLength={2000} value={question} placeholder="Ask about builds, deployments, approvals…"
                      onChange={e => setQuestion(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask() } }} />
            <button className={`btn primary${busy ? ' busy' : ''}`} disabled={busy || !question.trim()} type="submit">
              <Icon name="arrow" size={14} />Ask</button>
          </form>
          <p className="muted assistant-note">
            Do not paste secrets: questions go to {status.provider || 'the AI provider'} ({status.model}) after redaction.
            Answers can be wrong; check the source before acting, especially for prod.
          </p>
        </div>
      )}
    </>
  )
}
