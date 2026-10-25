import { useState } from 'react'
import { compact, percent } from './format.js'

const STATS = [
  { label: 'Active users', value: 12840, delta: 0.124 },
  { label: 'Revenue', value: 48210, delta: 0.081, prefix: '$' },
  { label: 'Orders', value: 1932, delta: -0.023 },
  { label: 'Conversion', value: 0.0412, delta: 0.006, isPercent: true },
]
const NAV = ['Overview', 'Customers', 'Orders', 'Reports', 'Settings']

export default function App() {
  const [page, setPage] = useState('Overview')
  return (
    <div className="shell">
      <aside>
        <div className="brand"><span className="mark" />My App</div>
        {NAV.map(n => (
          <button key={n} className={n === page ? 'active' : ''} onClick={() => setPage(n)}>{n}</button>
        ))}
      </aside>
      <main>
        <header>
          <h1>{page}</h1>
          <p>Edit <code>src/App.jsx</code> and push — the AIO pipeline tests, builds, scans and deploys it.</p>
        </header>
        <section className="stats">
          {STATS.map(s => (
            <div className="stat" key={s.label}>
              <span>{s.label}</span>
              <b>{s.isPercent ? percent(s.value) : `${s.prefix || ''}${compact(s.value)}`}</b>
              <em className={s.delta >= 0 ? 'up' : 'down'}>{s.delta >= 0 ? '▲' : '▼'} {percent(Math.abs(s.delta))}</em>
            </div>
          ))}
        </section>
        <section className="panel">
          <h2>Getting started</h2>
          <ol>
            <li>Clone this repository and run <code>npm install && npm run dev</code>.</li>
            <li>Build your UI in <code>src/</code>; keep tests in <code>src/*.test.js</code>.</li>
            <li>Push to the default branch — dev updates automatically; promote to staging and prod from the portal.</li>
          </ol>
        </section>
      </main>
    </div>
  )
}
