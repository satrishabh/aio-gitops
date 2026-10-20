export default function Login() {
  const error = new URLSearchParams(window.location.search).has('error')
  return (
    <div className="center">
      <div className="card login">
        <h1>AIO Platform</h1>
        <p className="muted">GitHub repo → Tekton CI → Argo CD deploy, all tracked in one place.</p>
        {error && <p className="error">Login failed or your GitHub account is not allowed.</p>}
        <a className="btn primary" href="/oauth2/authorization/github">Sign in with GitHub</a>
      </div>
    </div>
  )
}
