/** 12840 -> "12.8K", 48210 -> "48.2K", 999 -> "999" */
export function compact(n) {
  if (Math.abs(n) >= 1e6) return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`
  if (Math.abs(n) >= 1e3) return `${(n / 1e3).toFixed(1).replace(/\.0$/, '')}K`
  return String(n)
}

/** 0.124 -> "12.4%" */
export function percent(x) {
  return `${(x * 100).toFixed(1).replace(/\.0$/, '')}%`
}
