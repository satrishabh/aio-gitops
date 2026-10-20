import { useEffect, useState } from 'react'
import { Link, NavLink, Route, Routes, useLocation } from 'react-router-dom'
import { api, logout } from './api.js'
import Login from './pages/Login.jsx'
import Projects from './pages/Projects.jsx'
import NewProject from './pages/NewProject.jsx'
import ProjectDetail from './pages/ProjectDetail.jsx'
import RunDetail from './pages/RunDetail.jsx'
import Approvals from './pages/Approvals.jsx'
import Audit from './pages/Audit.jsx'

export default function App() {
  const [me, setMe] = useState(null)
  const [loading, setLoading] = useState(true)
  const location = useLocation()

  useEffect(() => {
    if (location.pathname === '/login') { setLoading(false); return }
    api('/api/me').then(setMe).catch(() => {}).finally(() => setLoading(false))
  }, [location.pathname === '/login'])

  if (location.pathname === '/login') return <Login />
  if (loading) return <div className="center">Loading…</div>
  if (!me) return null

  return (
    <div className="layout">
      <header className="topbar">
        <Link to="/" className="brand">AIO Platform</Link>
        <nav>
          <NavLink to="/" end>Projects</NavLink>
          <NavLink to="/new">New project</NavLink>
          <NavLink to="/approvals">Approvals</NavLink>
          <NavLink to="/audit">Audit log</NavLink>
        </nav>
        <div className="user">
          {me.avatarUrl && <img src={me.avatarUrl} alt="" />}
          <span>{me.login}{me.approver ? ' (approver)' : ''}</span>
          <button className="link" onClick={logout}>Log out</button>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Projects />} />
          <Route path="/new" element={<NewProject />} />
          <Route path="/projects/:id" element={<ProjectDetail me={me} />} />
          <Route path="/runs/:id" element={<RunDetail />} />
          <Route path="/approvals" element={<Approvals me={me} />} />
          <Route path="/audit" element={<Audit />} />
        </Routes>
      </main>
    </div>
  )
}
