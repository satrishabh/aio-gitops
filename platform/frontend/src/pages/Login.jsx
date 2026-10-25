import Icon from '../components/Icon.jsx'

export default function Login() {
  const error = new URLSearchParams(window.location.search).has('error')
  return (
    <div className="login-wrap">
      <div className="login-hero">
        <div className="logo" style={{ padding: 0 }}>
          <span className="logo-mark"><Icon name="zap" size={17} /></span><span>AIO Platform</span>
        </div>
        <h1>Ship from <span className="grad-text">GitHub to production</span> with every step tracked.</h1>
        <p>Connect a repository with a Dockerfile. The platform tests, builds, scans and deploys it through dev, staging
          and a gated prod, using GitOps for every change.</p>
        <div className="login-flow">
          <span><Icon name="github" size={14} /> GitHub</span><em>→</em>
          <span><Icon name="check" size={14} /> Tekton CI</span><em>→</em>
          <span><Icon name="shield" size={14} /> Trivy</span><em>→</em>
          <span><Icon name="rocket" size={14} /> Argo CD</span><em>→</em>
          <span><Icon name="chart" size={14} /> DORA</span>
        </div>
      </div>
      <div className="login-panel">
        <div className="card login-card">
          <div className="logo-mark" style={{ width: 52, height: 52, margin: '0 auto 16px', borderRadius: 14 }}><Icon name="zap" size={24} /></div>
          <h2>Welcome back</h2>
          <p className="muted" style={{ marginTop: 0 }}>Sign in with your GitHub account to continue.</p>
          {error && <div className="banner" style={{ background: 'var(--red-bg)', color: '#fecaca', borderColor: 'rgba(248,113,113,0.35)' }}>
            <Icon name="x" size={15} />Login failed or your GitHub account is not allowed.</div>}
          <a className="btn github" href="/oauth2/authorization/github"><Icon name="github" size={18} />Continue with GitHub</a>
          <p className="muted" style={{ fontSize: '0.75rem', marginBottom: 0 }}>Access is limited to allow-listed GitHub users.</p>
        </div>
      </div>
    </div>
  )
}
