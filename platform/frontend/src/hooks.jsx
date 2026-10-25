import { useCallback, useEffect, useRef, useState } from 'react'
import { useToast } from './components/Toast.jsx'

/**
 * Polls fn() every `interval` ms. On failure it keeps the last good data, reports `stale`,
 * and backs off (x2 up to 60s) until the server answers again.
 */
export function usePoll(fn, interval, deps = []) {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [stale, setStale] = useState(false)
  const timer = useRef(null)
  const delay = useRef(interval)
  const fnRef = useRef(fn)
  fnRef.current = fn

  const tick = useCallback(async () => {
    clearTimeout(timer.current)
    try {
      setData(await fnRef.current())
      setError(''); setStale(false)
      delay.current = interval
    } catch (e) {
      setError(e.message); setStale(true)
      delay.current = Math.min(delay.current * 2, 60000)
    }
    timer.current = setTimeout(tick, delay.current)
  }, [interval])

  useEffect(() => {
    setData(null); delay.current = interval
    tick()
    return () => clearTimeout(timer.current)
  }, [tick, ...deps])

  return { data, error, stale, refresh: tick }
}

/**
 * Runs mutating actions with a busy key (to disable/spin the right button) and a toast for the result.
 * run('promote-dev', () => api(...), 'Promoted') -> resolves to the result, or undefined on error.
 */
export function useAction(after) {
  const toast = useToast()
  const [busy, setBusy] = useState(null)
  const run = useCallback(async (key, fn, okMsg) => {
    setBusy(key)
    try {
      const res = await fn()
      if (okMsg) toast('ok', typeof okMsg === 'function' ? okMsg(res) : okMsg)
      if (after) after()
      return res
    } catch (e) {
      toast('error', e.message || 'Request failed')
      return undefined
    } finally {
      setBusy(null)
    }
  }, [toast, after])
  return { busy, run }
}

export function Stale({ stale, error, onRetry }) {
  if (!stale) return null
  return (
    <div className="banner">
      Connection problem — showing last known data ({error}). Retrying automatically…{' '}
      <button className="btn small" onClick={onRetry}>Retry now</button>
    </div>
  )
}
