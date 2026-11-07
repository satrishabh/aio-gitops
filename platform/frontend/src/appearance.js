const KEY = 'aio-theme'
const valid = value => ['system', 'light', 'dark'].includes(value) ? value : 'system'
const media = window.matchMedia('(prefers-color-scheme: dark)')
const listeners = new Set()
export const readPref = () => {
  try { return valid(localStorage.getItem(KEY)) } catch { return 'system' }
}
let preference = readPref()
export const getAppearance = () => preference
function apply() {
  const dark = preference === 'dark' || (preference === 'system' && media.matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
  document.querySelectorAll('meta[name="theme-color"]').forEach(meta => {
    meta.setAttribute('content', dark ? '#000000' : '#f5f5f7')
  })
}
export function setAppearance(value) {
  preference = valid(value)
  try {
    if (preference === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, preference)
  } catch { /* Switching still works when storage is unavailable. */ }
  apply()
  listeners.forEach(listener => listener())
}
export function subscribeAppearance(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
const onSystemChange = () => { if (preference === 'system') apply() }
if (media.addEventListener) media.addEventListener('change', onSystemChange)
else media.addListener(onSystemChange)
window.addEventListener('storage', event => {
  if (event.key !== KEY && event.key !== null) return
  preference = readPref()
  apply()
  listeners.forEach(listener => listener())
})
apply()
