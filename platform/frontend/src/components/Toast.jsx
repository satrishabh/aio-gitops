import { createContext, useCallback, useContext, useRef, useState } from 'react'

const ToastCtx = createContext(() => {})

/** Global toasts: toast('ok' | 'error' | 'info', text). Errors stay longer and can be dismissed. */
export function ToastProvider({ children }) {
  const [items, setItems] = useState([])
  const seq = useRef(0)

  const dismiss = useCallback(id => setItems(xs => xs.filter(x => x.id !== id)), [])
  const toast = useCallback((kind, text) => {
    const id = ++seq.current
    setItems(xs => [...xs.slice(-4), { id, kind, text }])
    setTimeout(() => dismiss(id), kind === 'error' ? 9000 : 4500)
  }, [dismiss])

  // Errors interrupt (assertive); everything else waits its turn (polite). Each toast is dismissable by keyboard.
  const render = kind => items.filter(t => (t.kind === 'error') === kind).map(t => (
    <div key={t.id} className={`toast ${t.kind}`}>
      <span style={{ flex: 1 }}>{t.text}</span>
      <button type="button" className="toast-x" aria-label="Dismiss notification" onClick={() => dismiss(t.id)}>×</button>
    </div>
  ))

  return (
    <ToastCtx.Provider value={toast}>
      {children}
      <div className="toasts">
        <div role="status" aria-live="polite" style={{ display: 'contents' }}>{render(false)}</div>
        <div role="alert" aria-live="assertive" style={{ display: 'contents' }}>{render(true)}</div>
      </div>
    </ToastCtx.Provider>
  )
}

export const useToast = () => useContext(ToastCtx)
