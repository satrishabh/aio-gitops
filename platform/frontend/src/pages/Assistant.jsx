import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import Badge from '../components/Badge.jsx'
import { Empty, EmptyArt, PageHead } from '../components/ui.jsx'

const ACTION_LABEL = {
  trigger_ci: 'Start a CI run for project', rerun_run: 'Re-run CI run',
  test_connector: 'Test connector', enable_connector: 'Enable connector', rollback_connector: 'Roll back connector',
  disable_connector: 'Disable connector',
}

const EXAMPLES = ['Why did the last CI run of my project fail?', 'Which connector should I use for logs?']
const WHO = { user: 'You', assistant: 'Assistant', error: 'Error' }

// Plain text with ``` code blocks; no HTML is ever injected
function Text({ value }) {
  return value.split(/```/).map((part, i) => i % 2
    ? <pre key={i} className="mono chat-code">{part.replace(/^[a-z]*\n/, '')}</pre>
    : part.trim() && <p key={i} className="chat-p">{part.replace(/^\n+|\n+$/g, '')}</p>)
}

export default function Assistant() {
  const [status, setStatus] = useState(null)
  const [conversationId, setConversationId] = useState(null)
  const [items, setItems] = useState([])        // {role, text, tools?, proposals?}
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const end = useRef(null)
  const field = useRef(null)

  useEffect(() => { api('/api/assistant/status').then(setStatus).catch(() => setStatus({ enabled: false })) }, [])
  useEffect(() => { end.current?.scrollIntoView({ behavior: 'smooth' }) }, [items, busy])

  async function send(e) {
    e.preventDefault()
    const message = input.trim()
    if (!message || busy) return
    setInput('')
    setItems(it => [...it, { role: 'user', text: message }])
    setBusy(true)
    try {
      const r = await api('/api/assistant/chat', { method: 'POST', body: { conversationId, message } })
      setConversationId(r.conversationId)
      setItems(it => [...it, { role: 'assistant', text: r.text, tools: r.toolsUsed, proposals: r.proposals }])
    } catch (err) {
      setItems(it => [...it, { role: 'error', text: err.message }])
    } finally {
      setBusy(false)
    }
  }

  async function decide(p, approve) {
    if (approve && !window.confirm(`${ACTION_LABEL[p.action]} ${p.targetId}?\n\n${p.reason}`)) return
    try {
      const r = await api(`/api/assistant/${conversationId}/proposals/${p.id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' })
      const status = approve ? r.proposal.status : r.status
      setItems(it => it.map(m => ({ ...m, proposals: m.proposals?.map(x => x.id === p.id ? { ...x, status } : x) })))
      toast('ok', approve ? `${ACTION_LABEL[p.action]} ${p.targetId}: started` : 'Proposal rejected')
    } catch (err) {
      toast('error', err.message)
    }
  }

  function reset() {
    if (conversationId) api(`/api/assistant/${conversationId}`, { method: 'DELETE' }).catch(() => {})
    setConversationId(null)
    setItems([])
  }

  if (status && !status.enabled) return (
    <>
      <PageHead title="Assistant" sub="Ask about your projects, CI runs, logs, code and connectors." />
      <div className="card"><Empty icon="chat" title="The assistant is off">
        An admin can turn it on by setting <code>ANTHROPIC_API_KEY</code> on the platform.
      </Empty></div>
    </>
  )

  return (
    <>
      <PageHead title="Assistant"
        sub={`Ask about your projects, CI runs, logs, code and connectors. It can only look; actions run after you approve them.${status?.model ? ` Model: ${status.model}.` : ''}`}>
        <button type="button" className="btn" onClick={reset} disabled={busy}><Icon name="plus" size={14} />New chat</button>
      </PageHead>
      <section className="card chat" aria-label="Conversation">
        <div className="chat-log" role="log" aria-live="polite" aria-busy={busy}>
          {items.length === 0 && (
            <div className="chat-intro">
              <EmptyArt icon="chat" />
              <h3>What do you want to know?</h3>
              <p className="muted">It reads projects, runs, logs and code for you. Try one of these:</p>
              <div className="chips chat-examples">
                {EXAMPLES.map(q => (
                  <button key={q} type="button" className="chip" onClick={() => { setInput(q); field.current?.focus() }}>{q}</button>
                ))}
              </div>
            </div>
          )}
          {items.map((m, i) => (
            <div key={i} className={`msg msg-${m.role}`}>
              <span className="msg-avatar" aria-hidden="true"><Icon name={m.role === 'user' ? 'user' : m.role === 'error' ? 'alert' : 'sparkle'} size={15} /></span>
              <div className="msg-body">
                <span className="sr-only">{WHO[m.role]}:</span>
                <div className="msg-bubble">
                  {m.role === 'error' ? <p className="error">{m.text}</p> : <Text value={m.text} />}
                </div>
                {m.tools?.length > 0 && <div className="msg-tools"><Icon name="search" size={12} />Looked at: {[...new Set(m.tools)].filter(t => t !== 'propose_action').join(', ')}</div>}
                {m.proposals?.map(p => (
                  <div key={p.id} className="proposal">
                    <span className="proposal-icon" aria-hidden="true"><Icon name="zap" size={15} /></span>
                    <div className="proposal-text"><b>{ACTION_LABEL[p.action]} {p.targetId}</b><span className="muted">{p.reason}</span></div>
                    <div className="proposal-actions">
                      {p.status === 'PENDING' ? <>
                        <button className="btn primary small" onClick={() => decide(p, true)}><Icon name="check" size={13} />Approve</button>
                        <button className="btn small" onClick={() => decide(p, false)}>Reject</button>
                      </> : <Badge value={p.status} label={p.status.toLowerCase()} quiet />}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
          {busy && <div className="msg msg-assistant msg-busy"><span className="msg-avatar" aria-hidden="true"><Icon name="sparkle" size={15} /></span>
            <div className="msg-body"><div className="msg-bubble"><span className="typing" aria-hidden="true"><i /><i /><i /></span>Thinking and looking things up…</div></div></div>}
          <div ref={end} />
        </div>
        <form className="chat-compose" onSubmit={send}>
          <input ref={field} aria-label="Message the assistant" value={input} onChange={e => setInput(e.target.value)} maxLength={8000}
            placeholder="Ask about a project, a failed run, a log line, a file…" disabled={busy} />
          <button className="btn primary" disabled={busy || !input.trim()}><Icon name="send" size={14} />Send</button>
        </form>
      </section>
      <p className="muted small-print chat-note">
        <Icon name="lock" size={12} />Conversations go to the Claude API (Anthropic). Secrets in logs and files are removed before sending; conversations are kept in memory for 2 hours.
      </p>
    </>
  )
}
