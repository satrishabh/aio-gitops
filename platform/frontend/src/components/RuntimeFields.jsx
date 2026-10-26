import { useId } from 'react'
import Icon from './Icon.jsx'

export const RUNTIME_DEFAULTS = { cpuRequest: '50m', cpuLimit: '500m', memoryRequest: '128Mi', memoryLimit: '512Mi' }

const SIZES = [
  { id: 'small', label: 'Small', cpuRequest: '50m', cpuLimit: '500m', memoryRequest: '128Mi', memoryLimit: '512Mi' },
  { id: 'medium', label: 'Medium', cpuRequest: '250m', cpuLimit: '1', memoryRequest: '512Mi', memoryLimit: '1Gi' },
  { id: 'large', label: 'Large', cpuRequest: '500m', cpuLimit: '2', memoryRequest: '1Gi', memoryLimit: '4Gi' },
]
const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/

/** Parses KEY=value lines (a pasted .env file). Ignores comments and blank lines; strips matching quotes. */
export function parseDotEnv(text) {
  return text.split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#') && l.includes('=')).map(l => {
    const i = l.indexOf('=')
    let value = l.slice(i + 1).trim()
    if (/^(["']).*\1$/.test(value)) value = value.slice(1, -1)
    return { name: l.slice(0, i).replace(/^export\s+/, '').trim(), value }
  })
}

/**
 * Environment variables + CPU/memory, shared by New project and project Settings.
 * value: { envVars: [{name, value}], cpuRequest, cpuLimit, memoryRequest, memoryLimit }; onChange(next).
 */
export default function RuntimeFields({ value, onChange }) {
  const id = useId()
  const env = value.envVars || []
  const set = patch => onChange({ ...value, ...patch })
  const setRow = (i, patch) => set({ envVars: env.map((e, j) => j === i ? { ...e, ...patch } : e) })
  const names = env.map(e => e.name.trim())
  const rowError = (e, i) => {
    const n = e.name.trim()
    if (!n) return e.value ? 'Name is required' : ''
    if (!NAME.test(n)) return 'Letters, digits and _ only; must not start with a digit'
    if (names.indexOf(n) !== i) return 'Duplicate name'
    return ''
  }
  const size = SIZES.find(s => ['cpuRequest', 'cpuLimit', 'memoryRequest', 'memoryLimit'].every(k => s[k] === value[k]))

  function onPasteName(i, e) {
    const text = e.clipboardData.getData('text')
    if (!text.includes('=')) return
    e.preventDefault()
    const parsed = parseDotEnv(text)
    const rest = env.filter((r, j) => j !== i && r.name.trim())
    const merged = [...rest.filter(r => !parsed.some(p => p.name === r.name)), ...parsed]
    set({ envVars: merged.length ? merged : [{ name: '', value: '' }] })
  }

  return (
    <>
      <fieldset className="fieldset">
        <legend>Environment variables</legend>
        <p className="hint" id={`${id}-env-hint`} style={{ marginTop: 0 }}>
          Applied to dev, staging and prod. Values are committed to the GitOps repo in plain text — don't put passwords or API keys here.
          Tip: paste a <code>.env</code> file into a name field.
        </p>
        {env.length > 0 && (
          <div className="env-rows" role="group" aria-describedby={`${id}-env-hint`}>
            {env.map((e, i) => {
              const err = rowError(e, i)
              return (
                <div className="env-row" key={i}>
                  <input aria-label={`Variable ${i + 1} name`} placeholder="NAME" value={e.name} spellCheck="false" autoComplete="off"
                         aria-invalid={!!err} aria-describedby={err ? `${id}-err-${i}` : undefined}
                         onChange={ev => setRow(i, { name: ev.target.value })} onPaste={ev => onPasteName(i, ev)} className="mono" />
                  <input aria-label={`Variable ${i + 1} value`} placeholder="value" value={e.value} spellCheck="false" autoComplete="off"
                         onChange={ev => setRow(i, { value: ev.target.value })} className="mono" />
                  <button type="button" className="icon-btn" aria-label={`Remove ${e.name || `variable ${i + 1}`}`}
                          onClick={() => set({ envVars: env.filter((_, j) => j !== i) })}><Icon name="trash" size={15} /></button>
                  {err && <span className="field-error" id={`${id}-err-${i}`} role="alert">{err}</span>}
                </div>
              )
            })}
          </div>
        )}
        <button type="button" className="btn small" onClick={() => set({ envVars: [...env, { name: '', value: '' }] })}>
          <Icon name="plus" size={13} />Add variable
        </button>
      </fieldset>

      <fieldset className="fieldset">
        <legend>CPU &amp; memory</legend>
        <div className="chips" role="group" aria-label="Size preset" style={{ marginBottom: 14 }}>
          {SIZES.map(s => (
            <button key={s.id} type="button" aria-pressed={size?.id === s.id} className={`chip${size?.id === s.id ? ' active' : ''}`}
                    onClick={() => set({ cpuRequest: s.cpuRequest, cpuLimit: s.cpuLimit, memoryRequest: s.memoryRequest, memoryLimit: s.memoryLimit })}>
              {s.label} <span className="muted" style={{ color: 'inherit', opacity: 0.7 }}>· {s.cpuLimit} CPU, {s.memoryLimit}</span>
            </button>
          ))}
        </div>
        <div className="row">
          <label>CPU request<input required value={value.cpuRequest} onChange={e => set({ cpuRequest: e.target.value })}
                                   placeholder="50m" spellCheck="false" aria-describedby={`${id}-res-hint`} /></label>
          <label>CPU limit<input required value={value.cpuLimit} onChange={e => set({ cpuLimit: e.target.value })}
                                 placeholder="500m" spellCheck="false" aria-describedby={`${id}-res-hint`} /></label>
        </div>
        <div className="row">
          <label>Memory request<input required value={value.memoryRequest} onChange={e => set({ memoryRequest: e.target.value })}
                                      placeholder="128Mi" spellCheck="false" aria-describedby={`${id}-res-hint`} /></label>
          <label>Memory limit<input required value={value.memoryLimit} onChange={e => set({ memoryLimit: e.target.value })}
                                    placeholder="512Mi" spellCheck="false" aria-describedby={`${id}-res-hint`} /></label>
        </div>
        <p className="hint" id={`${id}-res-hint`}>
          Per pod. CPU in cores or millicores (<code>0.5</code> = <code>500m</code>); memory in <code>Mi</code> or <code>Gi</code>.
          Request is what's reserved, limit is the ceiling — the request can't be higher than the limit.
        </p>
      </fieldset>
    </>
  )
}

/** Drops blank rows before sending to the API. */
export const cleanEnv = env => (env || []).filter(e => e.name.trim()).map(e => ({ name: e.name.trim(), value: e.value }))
