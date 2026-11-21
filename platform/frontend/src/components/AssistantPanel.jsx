import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from './Icon.jsx'
import AssistantChat from './AssistantChat.jsx'

/*
 * The Assistant on every page: a button in the top bar opens a panel on the right. The panel stays mounted while
 * closed, so the conversation survives navigation. Esc closes it and focus returns to the button; it does not trap
 * focus (it's a side panel next to the page, not a modal), but it moves focus into the message box when opened.
 */
export function AssistantButton({ open, onToggle, buttonRef }) {
  return (
    <button ref={buttonRef} type="button" className={`assistant-trigger${open ? ' open' : ''}`} onClick={onToggle}
            aria-expanded={open} aria-controls="assistant-panel" aria-keyshortcuts="Control+J Meta+J" title="Assistant (Ctrl J)">
      <Icon name="sparkle" size={16} /><span className="assistant-trigger-label">Assistant</span>
    </button>
  )
}

export default function AssistantPanel({ open, onClose, pageContext }) {
  const chat = useRef(null)
  const [status, setStatus] = useState(null)
  const [mounted, setMounted] = useState(open)
  useEffect(() => { if (open) setMounted(true) }, [open])
  useEffect(() => {
    if (!open) return
    const t = setTimeout(() => chat.current?.focus(), 60)
    const onKey = e => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => { clearTimeout(t); document.removeEventListener('keydown', onKey) }
  }, [open, onClose])

  if (!mounted) return null   // first open loads the status; until then nothing is fetched
  return (
    <aside id="assistant-panel" className={`assistant-panel${open ? ' open' : ''}`} aria-label="Assistant" aria-hidden={!open} inert={open ? undefined : ''}>
      <header className="assistant-panel-head">
        <span className="assistant-panel-title"><Icon name="sparkle" size={16} />Assistant</span>
        {status?.model && <span className="muted assistant-panel-model mono">{status.model}</span>}
        <span className="spacer" />
        {status?.enabled && <button type="button" className="icon-btn" onClick={() => chat.current?.reset()} title="New chat" aria-label="New chat"><Icon name="plus" size={16} /></button>}
        <Link to="/assistant" className="icon-btn" onClick={onClose} title="Open full page" aria-label="Open the Assistant as a full page"><Icon name="external" size={16} /></Link>
        <button type="button" className="icon-btn" onClick={onClose} title="Close (Esc)" aria-label="Close the Assistant"><Icon name="x" size={16} /></button>
      </header>
      <AssistantChat ref={chat} compact pageContext={pageContext} onStatus={setStatus} />
      <p className="muted assistant-panel-note"><Icon name="lock" size={11} />Sent to the Claude API (Anthropic); secrets are removed first. Kept 2 hours.</p>
    </aside>
  )
}
