import { useSyncExternalStore } from 'react'
import { getAppearance, setAppearance, subscribeAppearance } from './appearance.js'
export { readPref } from './appearance.js'

/** Auto follows the device; manual choices persist across pages and tabs. */
export function useTheme() {
  return [useSyncExternalStore(subscribeAppearance, getAppearance), setAppearance]
}
