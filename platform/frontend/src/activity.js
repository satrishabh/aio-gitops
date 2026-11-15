/**
 * Decides when the UI may poll the backend. AIO, Argo CD and Tekton scale to zero after 30 minutes without
 * requests, so a forgotten tab that keeps polling keeps the whole stack (and its node capacity) awake all day.
 * Polling therefore pauses while the tab is hidden, or after IDLE_MS without any user input, and resumes on
 * the next interaction or when the tab becomes visible again.
 */
export const IDLE_MS = 15 * 60 * 1000

export function createActivity({ now = () => Date.now(), hidden = () => false, idleMs = IDLE_MS } = {}) {
  let last = now()
  const listeners = new Set()
  const idle = () => now() - last > idleMs
  return {
    /** True while polling should stop. */
    paused: () => hidden() || idle(),
    /** Record user input; wakes paused pollers if we were idle. */
    touch() {
      const wasPaused = hidden() || idle()
      last = now()
      if (wasPaused && !hidden()) listeners.forEach(f => f())
    },
    /** Tab became visible: counts as activity. */
    visible() {
      last = now()
      if (!hidden()) listeners.forEach(f => f())
    },
    /** Subscribe to "resume" events; returns an unsubscribe function. */
    onResume(f) {
      listeners.add(f)
      return () => listeners.delete(f)
    },
  }
}

const browser = typeof window !== 'undefined' && typeof document !== 'undefined'

/** The app-wide instance, wired to real browser events. */
export const activity = createActivity({ hidden: () => browser && document.hidden })

if (browser) {
  const touch = () => activity.touch()
  for (const e of ['pointerdown', 'keydown', 'wheel', 'touchstart', 'mousemove']) {
    window.addEventListener(e, touch, { passive: true })
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) activity.visible() })
}
