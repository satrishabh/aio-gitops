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
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = '/logout'
  const csrf = document.createElement('input')
  csrf.type = 'hidden'
  csrf.name = '_csrf'
  csrf.value = csrfToken()
  form.appendChild(csrf)
  document.body.appendChild(form)
  form.submit()
}

export function fmtTime(t) {
  return t ? new Date(t).toLocaleString() : '—'
}

export function duration(start, end) {
  if (!start) return '—'
  const s = Math.max(0, Math.round(((end ? new Date(end) : new Date()) - new Date(start)) / 1000))
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`
}

/**
 * Downloads a file from the API (same credentials and CSRF header as api()) and saves it through a blob URL.
 * Returns the file name used. Errors carry the server's message, as api() does.
 */
export async function download(path, fallbackName = 'download') {
  let res
  try {
    res = await fetch(path, { credentials: 'include', headers: { 'X-XSRF-TOKEN': csrfToken() } })
  } catch {
    throw new Error('Network error — platform unreachable')
  }
  if (res.status === 401) {
    if (window.location.pathname !== '/login') window.location.assign('/login')
    throw new Error('Not logged in')
  }
  if (!res.ok) {
    const raw = await res.text().catch(() => '')
    let msg = `${res.status} ${res.statusText}`
    try { msg = JSON.parse(raw).message || msg } catch { /* not json */ }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const cd = res.headers.get('Content-Disposition') || ''
  const m = cd.match(/filename\*=UTF-8''([^;]+)/i) || cd.match(/filename="?([^";]+)"?/i)
  let name = fallbackName
  if (m) { try { name = decodeURIComponent(m[1]) } catch { name = m[1] } }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
  return name
}
