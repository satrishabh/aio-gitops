import { greeting } from '../lib/greeting.mjs'

export default function Home() {
  return (
    <main style={{ maxWidth: 760, margin: '0 auto', padding: '80px 24px' }}>
      <h1 style={{ fontSize: '3rem', margin: 0, background: 'linear-gradient(135deg,#7c5cff,#22d3ee)', WebkitBackgroundClip: 'text', color: 'transparent' }}>
        {greeting('world')}
      </h1>
      <p style={{ color: '#8a94a7', fontSize: '1.1rem' }}>
        Edit <code>app/page.js</code> and push. The AIO pipeline tests, builds, scans and deploys every change.
      </p>
      <p><a style={{ color: '#a5b4fc' }} href="/health">/health</a></p>
    </main>
  )
}
