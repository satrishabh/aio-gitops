import { useEffect, useState } from 'react'

const KEY = 'aio-theme'
const media = window.matchMedia('(prefers-color-scheme: dark)')

export const readPref = () => { try { return localStorage.getItem(KEY) || 'system' } catch { return 'system' } }

function apply(pref) {
  const dark = pref === 'dark' || (pref === 'system' && media.matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  document.querySelectorAll('meta[name="theme-color"]').forEach(m => m.setAttribute('content', dark ? '#000000' : '#f5f5f7'))
}

/** 'system' | 'light' | 'dark', persisted; 'system' tracks the OS setting live. */
export function useTheme() {
  const [pref, setPref] = useState(readPref)
  useEffect(() => {
    apply(pref)
    try { pref === 'system' ? localStorage.removeItem(KEY) : localStorage.setItem(KEY, pref) } catch { /* private mode */ }
    if (pref !== 'system') return
    const onChange = () => apply('system')
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [pref])
  return [pref, setPref]
}
