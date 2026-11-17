import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { PageHead } from '../components/ui.jsx'

const ACTION_LABEL = {
  trigger_ci: 'Start a CI run for project', rerun_run: 'Re-run CI run',
  test_connector: 'Test connector', enable_connector: 'Enable connector', rollback_connector: 'Roll back connector',
  disable_connector: 'Disable connector',
}

// Plain text with ``` code blocks; no HTML is ever injected
function Text({ value }) {
  return value.split(/```/).map((part, i) => i % 2
    ? <pre key={i} className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: '0.8rem' }}>{part.replace(/^[a-z]*\n/, '')}</pre>
    : <p key={i} style={{ whiteSpace: 'pre-wrap', margin: '4px 0' }}>{part}</p>)
}

export default function Assistant() {
  const [status, setStatus] = useState(null)
  const [conversationId, setConversationId] = useState(null)
  const [items, setItems] = useState([])        // {role, text, tools?, proposals?}
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const end = useRef(null)

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
      <PageHead title="Assistant" />
      <p className="muted">The assistant is off. An admin can turn it on by setting <code>ANTHROPIC_API_KEY</code> on the platform.</p>
    </>
  )

  return (
    <>
      <PageHead title="Assistant"
        sub={`Ask about your projects, CI runs, logs, code and connectors. It can only look; actions run after you approve them.${status?.model ? ` Model: ${status.model}.` : ''}`} />
      <div className="card" style={{ minHeight: 320, maxHeight: '60vh', overflowY: 'auto' }}>
        {items.length === 0 && <p className="muted">Try: “Why did the last CI run of my project fail?” or “Which connector should I use for logs?”</p>}
        {items.map((m, i) => (
          <div key={i} style={{ margin: '10px 0', textAlign: m.role === 'user' ? 'right' : 'left' }}>
            <div style={{ display: 'inline-block', maxWidth: '85%', textAlign: 'left', padding: '6px 10px', borderRadius: 8,
              background: m.role === 'user' ? 'var(--accent-soft, rgba(80,120,255,.12))' : 'transparent' }}>
              {m.role === 'error' ? <p className="error">{m.text}</p> : <Text value={m.text} />}
              {m.tools?.length > 0 && <div className="muted" style={{ fontSize: '0.75rem' }}>Looked at: {[...new Set(m.tools)].filter(t => t !== 'propose_action').join(', ')}</div>}
              {m.proposals?.map(p => (
                <div key={p.id} className="banner" style={{ marginTop: 6 }}>
                  <Icon name="zap" size={14} /> <b>{ACTION_LABEL[p.action]} {p.targetId}</b> - {p.reason}
                  <span style={{ marginLeft: 'auto', whiteSpace: 'nowrap' }}>
                    {p.status === 'PENDING' ? <>
                      <button className="btn primary" onClick={() => decide(p, true)}>Approve</button>{' '}
                      <button className="btn" onClick={() => decide(p, false)}>Reject</button>
                    </> : <span className="badge gray quiet">{p.status.toLowerCase()}</span>}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ))}
        {busy && <p className="muted">Thinking and looking things up…</p>}
        <div ref={end} />
      </div>
      <form onSubmit={send} style={{ display: 'flex', gap: 8, marginTop: 10 }}>
        <input style={{ flex: 1 }} value={input} onChange={e => setInput(e.target.value)} maxLength={8000}
          placeholder="Ask about a project, a failed run, a log line, a file…" disabled={busy} />
        <button className="btn primary" disabled={busy || !input.trim()}>Send</button>
        <button type="button" className="btn" onClick={reset} disabled={busy}>New chat</button>
      </form>
      <p className="muted" style={{ fontSize: '0.75rem' }}>
        Conversations go to the Claude API (Anthropic). Secrets in logs and files are removed before sending; conversations are kept in memory for 2 hours.
      </p>
    </>
  )
}
