import Icon from '../components/Icon.jsx'
import { LogoMark, Wordmark } from '../components/Logo.jsx'
import ThemeToggle from '../components/ThemeToggle.jsx'

export default function Login() {
  const error = new URLSearchParams(window.location.search).has('error')
  return (
    <main className="login-wrap">
      <div className="login-theme"><ThemeToggle /></div>
      <div className="login-hero">
        <div className="logo" style={{ padding: 0 }}>
          <Wordmark height={22} className="logo-mark" /><span className="muted" style={{ fontWeight: 500 }}>Platform</span>
        </div>
        <h1>Ship from <span className="grad-text">GitHub to production</span> with every step tracked.</h1>
        <p>Connect a repository with a Dockerfile. The platform tests, builds, scans and deploys it through dev, staging
          and a gated prod, using GitOps for every change.</p>
        <ol className="login-flow" aria-label="Delivery pipeline">
          <li><Icon name="github" size={14} /> GitHub</li><li className="sep" aria-hidden="true">→</li>
          <li><Icon name="check" size={14} /> Tekton CI</li><li className="sep" aria-hidden="true">→</li>
          <li><Icon name="shield" size={14} /> Trivy</li><li className="sep" aria-hidden="true">→</li>
          <li><Icon name="rocket" size={14} /> Argo CD</li><li className="sep" aria-hidden="true">→</li>
          <li><Icon name="chart" size={14} /> DORA</li>
        </ol>
      </div>
      <div className="login-panel">
        <div className="card login-card">
          <LogoMark size={52} className="logo-mark" style={{ display: 'block', margin: '0 auto 16px' }} />
          <h2>Welcome back</h2>
          <p className="muted" style={{ marginTop: 0 }}>Sign in with your GitHub account to continue.</p>
          {error && <div className="banner" style={{ background: 'var(--red-bg)', color: 'var(--red)' }}>
            <Icon name="x" size={15} />Login failed or your GitHub account is not allowed.</div>}
          <a className="btn github" href="/oauth2/authorization/github"><Icon name="github" size={18} />Continue with GitHub</a>
          <p className="muted" style={{ fontSize: '0.75rem', marginBottom: 0 }}>Access is limited to allow-listed GitHub users.</p>
        </div>
      </div>
    </main>
  )
}
