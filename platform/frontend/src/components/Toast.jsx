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

  return (
    <ToastCtx.Provider value={toast}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map(t => (
          <div key={t.id} className={`toast ${t.kind}`} onClick={() => dismiss(t.id)}>{t.text}</div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

export const useToast = () => useContext(ToastCtx)
