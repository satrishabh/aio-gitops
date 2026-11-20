import Icon from '../Icon.jsx'

/** Status word with a dot. The word carries the meaning; the dot only repeats it in colour. */
export function StateWord({ tone = 'gray', children, className = '' }) {
  return <span className={`fl-state is-${tone} ${className}`}>{children}</span>
}

/** One word per pipeline/task status, and the tone it is drawn in. */
export const TASK_WORD = { SUCCEEDED: 'Passed', FAILED: 'Failed', RUNNING: 'Running', SKIPPED: 'Skipped', WAITING: 'Waiting', PENDING: 'Waiting' }
export const TASK_TONE = { SUCCEEDED: 'green', FAILED: 'red', RUNNING: 'blue', SKIPPED: 'gray', WAITING: 'gray', PENDING: 'gray' }

/**
 * Horizontal timeline of stages. items: [{ key, name, status, meta, icon, active, disabled, word }].
 * Each stage is a real button; onSelect(item) runs when one is pressed.
 */
export function Timeline({ items, onSelect, label }) {
  return (
    <ol className="fl-timeline" aria-label={label}>
      {items.map((t, i) => {
        const tone = TASK_TONE[t.status] || 'gray'
        const glyph = t.status === 'SUCCEEDED' ? 'check' : t.status === 'FAILED' ? 'x' : t.icon || 'zap'
        return (
          <li key={t.key} className={`fl-task st-${t.status}${t.active ? ' active' : ''}`}>
            <button type="button" aria-pressed={!!t.active} disabled={t.disabled} onClick={() => onSelect(t)}>
              <span className="fl-task-top">
                <span className={`fl-task-ico is-${tone}`} aria-hidden="true"><Icon name={glyph} size={14} stroke={2.5} /></span>
                <span className="fl-task-n" aria-hidden="true">{i + 1}</span>
              </span>
              <span className="fl-task-name">{t.name}</span>
              <span className="fl-task-meta">
                <StateWord tone={tone}>{t.word || TASK_WORD[t.status] || t.status}</StateWord>
                {t.meta && <span className="fl-task-dur">{t.meta}</span>}
              </span>
            </button>
          </li>
        )
      })}
    </ol>
  )
}

/** Header card shared by the project, CI run and CD detail pages. */
export function DetailHead({ avatar, title, sub, children, below }) {
  return (
    <section className="fl-head">
      <div className="fl-head-row">
        {avatar}
        <div className="fl-head-title">
          <h1>{title}</h1>
          {sub && <div className="fl-head-sub">{sub}</div>}
        </div>
        {children && <div className="fl-head-actions">{children}</div>}
      </div>
      {below}
    </section>
  )
}
