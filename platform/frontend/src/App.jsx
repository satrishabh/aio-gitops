import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { api, logout } from './api.js'
import { ToastProvider } from './components/Toast.jsx'
import Icon from './components/Icon.jsx'
import ThemeToggle from './components/ThemeToggle.jsx'
import { LogoMark, Wordmark } from './components/Logo.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import Projects from './pages/Projects.jsx'
import NewProject from './pages/NewProject.jsx'
import ProjectDetail from './pages/ProjectDetail.jsx'
import RunDetail from './pages/RunDetail.jsx'
import CdDetail from './pages/CdDetail.jsx'
import Clusters from './pages/Clusters.jsx'
import Approvals from './pages/Approvals.jsx'
import Audit from './pages/Audit.jsx'
import Metrics from './pages/Metrics.jsx'
import Templates from './pages/Templates.jsx'
import PipelinesCi from './pages/PipelinesCi.jsx'
import PipelinesCd from './pages/PipelinesCd.jsx'
import Security from './pages/Security.jsx'
import ScanDetail from './pages/ScanDetail.jsx'

const CRUMBS = [
  [/^\/$/, 'Overview'], [/^\/projects$/, 'Projects'], [/^\/projects\/\d+\/cd\//, 'Projects / CD'], [/^\/projects\/\d+/, 'Projects / Project'],
  [/^\/runs\//, 'Projects / CI run'], [/^\/new/, 'New project'], [/^\/approvals/, 'Approvals'],
  [/^\/metrics/, 'Delivery metrics'], [/^\/audit/, 'Audit log'], [/^\/templates/, 'Templates'], [/^\/clusters/, 'Clusters'],
  [/^\/pipelines\/ci/, 'Pipelines / CI'], [/^\/pipelines\/cd/, 'Pipelines / CD'], [/^\/pipelines\/security/, 'Pipelines / Security'],
  [/^\/scans\//, 'Pipelines / Security scan'],
]

export default function App() {
  const [me, setMe] = useState(null)
  const [sessionError, setSessionError] = useState('')
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(0)
  const [mobileNav, setMobileNav] = useState(false)
  const menuButton = useRef(null)
  const sidebar = useRef(null)
  const location = useLocation()
  const isLogin = location.pathname === '/login'

  useEffect(() => { setMobileNav(false) }, [location.pathname])
  useEffect(() => {
    if (!mobileNav) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    sidebar.current?.querySelector('a')?.focus()
    const onKey = e => {
      if (e.key === 'Escape') { setMobileNav(false); menuButton.current?.focus() }
      if (e.key !== 'Tab') return
      const controls = [...sidebar.current.querySelectorAll('a, button')]
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

  useEffect(() => {
    if (isLogin) { setLoading(false); return }
    setLoading(true)
    setSessionError('')
    api('/api/me').then(setMe).catch(e => setSessionError(e.message)).finally(() => setLoading(false))
  }, [isLogin])

  // Pending prod approvals badge in the sidebar
  useEffect(() => {
    if (!me) return
    if (me.accessTier === 'community') { setPending(0); return }
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

  if (isLogin) return <Login />
  if (loading) return <div className="center" role="status" aria-label="Loading AIO Platform"><LogoMark size={44} className="logo-mark pulse" /></div>
  if (!me) return <div className="center"><div className="card" role="alert"><h1>Unable to load your workspace</h1><p>{sessionError || 'Your session could not be loaded.'}</p><button className="btn primary" onClick={() => window.location.reload()}>Try again</button> <Link className="btn" to="/login">Go to login</Link></div></div>
  const community = me.accessTier === 'community'

  return (
    <ToastProvider>
      <a href="#main" className="skip-link">Skip to content</a>
      <div className="shell">
        {mobileNav && <button className="mobile-nav-backdrop" aria-label="Close navigation" onClick={() => { setMobileNav(false); menuButton.current?.focus() }} />}
        <aside ref={sidebar} id="workspace-navigation" className={`sidebar${mobileNav ? ' mobile-open' : ''}`}>
          <button className="mobile-nav-close icon-btn" aria-label="Close navigation" onClick={() => { setMobileNav(false); menuButton.current?.focus() }}><Icon name="x" /></button>
          <Link to="/" className="logo" aria-label="AIO Platform — overview">
            <span><Wordmark height={18} className="logo-mark" /><small>CI · CD · GitOps</small></span>
          </Link>
          <nav className="nav" aria-label="Main" onClick={e => { if (mobileNav && e.target.closest('a')) { setMobileNav(false); mainRef.current?.focus() } }}>
            <div className="nav-section">Workspace</div>
            <NavLink to="/" end><Icon name="home" />Overview</NavLink>
            <NavLink to="/projects"><Icon name="grid" />Projects</NavLink>
            {!community && <>
              <NavLink to="/new"><Icon name="plus" />New project</NavLink>
              <NavLink to="/templates"><Icon name="layers" />Templates</NavLink>
              <div className="nav-section">Pipelines</div>
              <NavLink to="/pipelines/ci"><Icon name="zap" />CI</NavLink>
              <NavLink to="/pipelines/cd"><Icon name="rocket" />CD</NavLink>
              <NavLink to="/pipelines/security" className={({ isActive }) => isActive || location.pathname.startsWith('/scans/') ? 'active' : ''}>
                <Icon name="shield" />Security</NavLink>
            </>}
            <div className="nav-section">Delivery</div>
            {!community && <NavLink to="/approvals"><Icon name="shield" />Approvals{pending > 0 && <span className="count" aria-label={`${pending} pending`}>{pending}</span>}</NavLink>}
            <NavLink to="/metrics"><Icon name="chart" />Metrics</NavLink>
            {!community && <>
              <NavLink to="/clusters"><Icon name="layers" />Clusters</NavLink>
              <NavLink to="/audit"><Icon name="list" />Audit log</NavLink>
            </>}
          </nav>
          <div className="sidebar-foot">
            <div className="me">
              {me.avatarUrl && <img src={me.avatarUrl} alt="" />}
              <div className="who"><b>{me.name || me.login}</b><span>@{me.login}{me.approver ? ' · approver' : ''}</span></div>
              <button className="logout-btn" type="button" title="Log out" onClick={logout}>
                <Icon name="logout" /><span>Log out</span>
              </button>
            </div>
          </div>
        </aside>
        <div className="content">
          <header className="topbar">
            <button ref={menuButton} className="mobile-nav-toggle icon-btn" aria-label="Open navigation" aria-controls="workspace-navigation" aria-expanded={mobileNav} onClick={() => setMobileNav(true)}><Icon name="menu" size={21} /></button>
            <Link to="/" className="mobile-brand" aria-label="AIO by Sarvaikam — home">
              <Wordmark height={19} className="logo-mark" />
              <small>by Sarvaikam</small>
            </Link>
            <nav className="crumbs" aria-label="Breadcrumb">
              {crumb.split(' / ').map((c, i, a) => (
                <span key={c} style={{ display: 'contents' }}>
                  {i < a.length - 1 ? <Link to={c === 'Projects' ? '/projects' : c === 'Pipelines' ? (location.pathname.startsWith('/scans/') ? '/pipelines/security' : '/pipelines/ci') : '/'}>{c}</Link> : <b aria-current="page">{c}</b>}
                  {i < a.length - 1 && <span aria-hidden="true">/</span>}
                </span>
              ))}
            </nav>
            <span className="live-dot" title="Data refreshes automatically">Live</span>
            <ThemeToggle />
            {!community && <Link to="/new" className="btn primary small" aria-label="New project" title="New project">
              <Icon name="plus" size={14} /><span>New project</span>
            </Link>}
          </header>
          <main id="main" ref={mainRef} tabIndex={-1}>
            <Routes>
              <Route path="*" element={<div className="empty-state"><h1>Page not found</h1><p>This address does not match an AIO page.</p><Link className="btn primary" to="/">Back to overview</Link></div>} />
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
              <Route path="/audit" element={<Audit />} />
              <Route path="/pipelines/ci" element={<PipelinesCi />} />
              <Route path="/pipelines/cd" element={<PipelinesCd me={me} />} />
              <Route path="/pipelines/security" element={<Security />} />
              <Route path="/scans/:id" element={<ScanDetail />} />
            </Routes>
          </main>
        </div>
      </div>
    </ToastProvider>
  )
}
