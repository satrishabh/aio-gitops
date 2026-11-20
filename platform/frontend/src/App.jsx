import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { api, logout } from './api.js'
import { ToastProvider } from './components/Toast.jsx'
import Icon from './components/Icon.jsx'
import AccountControls from './components/AccountControls.jsx'
import CommandPalette from './components/CommandPalette.jsx'
import { LogoMark, Wordmark } from './components/Logo.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import Projects from './pages/Projects.jsx'
import NewProject from './pages/NewProject.jsx'
import ProjectDetail from './pages/ProjectDetail.jsx'
import RunDetail from './pages/RunDetail.jsx'
import CdDetail from './pages/CdDetail.jsx'
import Clusters from './pages/Clusters.jsx'
import Connectors from './pages/Connectors.jsx'
import Assistant from './pages/Assistant.jsx'
import Approvals from './pages/Approvals.jsx'
import Audit from './pages/Audit.jsx'
import Metrics from './pages/Metrics.jsx'
import Templates from './pages/Templates.jsx'
import PipelinesCi from './pages/PipelinesCi.jsx'
import PipelinesCd from './pages/PipelinesCd.jsx'
import Security from './pages/Security.jsx'
import ScanDetail from './pages/ScanDetail.jsx'
import NotFound from './pages/NotFound.jsx'

