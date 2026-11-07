import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { api, fmtTime, logout } from '../api.js'
import { useTheme } from '../theme.js'
import Icon from './Icon.jsx'
import { ago } from './ui.jsx'

export default function AccountControls({ me }) {
  const [open, setOpen] = useState(null)
  const [theme, setTheme] = useTheme()
  const root = useRef(null)
  const panel = useRef(null)
  const opener = useRef(null)
  const id = useId()
  const location = useLocation()
  const community = me.accessTier === 'community'
  useEffect(() => { setOpen(null) }, [location.pathname])
  useEffect(() => {
    if (!open) return
    panel.current?.focus()
    const outside = event => { if (!root.current?.contains(event.target)) setOpen(null) }
    const escape = event => {
      if (event.key === 'Escape') { setOpen(null); opener.current?.focus() }
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])
  const toggle = (name, event) => {
    opener.current = event.currentTarget
    setOpen(value => value === name ? null : name)
  }
  return (
    <div ref={root} className="account-controls" onBlur={event => {
      if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) setOpen(null)
    }}>
      {!community && <button type="button" className="icon-btn account-button" aria-label="Notifications and audit activity"
        aria-expanded={open === 'activity'} aria-controls={`${id}-activity`} onClick={event => toggle('activity', event)}>
        <Icon name="bell" size={20} />
      </button>}
      <button type="button" className="icon-btn account-button profile-button" aria-label="Profile and settings"
        aria-expanded={open === 'profile'} aria-controls={`${id}-profile`} onClick={event => toggle('profile', event)}>
        {me.avatarUrl ? <img src={me.avatarUrl} alt="" /> : <Icon name="user" size={21} />}
      </button>
      {open && <section ref={panel} tabIndex={-1} id={`${id}-${open}`} className="account-panel"
        role="dialog" aria-label={open === 'profile' ? 'Profile and settings' : 'Notifications and audit activity'}>
        <div className="account-panel-head"><h2>{open === 'profile' ? 'Profile & settings' : 'Activity'}</h2>
          <button type="button" className="icon-btn" aria-label="Close panel" onClick={() => { setOpen(null); opener.current?.focus() }}><Icon name="x" /></button>
        </div>
        {open === 'profile' ? <>
          <div className="profile-summary">
            <strong>{me.name || me.login}</strong><span>@{me.login}</span>
            <span className="profile-tier">{me.accessTier || 'Member'}{me.approver ? ' · Production approver' : ''}</span>
          </div>
          <label className="profile-appearance">Appearance
            <select aria-label="Appearance" value={theme} onChange={event => setTheme(event.target.value)}>
              <option value="system">Use device appearance</option><option value="light">Light</option><option value="dark">Dark</option>
            </select>
          </label>
          <button type="button" className="btn account-logout" onClick={logout}><Icon name="logout" />Log out</button>
        </> : <AuditActivity />}
      </section>}
    </div>
  )
}

function AuditActivity() {
  const [events, setEvents] = useState(null)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let active = true
    setError('')
    const load = () => api('/api/audit').then(data => {
      if (active) { setEvents(data.slice(0, 8)); setError('') }
    }).catch(failure => { if (active) setError(failure.message) })
    load()
    const timer = setInterval(load, 15000)
    return () => { active = false; clearInterval(timer) }
  }, [retry])
  return <>
    <p className="activity-note">Recent audit activity</p>
    {error && <div role="alert" className="activity-error"><p>{error}</p><button className="btn small" onClick={() => setRetry(value => value + 1)}>Retry</button></div>}
    {!events && !error && <p role="status" className="activity-note">Loading activity…</p>}
    {events?.length === 0 && <p className="activity-note">No activity yet.</p>}
    {!!events?.length && <ul className="activity-list">{events.map(event => <li key={event.id}>
      <div><strong>{event.action.replaceAll('.', ' ')}</strong><time dateTime={event.createdAt} title={fmtTime(event.createdAt)}>{ago(event.createdAt)}</time></div>
      <p>{event.actor}{event.target ? ` · ${event.target}` : ''}</p>
      {event.details && <p className="activity-details">{event.details}</p>}
    </li>)}</ul>}
    <Link className="activity-all" to="/audit">View all audit logs<Icon name="arrow" size={15} /></Link>
  </>
}
