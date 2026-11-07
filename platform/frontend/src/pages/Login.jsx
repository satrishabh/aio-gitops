import Icon from '../components/Icon.jsx'
import { LogoMark, Wordmark } from '../components/Logo.jsx'
import ThemeToggle from '../components/ThemeToggle.jsx'

export default function Login() {
  const error = new URLSearchParams(window.location.search).has('error')
    ? 'Sign-in failed or this User Profile account is not allowed to access AIO.'
    : ''

  return (
    <main className="login-wrap">
      <div className="login-theme"><ThemeToggle /></div>
      <section className="login-hero">
        <div className="login-brand">
          <Wordmark height={26} className="login-wordmark" />
          <span>SHIP SOMETHING REAL</span>
        </div>
        <div className="login-copy">
          <p className="login-kicker"><span /> THE DELIVERY PLATFORM FOR YOUR NEXT BIG IDEA</p>
          <h1>From <span>first commit</span><br />to <em>fully shipped.</em></h1>
          <p className="login-description">
            AIO turns every project into a production-ready delivery system. Build, test, scan and deploy—
            with every change visible and every release in your control.
          </p>
        </div>
        <div className="login-pipeline" aria-label="AIO delivery pipeline">
          <div className="pipeline-node"><Icon name="github" size={17} /><span>YOUR CODE</span></div>
          <i />
          <div className="pipeline-node"><Icon name="zap" size={17} /><span>CI + SCAN</span></div>
          <i />
          <div className="pipeline-node"><Icon name="rocket" size={17} /><span>LIVE</span></div>
        </div>
        <div className="login-proof">
          <span><b>01</b> Push to GitHub</span>
          <span><b>02</b> AIO builds &amp; verifies</span>
          <span><b>03</b> Ship with confidence</span>
        </div>
        <div className="login-orbit orbit-one" />
        <div className="login-orbit orbit-two" />
      </section>

      <section className="login-panel">
        <div className="login-panel-top">
          <span><LogoMark size={30} className="logo-mark" /> AIO PLATFORM</span>
          <span className="login-online"><i /> SYSTEM ONLINE</span>
        </div>
        <div className="card login-card">
          <div className="login-card-mark"><LogoMark size={36} className="logo-mark" /></div>
          <p className="login-card-kicker">YOUR DELIVERY WORKSPACE</p>
          <h2>Welcome to AIO</h2>
          <p className="login-subtitle">Sign in with your shared User Profile account to continue.</p>
          {error && <div className="login-message error" role="alert"><Icon name="x" size={16} />{error}</div>}
          <a className="btn login-sso" href="/oauth2/authorization/profile">
            <Icon name="shield" size={18} />Continue with User Profile
          </a>
          <p className="login-provider-note">
            Choose email, Google, or GitHub on the next screen. One identity works across connected Sarvaikam apps.
          </p>
          <p className="login-terms">Access is limited to authorized workspace members.</p>
        </div>
        <footer className="login-footer"><span>© {new Date().getFullYear()} AIO Platform</span><span>Secure CI/CD · GitOps by default</span></footer>
      </section>
    </main>
  )
}
