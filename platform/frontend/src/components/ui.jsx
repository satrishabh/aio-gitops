import Icon, { iconPath } from './Icon.jsx'

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

/** Deterministic avatar from a project name: one hue per project, tinted background with same-hue text (theme sets the shades). */
export function Avatar({ name, size = 38 }) {
  let h = 0
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 360
  return (
    <div className="avatar" aria-hidden="true" style={{ width: size, height: size, fontSize: size * 0.34, '--hue': h }}>
      {name.slice(0, 2).toUpperCase()}
    </div>
  )
}

/**
 * Loading placeholder. Alone it is a labelled status region with `count` blocks of height `h`.
 * `bare` drops the wrapper and margin so blocks can be composed inside <Loading> into a page-shaped skeleton;
 * `w` sets a width and `className` adds shape classes (`line`, `round`).
 */
export function Skeleton({ h = 120, count = 1, w, bare, className = '', style }) {
  const block = i => <div key={i} className={`skeleton${className ? ` ${className}` : ''}`}
                          style={{ height: h, width: w, marginBottom: bare ? undefined : 18, ...style }} />
  if (bare) return <>{Array.from({ length: count }, (_, i) => block(i))}</>
  return (
    <div role="status" aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => block(i))}
    </div>
  )
}

/** One status region around a composed skeleton, so screen readers hear "Loading" once. */
export function Loading({ label = 'Loading', className, children }) {
  return <div role="status" aria-busy="true" aria-label={label} className={className}>{children}</div>
}

/** Skeleton shaped like a table in a flush card: a header rule and `rows` rows of `cols` cells (rows become cards on phones). */
export function SkeletonTable({ rows = 6, cols = 5, label = 'Loading' }) {
  const widths = ['70%', '55%', '40%', '62%', '48%', '35%', '58%']
  return (
    <Loading label={label} className="card flush skeleton-table">
      <div className="skeleton-table-head">{Array.from({ length: cols }, (_, c) => <Skeleton key={c} bare h={10} w="38%" className="line" />)}</div>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="skeleton-table-row" style={{ '--cols': cols }}>
          {Array.from({ length: cols }, (_, c) => <Skeleton key={c} bare h={c === 0 ? 14 : 12} w={widths[(r + c) % widths.length]} className="line" />)}
        </div>
      ))}
    </Loading>
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

/**
 * Empty state: a small illustration (theme colours, decorative), a title, one line of guidance and an optional action.
 * `compact` swaps the illustration for the plain tinted glyph (for small panels); `heading` sets the title element
 * (h3 by default; the 404 page uses h1); `tone="bad"` marks an error with the red badge.
 */
export function Empty({ icon = 'box', title, children, action, compact, heading: H = 'h3', tone, className }) {
  return (
    <div className={`empty-state${compact ? ' compact' : ''}${tone ? ` tone-${tone}` : ''}${className ? ` ${className}` : ''}`}>
      {compact ? <div className="glyph"><Icon name={icon} size={24} /></div> : <EmptyArt icon={icon} />}
      <H className="empty-title">{title}</H>
      {children && <div className="muted">{children}</div>}
      {action && <div className="actions">{action}</div>}
    </div>
  )
}

/** Decorative illustration for empty states: two stacked cards with an icon badge. Colours come from theme tokens (styles.css). */
export function EmptyArt({ icon = 'box' }) {
  return (
    <svg className="empty-art" viewBox="0 0 168 116" width="168" height="116" aria-hidden="true" focusable="false">
      <ellipse className="ea-blob" cx="84" cy="62" rx="72" ry="48" />
      <rect className="ea-card ea-back" x="50" y="18" width="78" height="56" rx="10" transform="rotate(-7 89 46)" />
      <rect className="ea-card" x="38" y="30" width="86" height="62" rx="10" />
      <rect className="ea-line ea-strong" x="50" y="44" width="34" height="7" rx="3.5" />
      <rect className="ea-line" x="50" y="58" width="60" height="5" rx="2.5" />
      <rect className="ea-line" x="50" y="70" width="44" height="5" rx="2.5" />
      <circle className="ea-dot ea-ok" cx="28" cy="34" r="4" />
      <circle className="ea-dot ea-warn" cx="142" cy="26" r="3" />
      <path className="ea-spark" d="M24 84v8M20 88h8" />
      <circle className="ea-badge" cx="122" cy="86" r="19" />
      <svg x="110" y="74" width="24" height="24" viewBox="0 0 24 24" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path className="ea-icon" d={iconPath(icon)} />
      </svg>
    </svg>
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

/**
 * Small trend line for one value per day. null means "no reading that day" and leaves a gap
 * (a missing lead time is not a zero lead time); isolated readings are drawn as dots.
 */
export function Sparkline({ values, color = 'var(--accent)', label, height = 36 }) {
  const W = 120, H = height, pad = 3
  const nums = values.filter(v => v != null)
  if (nums.length === 0) return <div className="spark-empty">No readings in this period</div>
  const max = Math.max(...nums), min = Math.min(0, ...nums)
  const span = max - min || 1
  const x = i => (values.length === 1 ? W / 2 : (i / (values.length - 1)) * W)
  const y = v => H - pad - ((v - min) / span) * (H - pad * 2)
  const runs = []
  let current = []
  values.forEach((v, i) => {
    if (v == null) { if (current.length) runs.push(current); current = [] } else current.push([x(i), y(v)])
  })
  if (current.length) runs.push(current)
  return (
    <svg className="spark" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={label}>
      {runs.map((pts, k) => (pts.length === 1
        ? <circle key={k} cx={pts[0][0]} cy={pts[0][1]} r="2.2" fill={color} vectorEffect="non-scaling-stroke" />
        : <polyline key={k} points={pts.map(p => p.join(',')).join(' ')} fill="none" stroke={color} strokeWidth="2"
                    strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />))}
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
