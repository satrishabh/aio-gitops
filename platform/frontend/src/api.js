function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : ''
}

export async function api(path, { method = 'GET', body, text = false } = {}) {
  let res
  try {
    res = await fetch(path, {
      method,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfToken(),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new Error('Network error — platform unreachable')
  }
  if (res.status === 401) {
    if (window.location.pathname !== '/login') window.location.assign('/login')
    throw new Error('Not logged in')
  }
  const raw = await res.text()
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`
    try { msg = JSON.parse(raw).message || msg } catch { /* not json */ }
    if (res.status === 403 && !raw) msg = 'Forbidden — reload the page (session/CSRF token expired)'
    throw new Error(msg)
  }
  if (text) return raw
  return raw ? JSON.parse(raw) : null
}

export async function logout() {
  await fetch('/logout', { method: 'POST', credentials: 'include', headers: { 'X-XSRF-TOKEN': csrfToken() } })
  window.location.assign('/login')
}

export function fmtTime(t) {
  return t ? new Date(t).toLocaleString() : '—'
}

export function duration(start, end) {
  if (!start) return '—'
  const s = Math.max(0, Math.round(((end ? new Date(end) : new Date()) - new Date(start)) / 1000))
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`
}
