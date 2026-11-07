import { useRef } from 'react'
import Icon from './Icon.jsx'
import { useTheme } from '../theme.js'

const OPTIONS = [
  { id: 'system', icon: 'auto', text: 'Auto', label: 'Automatic — follow device appearance' },
  { id: 'light', icon: 'sun', text: 'Light', label: 'Light appearance' },
  { id: 'dark', icon: 'moon', text: 'Dark', label: 'Dark appearance' },
]

/** Appearance picker: a radio group styled as a compact segmented control. Arrow keys move the selection. */
export default function ThemeToggle() {
  const [pref, setPref] = useTheme()
  const ref = useRef(null)
  const onKey = e => {
    const i = OPTIONS.findIndex(o => o.id === pref)
    const d = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key]
    if (!d && e.key !== 'Home' && e.key !== 'End') return
    e.preventDefault()
    const next = OPTIONS[e.key === 'Home' ? 0 : e.key === 'End' ? OPTIONS.length - 1 : (i + d + OPTIONS.length) % OPTIONS.length]
    setPref(next.id)
    ref.current?.querySelector(`[data-id="${next.id}"]`)?.focus()
  }
  return (
    <div ref={ref} className="appearance" role="radiogroup" aria-label="Appearance" onKeyDown={onKey}>
      {OPTIONS.map(o => (
        <button key={o.id} type="button" data-id={o.id} role="radio" aria-checked={pref === o.id}
                aria-label={o.label} title={o.label} tabIndex={pref === o.id ? 0 : -1} onClick={() => setPref(o.id)}>
          <Icon name={o.icon} size={15} /><span>{o.text}</span>
        </button>
      ))}
    </div>
  )
}
