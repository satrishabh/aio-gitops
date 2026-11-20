import Icon from './Icon.jsx'
import { useTheme } from '../theme.js'
import { resolveTheme } from '../appearance.js'

const ORDER = ['default', 'bright', 'dark']
const LABEL = { default: 'Default', bright: 'Bright', dark: 'Dark' }
const ICON = { default: 'sun', bright: 'star', dark: 'moon' }

/** One activation moves to the next theme: Default → Bright → Dark. */
export default function ThemeToggle() {
  const [pref, setPref] = useTheme()
  const current = resolveTheme(pref)
  const next = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length]
  return (
    <div className="appearance-dock">
      <button type="button" className="appearance-trigger" aria-label={`Theme: ${LABEL[current]}. Switch to ${LABEL[next]}`}
              title={`Switch to ${LABEL[next]} theme`} onClick={event => {
                setPref(next)
                if (event.detail > 0) event.currentTarget.blur()
              }}>
        <Icon name={ICON[current]} size={18} />
      </button>
    </div>
  )
}
