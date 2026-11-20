import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import Icon from './Icon.jsx'

const PER_GROUP = 6

/**
 * Ctrl K / ⌘K search: pages, projects and recent CI runs. Navigation only.
 * Arrow keys move, Enter opens, Esc closes; focus returns to where it was.
 */
export default function CommandPalette({ open, onClose, pages, community }) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [projects, setProjects] = useState([])
  const [runs, setRuns] = useState([])
  const input = useRef(null)
  const list = useRef(null)
  const id = useId()
  const navigate = useNavigate()

  useEffect(() => {
    if (!open) return undefined
    const returnTo = document.activeElement
    setQuery('')
    setActive(0)
    input.current?.focus()
    let alive = true
    api('/api/projects').then(x => { if (alive) setProjects(x) }).catch(() => {})
    if (!community) api('/api/runs?limit=20').then(x => { if (alive) setRuns(x) }).catch(() => {})
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      alive = false
      document.body.style.overflow = previousOverflow
      if (returnTo && returnTo.focus) returnTo.focus()
    }
  }, [open, community])

  const results = useMemo(() => {
    const q = query.trim().toLowerCase()
    const hit = (...parts) => !q || parts.some(p => p && String(p).toLowerCase().includes(q))
    const pageItems = pages.filter(p => hit(p.label, p.group)).slice(0, q ? PER_GROUP : 20)
      .map(p => ({ key: `page:${p.to}`, group: 'Pages', icon: p.icon, label: p.label, hint: p.group, to: p.to }))
    const projectItems = projects.filter(({ project: p }) => hit(p.name, p.repoFullName, p.repo))
      .slice(0, q ? PER_GROUP : 4)
      .map(({ project: p }) => ({ key: `project:${p.id}`, group: 'Projects', icon: 'grid', label: p.name, hint: p.repoFullName || p.repo || '', to: community ? '/projects' : `/projects/${p.id}` }))
    const runItems = q ? runs.filter(({ run: r, project }) => hit(`#${r.id}`, String(r.id), project?.name, r.gitRef, r.commitSha?.slice(0, 7), r.status))
      .slice(0, PER_GROUP)
      .map(({ run: r, project }) => ({ key: `run:${r.id}`, group: 'CI runs', icon: 'zap', label: `${project?.name || 'Run'} #${r.id}`, hint: `${(r.status || '').toLowerCase()} · ${r.gitRef || ''} ${r.commitSha?.slice(0, 7) || ''}`.trim(), to: `/runs/${r.id}` })) : []
    return [...pageItems, ...projectItems, ...runItems]
  }, [query, pages, projects, runs, community])

  useEffect(() => { setActive(0) }, [query])
  useEffect(() => {
    list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [active])

  if (!open) return null
  const go = item => { onClose(); navigate(item.to) }
  const onKey = event => {
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive(i => Math.min(i + 1, results.length - 1)) }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(i => Math.max(i - 1, 0)) }
    else if (event.key === 'Enter' && results[active]) { event.preventDefault(); go(results[active]) }
    else if (event.key === 'Escape') { event.preventDefault(); onClose() }
    else if (event.key === 'Tab') event.preventDefault()
  }

  let lastGroup = ''
  return (
    <div className="palette-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
      <div className="palette" role="dialog" aria-modal="true" aria-label="Search AIO">
        <div className="palette-input">
          <Icon name="search" size={18} />
          <input ref={input} type="text" value={query} onChange={event => setQuery(event.target.value)} onKeyDown={onKey}
                 placeholder="Search pages, projects and CI runs…" aria-label="Search pages, projects and CI runs"
                 role="combobox" aria-expanded="true" aria-controls={`${id}-list`} aria-autocomplete="list"
                 aria-activedescendant={results[active] ? `${id}-${active}` : undefined} autoComplete="off" spellCheck="false" />
          <kbd>Esc</kbd>
        </div>
        <ul ref={list} id={`${id}-list`} role="listbox" aria-label="Results" className="palette-list">
          {results.length === 0 && <li className="palette-empty" role="presentation">No matches for “{query}”.</li>}
          {results.map((item, i) => {
            const heading = item.group !== lastGroup ? item.group : null
            lastGroup = item.group
            return [
              heading && <li key={`h:${heading}`} role="presentation" className="palette-group">{heading}</li>,
              <li key={item.key} id={`${id}-${i}`} role="option" aria-selected={i === active}
                  className={`palette-item${i === active ? ' active' : ''}`}
                  onMouseMove={() => setActive(i)} onClick={() => go(item)}>
                <Icon name={item.icon} size={16} />
                <span className="palette-label">{item.label}</span>
                {item.hint && <span className="palette-hint">{item.hint}</span>}
              </li>,
            ]
          })}
        </ul>
        <div className="palette-foot" aria-hidden="true"><span><kbd>↑</kbd><kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span><span><kbd>Esc</kbd> close</span></div>
      </div>
    </div>
  )
}
