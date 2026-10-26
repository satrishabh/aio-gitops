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
import Approvals from './pages/Approvals.jsx'
import Audit from './pages/Audit.jsx'
import Metrics from './pages/Metrics.jsx'
import Templates from './pages/Templates.jsx'

const CRUMBS = [
  [/^\/$/, 'Overview'], [/^\/projects$/, 'Projects'], [/^\/projects\/\d+/, 'Projects / Project'],
  [/^\/runs\//, 'Projects / CI run'], [/^\/new/, 'New project'], [/^\/approvals/, 'Approvals'],
  [/^\/metrics/, 'Delivery metrics'], [/^\/audit/, 'Audit log'], [/^\/templates/, 'Templates'],
]

export default function App() {
  const [me, setMe] = useState(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(0)
  const location = useLocation()
  const isLogin = location.pathname === '/login'

  useEffect(() => {
    if (isLogin) { setLoading(false); return }
    api('/api/me').then(setMe).catch(() => {}).finally(() => setLoading(false))
  }, [isLogin])

  // Pending prod approvals badge in the sidebar
  useEffect(() => {
    if (!me) return
    const load = () => api('/api/promotions?status=PENDING').then(x => setPending(x.length)).catch(() => {})
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [me, location.pathname])

  // On client-side navigation: name the page and move focus to the content, as a full page load would.
  const mainRef = useRef(null)
  const firstRender = useRef(true)
  const crumb = (CRUMBS.find(([re]) => re.test(location.pathname)) || [null, ''])[1]
  useEffect(() => {
    document.title = crumb ? `${crumb.split(' / ').pop()} · AIO Platform` : 'AIO Platform'
    if (firstRender.current) { firstRender.current = false; return }
    mainRef.current?.focus({ preventScroll: true })
    window.scrollTo(0, 0)
  }, [location.pathname, crumb])

  if (isLogin) return <Login />
  if (loading) return <div className="center" role="status" aria-label="Loading AIO Platform"><LogoMark size={44} className="logo-mark pulse" /></div>
  if (!me) return null

  return (
    <ToastProvider>
      <a href="#main" className="skip-link">Skip to content</a>
      <div className="shell">
        <aside className="sidebar">
          <Link to="/" className="logo" aria-label="AIO Platform — overview">
            <span><Wordmark height={18} className="logo-mark" /><small>CI · CD · GitOps</small></span>
          </Link>
          <nav className="nav" aria-label="Main">
            <div className="nav-section">Workspace</div>
            <NavLink to="/" end><Icon name="home" />Overview</NavLink>
            <NavLink to="/projects"><Icon name="grid" />Projects</NavLink>
            <NavLink to="/new"><Icon name="plus" />New project</NavLink>
            <NavLink to="/templates"><Icon name="layers" />Templates</NavLink>
            <div className="nav-section">Delivery</div>
            <NavLink to="/approvals"><Icon name="shield" />Approvals{pending > 0 && <span className="count" aria-label={`${pending} pending`}>{pending}</span>}</NavLink>
            <NavLink to="/metrics"><Icon name="chart" />Metrics</NavLink>
            <NavLink to="/audit"><Icon name="list" />Audit log</NavLink>
          </nav>
          <div className="sidebar-foot">
            <div className="me">
              {me.avatarUrl && <img src={me.avatarUrl} alt="" />}
              <div className="who"><b>{me.name || me.login}</b><span>@{me.login}{me.approver ? ' · approver' : ''}</span></div>
              <button className="icon-btn" title="Log out" aria-label="Log out" onClick={logout}><Icon name="logout" /></button>
            </div>
          </div>
        </aside>
        <div className="content">
          <header className="topbar">
            <nav className="crumbs" aria-label="Breadcrumb">
              {crumb.split(' / ').map((c, i, a) => (
                <span key={c} style={{ display: 'contents' }}>
                  {i < a.length - 1 ? <Link to={c === 'Projects' ? '/projects' : '/'}>{c}</Link> : <b aria-current="page">{c}</b>}
                  {i < a.length - 1 && <span aria-hidden="true">/</span>}
                </span>
              ))}
            </nav>
            <span className="live-dot" title="Data refreshes automatically">Live</span>
            <ThemeToggle />
            <Link to="/new" className="btn primary small"><Icon name="plus" size={14} />New project</Link>
          </header>
          <main id="main" ref={mainRef} tabIndex={-1}>
            <Routes>
              <Route path="/" element={<Dashboard me={me} />} />
              <Route path="/projects" element={<Projects />} />
              <Route path="/new" element={<NewProject />} />
              <Route path="/templates" element={<Templates />} />
              <Route path="/projects/:id" element={<ProjectDetail me={me} />} />
              <Route path="/runs/:id" element={<RunDetail me={me} />} />
              <Route path="/approvals" element={<Approvals me={me} />} />
              <Route path="/metrics" element={<Metrics />} />
              <Route path="/audit" element={<Audit />} />
            </Routes>
          </main>
        </div>
      </div>
    </ToastProvider>
  )
}
