// Appearance preference: 'default' | 'bright' | 'dark' | 'auto' (auto: device light → default, device dark → dark).
// <html data-theme> always holds the resolved theme. Kept in sync with the pre-paint script in index.html.
const KEY = 'aio-theme'
export const THEMES = ['default', 'bright', 'dark', 'auto']
const LEGACY = { light: 'default', system: 'auto' }
const THEME_COLOR = { default: '#F3F4F8', bright: '#FFFFFF', dark: '#0B0F14' }
const media = window.matchMedia('(prefers-color-scheme: dark)')
const listeners = new Set()

const valid = value => {
  const v = LEGACY[value] || value
  return THEMES.includes(v) ? v : 'default'
}
export const readPref = () => {
  try { return valid(localStorage.getItem(KEY)) } catch { return 'default' }
}
let preference = readPref()
export const getAppearance = () => preference
export const resolveTheme = pref => (pref === 'auto' ? (media.matches ? 'dark' : 'default') : pref)

function apply() {
  const theme = resolveTheme(preference)
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme === 'dark' ? 'dark' : 'light'
  document.querySelectorAll('meta[name="theme-color"]').forEach(meta => meta.setAttribute('content', THEME_COLOR[theme]))
}
export function setAppearance(value) {
  preference = valid(value)
  try {
    if (preference === 'default') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, preference)
  } catch { /* Switching still works when storage is unavailable. */ }
  apply()
  listeners.forEach(listener => listener())
}
export function subscribeAppearance(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
const onSystemChange = () => { if (preference === 'auto') { apply(); listeners.forEach(listener => listener()) } }
if (media.addEventListener) media.addEventListener('change', onSystemChange)
else media.addListener(onSystemChange)
window.addEventListener('storage', event => {
  if (event.key !== KEY && event.key !== null) return
  preference = readPref()
  apply()
  listeners.forEach(listener => listener())
})
apply()
