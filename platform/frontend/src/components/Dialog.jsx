import { useEffect, useId, useRef } from 'react'
import Icon from './Icon.jsx'

/**
 * Centred modal dialog: focus moves in, Tab stays inside, Esc and the backdrop close it (unless `busy`), and focus
 * returns to where it was. `title` labels it; `footer` holds the actions.
 */
export default function Dialog({ title, sub, onClose, busy, footer, children, wide, className = '' }) {
  const ref = useRef(null)
  const busyRef = useRef(busy)
  busyRef.current = busy
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  const id = useId()

  useEffect(() => {
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const box = ref.current
    const first = box.querySelector('[data-autofocus]') || box
    first.focus()
    const onKey = e => {
      if (e.key === 'Escape' && !busyRef.current) { e.preventDefault(); closeRef.current() }
      if (e.key !== 'Tab') return
      const controls = [...box.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')]
      const a = controls[0], z = controls[controls.length - 1]
      if (!a) { e.preventDefault(); box.focus(); return }
      if (e.shiftKey && (document.activeElement === a || document.activeElement === box)) { e.preventDefault(); z.focus() }
      else if (!e.shiftKey && (document.activeElement === z || document.activeElement === box)) { e.preventDefault(); a.focus() }
    }
    box.addEventListener('keydown', onKey)
    return () => { box.removeEventListener('keydown', onKey); document.body.style.overflow = overflow; previous?.focus?.() }
  }, [])

  return (
    // biome-ignore lint/a11y/noStaticElementInteractions: a backdrop click closes; Esc and the Close button are the keyboard paths
    <div className="dlg-backdrop" role="presentation" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose() }}>
      <div ref={ref} role="dialog" aria-modal="true" aria-labelledby={`${id}-t`} aria-describedby={sub ? `${id}-s` : undefined}
           tabIndex={-1} className={`dlg${wide ? ' wide' : ''} ${className}`}>
        <div className="dlg-head">
          <div>
            <h2 id={`${id}-t`}>{title}</h2>
            {sub && <p id={`${id}-s`} className="dlg-sub">{sub}</p>}
          </div>
          <button type="button" className="icon-btn dlg-close" aria-label="Close" onClick={onClose} disabled={busy}><Icon name="x" /></button>
        </div>
        <div className="dlg-body">{children}</div>
        {footer && <div className="dlg-foot">{footer}</div>}
      </div>
    </div>
  )
}