const CRUMBS = [
  [/^\/$/, 'Overview'], [/^\/projects$/, 'Projects'], [/^\/projects\/\d+\/cd\//, 'Projects / CD'], [/^\/projects\/\d+/, 'Projects / Project'],
  [/^\/runs\//, 'Pipelines / CI run'], [/^\/new/, 'New project'], [/^\/approvals/, 'Approvals'],
  [/^\/metrics/, 'Delivery metrics'], [/^\/audit/, 'Audit log'], [/^\/templates/, 'Templates'], [/^\/clusters/, 'Clusters'], [/^\/connectors/, 'Connectors'], [/^\/assistant/, 'Assistant'],
  [/^\/pipelines\/ci/, 'Pipelines / CI'], [/^\/pipelines\/cd/, 'Pipelines / CD'], [/^\/pipelines\/security/, 'Pipelines / Security'],
  [/^\/scans\//, 'Pipelines / Security scan'],
]

/** The five navigation groups, filtered by access tier exactly as the routes are. */
function navGroups(me, pending) {
  const community = me.accessTier === 'community'
  const admin = me.accessTier === 'admin'
  const groups = [
    { key: 'overview', label: 'Overview', match: /^\/$/, items: [{ to: '/', label: 'Overview', icon: 'home', end: true }] },
    { key: 'projects', label: 'Projects', match: /^\/(projects|new|templates|assistant)(\/|$)/, items: [
      { to: '/projects', label: 'Projects', icon: 'grid', also: /^\/projects\// },
      ...(community ? [] : [
        { to: '/new', label: 'New project', icon: 'plus' },
        { to: '/templates', label: 'Templates', icon: 'layers' },
        { to: '/assistant', label: 'Assistant', icon: 'zap' },
      ]),
    ] },
  ]
  if (!community) groups.push({ key: 'pipelines', label: 'Pipelines', match: /^\/(pipelines|runs|scans)(\/|$)/, items: [
    { to: '/pipelines/ci', label: 'CI', icon: 'zap', also: /^\/runs\// },
    { to: '/pipelines/cd', label: 'CD', icon: 'rocket' },
    { to: '/pipelines/security', label: 'Security', icon: 'shield', also: /^\/scans\// },
  ] })
  groups.push({ key: 'delivery', label: 'Delivery', badge: community ? 0 : pending, match: /^\/(approvals|metrics|audit|clusters)(\/|$)/, items: [
    ...(community ? [] : [{ to: '/approvals', label: 'Approvals', icon: 'shield', badge: pending }]),
    { to: '/metrics', label: 'Metrics', icon: 'chart' },
    ...(community ? [] : [{ to: '/audit', label: 'Audit log', icon: 'list' }]),
    ...(admin ? [{ to: '/clusters', label: 'Clusters', icon: 'layers' }] : []),
  ] })
  if (admin) groups.push({ key: 'connectors', label: 'Connectors', match: /^\/connectors(\/|$)/, items: [{ to: '/connectors', label: 'Connectors', icon: 'plug' }] })
  return groups
}

const Count = ({ n }) => (n > 0 ? <span className="count" aria-label={`${n} pending`}>{n}</span> : null)
const Chevron = () => <svg className="chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)

/** Desktop navigation: single-page groups are links; the others are disclosure buttons with a small menu. */
function TopNav({ groups, pathname }) {
  const [open, setOpen] = useState(null)
  const root = useRef(null)
  useEffect(() => { setOpen(null) }, [pathname])
  useEffect(() => {
    if (!open) return undefined
    const outside = event => { if (!root.current?.contains(event.target)) setOpen(null) }
    const escape = event => {
      if (event.key !== 'Escape') return
      setOpen(null)
      root.current?.querySelector(`[data-group="${open}"]`)?.focus()
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) }
  }, [open])
  return (
    <nav ref={root} className="topnav" aria-label="Main" onBlur={event => {
      if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) setOpen(null)
    }}>
      <ul>
        {groups.map(g => {
          const active = g.match.test(pathname)
          if (g.items.length === 1) {
            const item = g.items[0]
            return <li key={g.key}><NavLink to={item.to} end={item.end} className={active ? 'active' : ''}>{g.label}<Count n={g.badge} /></NavLink></li>
          }
          return (
            <li key={g.key} className="has-menu">
              <button type="button" data-group={g.key} className={active ? 'active' : ''} aria-expanded={open === g.key}
                      aria-controls={`topnav-${g.key}`} onClick={() => setOpen(o => (o === g.key ? null : g.key))}>
                {g.label}<Count n={g.badge} /><Chevron />
              </button>
              {open === g.key && <ul id={`topnav-${g.key}`} className="topnav-menu">
                {g.items.map(item => (
                  <li key={item.to}>
                    <NavLink to={item.to} end={item.end} className={({ isActive }) => (isActive || item.also?.test(pathname) ? 'active' : '')}>
                      <Icon name={item.icon} size={16} /><span>{item.label}</span><Count n={item.badge} />
                    </NavLink>
                  </li>
                ))}
              </ul>}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

export default function App() {
  const [me, setMe] = useState(null)
  const [sessionError, setSessionError] = useState('')
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(0)
  const [mobileNav, setMobileNav] = useState(false)
  const [palette, setPalette] = useState(false)
  const menuButton = useRef(null)
  const sheet = useRef(null)
  const location = useLocation()
  const isLogin = location.pathname === '/login'

  useEffect(() => { setMobileNav(false); setPalette(false) }, [location.pathname])
  useEffect(() => {
    if (!mobileNav) return undefined
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    sheet.current?.querySelector('a')?.focus()
    const onKey = e => {
      if (e.key === 'Escape') { setMobileNav(false); menuButton.current?.focus() }
      if (e.key !== 'Tab') return
      const controls = [...sheet.current.querySelectorAll('a, button')]
      const first = controls[0], last = controls.at(-1)
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
    }
    const onResize = () => { if (window.innerWidth > 760) setMobileNav(false) }
    window.addEventListener('keydown', onKey)
    window.addEventListener('resize', onResize)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('resize', onResize)
    }
  }, [mobileNav])

  // Ctrl K / ⌘K opens search from anywhere in the workspace.
  useEffect(() => {
    if (isLogin || !me) return undefined
    const onKey = e => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette(open => !open) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [isLogin, me])

  useEffect(() => {
    if (isLogin) { setLoading(false); return }
    setLoading(true)
    setSessionError('')
    api('/api/me').then(setMe).catch(e => setSessionError(e.message)).finally(() => setLoading(false))
  }, [isLogin])

  // Pending prod approvals badge in the navigation
  useEffect(() => {
    if (!me) return undefined
    if (me.accessTier === 'community') { setPending(0); return undefined }
    const load = () => api('/api/promotions?status=PENDING').then(x => setPending(x.length)).catch(() => {})
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [me, location.pathname])

  // On client-side navigation: name the page and move focus to the content, as a full page load would.
  const mainRef = useRef(null)
  const firstRender = useRef(true)
  const crumb = (CRUMBS.find(([re]) => re.test(location.pathname)) || [null, 'Page not found'])[1]
  useEffect(() => {
    document.title = crumb ? `${crumb.split(' / ').pop()} · AIO Platform` : 'AIO Platform'
    if (firstRender.current) { firstRender.current = false; return }
    mainRef.current?.focus({ preventScroll: true })
    window.scrollTo(0, 0)
  }, [location.pathname, crumb])

  const groups = useMemo(() => (me ? navGroups(me, pending) : []), [me, pending])
  const pages = useMemo(() => groups.flatMap(g => g.items.map(i => ({ to: i.to, label: i.label, icon: i.icon, group: g.label }))), [groups])

  if (isLogin) return <Login />
  if (loading) return <div className="center" role="status" aria-label="Loading AIO Platform"><LogoMark size={44} className="logo-mark pulse" /></div>
  if (!me) return <div className="center"><div className="card" role="alert"><h1>Unable to load your workspace</h1><p>{sessionError || 'Your session could not be loaded.'}</p><button className="btn primary" onClick={() => window.location.reload()}>Try again</button> <Link className="btn" to="/login">Go to login</Link></div></div>
  const community = me.accessTier === 'community'
  const crumbParts = crumb.split(' / ')
  const closeSheet = () => { setMobileNav(false); menuButton.current?.focus() }

  return (
    <ToastProvider>
      <a href="#main" className="skip-link">Skip to content</a>
      <div className="shell">
        {mobileNav && <>
          <button className="mobile-nav-backdrop" aria-label="Close navigation" onClick={closeSheet} />
          <aside ref={sheet} id="workspace-navigation" className="sidebar mobile-open" aria-label="Navigation">
            <button className="mobile-nav-close icon-btn" aria-label="Close navigation" onClick={closeSheet}><Icon name="x" /></button>
            <Link to="/" className="logo" aria-label="AIO Platform — overview"><Wordmark height={18} className="logo-mark" /></Link>
            <nav className="nav" aria-label="Main" onClick={e => { if (e.target.closest('a')) { setMobileNav(false); mainRef.current?.focus() } }}>
              {groups.map(g => <div key={g.key}>
                <div className="nav-section">{g.label}</div>
                {g.items.map(item => (
                  <NavLink key={item.to} to={item.to} end={item.end}
                           className={({ isActive }) => (isActive || item.also?.test(location.pathname) ? 'active' : '')}>
                    <Icon name={item.icon} />{item.label}<Count n={item.badge} />
                  </NavLink>
                ))}
              </div>)}
            </nav>
            <div className="sidebar-foot">
              <div className="me">
                {me.avatarUrl && <img src={me.avatarUrl} alt="" />}
                <div className="who"><b>{me.name || me.login}</b><span>@{me.login}{me.approver ? ' · approver' : ''}</span></div>
                <button className="logout-btn" type="button" title="Log out" onClick={logout}><Icon name="logout" /><span>Log out</span></button>
              </div>
            </div>
          </aside>
        </>}
        <div className="content">
          <header className="topbar">
            <button ref={menuButton} className="mobile-nav-toggle icon-btn" aria-label="Open navigation" aria-controls="workspace-navigation"
                    aria-expanded={mobileNav} onClick={() => setMobileNav(true)}><Icon name="menu" size={21} /></button>
            <Link to="/" className="brand" aria-label="AIO Platform — overview"><Wordmark height={20} className="logo-mark" /></Link>
            <TopNav groups={groups} pathname={location.pathname} />
            <button type="button" className="search-trigger" onClick={() => setPalette(true)} aria-haspopup="dialog"
                    aria-keyshortcuts="Control+K Meta+K" aria-label="Search">
              <Icon name="search" size={16} /><span className="search-label">Search…</span><kbd>{isMac ? '⌘K' : 'Ctrl K'}</kbd>
            </button>
            {!community && <Link to="/new" className="btn primary small new-project" aria-label="New project" title="New project">
              <Icon name="plus" size={14} /><span>New project</span>
            </Link>}
            <AccountControls me={me} />
          </header>
          {crumbParts.length > 1 && <nav className="crumbs subbar" aria-label="Breadcrumb">
            {crumbParts.map((c, i, a) => (
              <span key={c} style={{ display: 'contents' }}>
                {i < a.length - 1 ? <Link to={c === 'Projects' ? '/projects' : c === 'Pipelines' ? (location.pathname.startsWith('/scans/') ? '/pipelines/security' : '/pipelines/ci') : '/'}>{c}</Link> : <b aria-current="page">{c}</b>}
                {i < a.length - 1 && <span aria-hidden="true">/</span>}
              </span>
            ))}
          </nav>}
          <main id="main" ref={mainRef} tabIndex={-1}>
            <Routes>
              <Route path="*" element={<NotFound />} />
              <Route path="/" element={<Dashboard me={me} />} />
              <Route path="/projects" element={<Projects me={me} />} />
              <Route path="/new" element={<NewProject />} />
              <Route path="/templates" element={<Templates />} />
              <Route path="/projects/:id" element={<ProjectDetail me={me} />} />
              <Route path="/projects/:id/cd/:env" element={<CdDetail me={me} />} />
              <Route path="/runs/:id" element={<RunDetail me={me} />} />
              <Route path="/approvals" element={<Approvals me={me} />} />
              <Route path="/metrics" element={<Metrics />} />
              <Route path="/clusters" element={<Clusters />} />
              <Route path="/connectors" element={<Connectors />} />
              {!community && <Route path="/assistant" element={<Assistant />} />}
              <Route path="/audit" element={<Audit />} />
              <Route path="/pipelines/ci" element={<PipelinesCi />} />
              <Route path="/pipelines/cd" element={<PipelinesCd me={me} />} />
              <Route path="/pipelines/security" element={<Security />} />
              <Route path="/scans/:id" element={<ScanDetail />} />
            </Routes>
          </main>
        </div>
      </div>
      <CommandPalette open={palette} onClose={() => setPalette(false)} pages={pages} community={community} />
    </ToastProvider>
  )
}
