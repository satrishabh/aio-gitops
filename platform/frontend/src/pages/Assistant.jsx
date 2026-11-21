import { useRef, useState } from 'react'
import Icon from '../components/Icon.jsx'
import AssistantChat from '../components/AssistantChat.jsx'
import { PageHead } from '../components/ui.jsx'

/** Full-page Assistant (/assistant). The same conversation is also on every page from the top-right Assistant button. */
export default function Assistant() {
  const chat = useRef(null)
  const [status, setStatus] = useState(null)
  return (
    <>
      <PageHead title="Assistant"
        sub={`Ask about your projects, CI runs, logs, code and connectors. It can only look; actions run after you approve them.${status?.model ? ` Model: ${status.model}.` : ''}`}>
        {status?.enabled && <button type="button" className="btn" onClick={() => chat.current?.reset()}><Icon name="plus" size={14} />New chat</button>}
      </PageHead>
      <AssistantChat ref={chat} onStatus={setStatus} />
      <p className="muted small-print chat-note">
        <Icon name="lock" size={12} />Conversations go to the Claude API (Anthropic). Secrets in logs and files are removed before sending; conversations are kept in memory for 2 hours.
      </p>
    </>
  )
}
