import Icon from './Icon.jsx'

export function PageHead({ title, sub, children }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      <div className="spacer" />
      {children}
    </div>
  )
}

export function Kpi({ label, value, sub, icon, tone }) {
  return (
    <div className={`kpi ${tone || ''}`}>
      <div className="label">{icon && <Icon name={icon} size={14} />}{label}</div>
      <div className="value">{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  )
}

/** Deterministic flat-colour avatar from a project name (one muted hue per project, no gradient). */
export function Avatar({ name, size = 38 }) {
  let h = 0
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 360
  return (
    <div className="avatar" style={{ width: size, height: size, fontSize: size * 0.34,
      background: `hsl(${h} 45% 52%)` }}>
      {name.slice(0, 2).toUpperCase()}
    </div>
  )
}

export function Skeleton({ h = 120, count = 1 }) {
  return (
    <div role="status" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => <div key={i} className="skeleton" style={{ height: h, marginBottom: 18 }} />)}
    </div>
  )
}

/** Segmented control with roving focus: Left/Right/Home/End move between tabs. */
export function Tabs({ tabs, value, onChange, label }) {
  const onKey = e => {
    const i = tabs.findIndex(t => t.id === value)
    const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key]
    if (next == null) return
    e.preventDefault()
    const t = tabs[(next + tabs.length) % tabs.length]
    onChange(t.id)
    e.currentTarget.querySelector(`[data-id="${t.id}"]`)?.focus()
  }
  return (
    <div className="tabs" role="tablist" aria-label={label} onKeyDown={onKey}>
      {tabs.map(t => {
        const on = value === t.id
        return (
          <button key={t.id} data-id={t.id} role="tab" aria-selected={on} tabIndex={on ? 0 : -1}
                  className={on ? 'active' : ''} onClick={() => onChange(t.id)}>
            {t.icon && <Icon name={t.icon} size={14} />}{t.label}
            {t.count != null && <span className="n">{t.count}</span>}
          </button>
        )
      })}
    </div>
  )
}

export function Empty({ icon = 'box', title, children, action }) {
  return (
    <div className="empty-state">
      <div className="glyph"><Icon name={icon} size={24} /></div>
      <h3>{title}</h3>
      {children && <div className="muted">{children}</div>}
      {action && <div className="actions">{action}</div>}
    </div>
  )
}

/** Stacked bar chart (SVG). series: [{key, color, label}], data: [{date, ...}] */
export function BarChart({ data, series, height = 220, label = 'Bar chart' }) {
  const W = 760, H = height, pad = { l: 28, r: 8, t: 10, b: 24 }
  const totals = data.map(d => series.reduce((s, x) => s + (d[x.key] || 0), 0))
  const max = Math.max(1, ...totals)
  const step = (W - pad.l - pad.r) / Math.max(1, data.length)
  const bw = Math.max(2, Math.min(22, step * 0.62))
  const y = v => H - pad.b - (v / max) * (H - pad.t - pad.b)
  const ticks = [0, Math.ceil(max / 2), max]
  const every = Math.ceil(data.length / 8)
  const sum = series.map(s => `${s.label} ${data.reduce((a, d) => a + (d[s.key] || 0), 0)}`).join(', ')
  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img"
         aria-label={`${label}, ${data.length} days: ${sum}`}>
      {ticks.map(t => (
        <g key={t}>
          <line className="axis" x1={pad.l} x2={W - pad.r} y1={y(t)} y2={y(t)} strokeDasharray={t ? '3 4' : ''} />
          <text x={pad.l - 6} y={y(t) + 3} textAnchor="end">{t}</text>
        </g>
      ))}
      {data.map((d, i) => {
        let acc = 0
        const x = pad.l + i * step + (step - bw) / 2
        return (
          <g key={d.date}>
            {series.map(s => {
              const v = d[s.key] || 0
              if (!v) return null
              const y1 = y(acc + v), y0 = y(acc)
              acc += v
              return <rect key={s.key} x={x} y={y1} width={bw} height={Math.max(1, y0 - y1)} rx="3" fill={s.color}>
                <title>{`${d.date} · ${s.label}: ${v}`}</title></rect>
            })}
            {i % every === 0 && <text x={x + bw / 2} y={H - 6} textAnchor="middle">{d.date.slice(5)}</text>}
          </g>
        )
      })}
    </svg>
  )
}

/** Circular progress ring for a 0..1 ratio (single accent). */
export function Ring({ value, size = 64, color = 'var(--accent)', label }) {
  const r = (size - 8) / 2, c = 2 * Math.PI * r
  const v = value == null ? 0 : Math.max(0, Math.min(1, value))
  const text = label ?? (value == null ? '—' : `${Math.round(v * 100)}%`)
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={String(text)}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--fill)" strokeWidth="6" />
      <circle className="ring-arc" cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth="6" strokeLinecap="round"
              strokeDasharray={`${c * v} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      <text x="50%" y="54%" textAnchor="middle" fill="var(--text)" fontSize={size / 4.6} fontWeight="600"
            style={{ fontVariantNumeric: 'tabular-nums' }} aria-hidden="true">
        {text}
      </text>
    </svg>
  )
}

export const ago = t => {
  if (!t) return '—'
  const s = Math.round((Date.now() - new Date(t)) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86400)}d ago`
}

/** Deployed (real image) but scaled to 0 on purpose. */
export const isStopped = s => !!s && s.replicas === 0 && !!s.desiredTag && s.desiredTag !== '0.0.0'

/** Overall colour of an environment for strips/cards. */
export function envTone(s) {
  if (!s || !s.healthStatus) return 'gray'
  if (s.replicas === 0) return 'gray'
  if (s.healthStatus === 'Degraded' || s.healthStatus === 'Missing') return 'red'
  if (s.healthStatus === 'Progressing' || (s.desiredTag && s.imageTag && s.desiredTag !== s.imageTag)) return 'blue'
  if (s.syncStatus === 'OutOfSync') return 'amber'
  return 'green'
}
