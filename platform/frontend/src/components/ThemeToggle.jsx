import { useEffect, useState } from 'react'
import Icon from './Icon.jsx'
import { useTheme } from '../theme.js'

/** One activation switches appearance; device appearance is used until overridden. */
export default function ThemeToggle() {
  const [pref, setPref] = useTheme()
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const update = () => setSystemDark(media.matches)
    update()
    if (media.addEventListener) media.addEventListener('change', update)
    else media.addListener(update)
    return () => {
      if (media.removeEventListener) media.removeEventListener('change', update)
      else media.removeListener(update)
    }
  }, [])
  const dark = pref === 'dark' || (pref === 'system' && systemDark)
  const next = dark ? 'light' : 'dark'
  return (
    <div className="appearance-dock">
      <button type="button" className="appearance-trigger" aria-label={`Switch to ${next} appearance`}
              title={`Switch to ${next} appearance`} onClick={event => {
                setPref(next)
                if (event.detail > 0) event.currentTarget.blur()
              }}>
        <Icon name={dark ? 'moon' : 'sun'} size={18} />
      </button>
    </div>
  )
}
