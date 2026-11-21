import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import Badge from './Badge.jsx'
import Icon from './Icon.jsx'
import { Skeleton } from './ui.jsx'
import { useToast } from './Toast.jsx'
import { useEnvironments } from '../environments.js'

const KIND = {
  env: 'Env var', envFrom: 'Loads', volume: 'Volume', volumeMount: 'Mount', secret: 'Secret', configMap: 'ConfigMap',
}

/**
 * Status of a promotion in words: DEPLOYING spins until the target is healthy; FAILED shows the reason and, when
 * the pod crashed, its last log lines (collapsed).
 */
export function PromotionStatus({ pr, compact }) {
  const deploying = pr.status === 'DEPLOYING'
  return (
    <div className="pm-status">
      {deploying
        ? <span className="badge blue pm-deploying"><span className="pm-spin" aria-hidden="true" />Deploying</span>
        : <Badge value={pr.status} />}
      {deploying && !compact && <div className="muted fl-small">Waiting for {pr.toEnv} to be healthy with {pr.imageTag}</div>}
      {pr.message && <div className={pr.status === 'FAILED' ? 'pm-reason' : 'muted fl-small'}>{pr.message}</div>}
      {pr.detail && (
        <details className="pm-logs">
          <summary>Last log lines</summary>
          <pre tabIndex={0} aria-label={`Last log lines of ${pr.toEnv}`}>{pr.detail}</pre>
        </details>
      )}
    </div>
  )
}

function CopyCmd({ cmd }) {
  const toast = useToast()
  return (
    <div className="pm-cmd">
      <code>{cmd}</code>
      <button type="button" className="icon-btn" aria-label="Copy command" title="Copy"
              onClick={() => navigator.clipboard.writeText(cmd).then(() => toast('ok', 'Command copied'), () => toast('error', 'Copy failed'))}>
        <Icon name="list" size={14} />
      </button>
    </div>
  )
}

/**
 * Promote from one env to the next after the pre-promotion check. With findings the user can copy the source's
 * settings into the target (in the same commit as the tag), promote anyway (typing the project name), or cancel.
 * Secrets/ConfigMaps missing in the target namespace can't be copied: they're listed with kubectl commands.
 */
export function PromoteDialog({ project, from, to, onClose, onDone }) {
  const { gated: needsApproval } = useEnvironments()
  const toast = useToast()
  const [check, setCheck] = useState(null)
  const [error, setError] = useState('')
  const [typed, setTyped] = useState('')
  const [values, setValues] = useState({})   // edited values of env vars Copy settings adds (plain values only)
  const [busy, setBusy] = useState(null)
  const ref = useRef(null)
  const busyRef = useRef(busy)
  busyRef.current = busy

  useEffect(() => {
    api(`/api/projects/${project.id}/promotions/check?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)
      .then(c => {
        setCheck(c)
        setValues(Object.fromEntries(c.missing.filter(f => f.kind === 'env' && f.value != null).map(f => [f.name, f.value])))
      }).catch(e => setError(e.message))
  }, [project.id, from, to])

  // Modal behaviour: focus inside, Tab trapped, Esc closes, focus returns to the Promote button
  useEffect(() => {
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const el = ref.current
    el.focus()
    const onKey = e => {
      if (e.key === 'Escape' && !busyRef.current) { e.preventDefault(); onClose() }
      if (e.key !== 'Tab') return
      const controls = [...el.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), summary, [tabindex="0"]')]
      const first = controls[0], last = controls[controls.length - 1]
      if (!first) { e.preventDefault(); el.focus(); return }
      if (e.shiftKey && (document.activeElement === first || document.activeElement === el)) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && (document.activeElement === last || document.activeElement === el)) { e.preventDefault(); first.focus() }
    }
    el.addEventListener('keydown', onKey)
    return () => { el.removeEventListener('keydown', onKey); document.body.style.overflow = overflow; previous?.focus() }
  }, [])

  const gated = needsApproval(to)
  const missing = check?.missing || []
  const differs = check?.differs || []
  const copyable = missing.filter(f => f.copyable)
  const envAdds = copyable.filter(f => f.kind === 'env')
  const otherAdds = copyable.filter(f => f.kind !== 'env')
  // only values the user changed are sent; the rest are copied from the source as they are in git
  const overrides = Object.fromEntries(envAdds.filter(f => f.value != null && values[f.name] !== f.value).map(f => [f.name, values[f.name] ?? '']))
  const objects = missing.filter(f => !f.copyable)
  const clean = check && missing.length === 0 && differs.length === 0
  const canCopy = copyable.length > 0 || differs.length > 0
  const confirmed = typed.trim() === project.name
  // Copy settings fixes everything git can carry; missing Secrets/ConfigMaps still need the typed confirmation
  const copyBlocked = objects.length > 0 && !confirmed

  async function go(mode) {
    setBusy(mode)
    try {
      await api(`/api/projects/${project.id}/promote`, {
        method: 'POST',
        body: { from, to, copySettings: mode === 'copy', confirm: confirmed ? project.name : undefined,
                envOverrides: mode === 'copy' && Object.keys(overrides).length ? overrides : undefined },
      })
      toast('ok', gated ? `Prod promotion requested${mode === 'copy' ? ' with copied settings' : ''} — approve it on the Approvals page`
        : `Promoting to ${to}${mode === 'copy' ? ' with settings from ' + from : ''} — watching until it is healthy`)
      onDone()
    } catch (e) {
      setError(e.message)
      setBusy(null)
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : onClose}>
      <aside ref={ref} role="dialog" aria-modal="true" aria-labelledby="promote-title" aria-describedby="promote-sub"
             tabIndex={-1} className="drawer pm-dialog" onClick={e => e.stopPropagation()}>
        <div className="card-head">
          <h3 id="promote-title">Promote {project.name}: {from} <Icon name="arrow" size={14} /> {to}</h3>
          <button className="icon-btn" aria-label="Close" onClick={onClose} disabled={!!busy}><Icon name="x" /></button>
        </div>
        <p id="promote-sub" className="muted fl-small">
          Promotion moves the image only. This check compares <code>values-{from}.yaml</code> with <code>values-{to}.yaml</code>
          {check?.clusterChecked ? <> and namespace <code>{check.namespace}</code></> : null}.
          {gated && ' Prod changes wait for an approver.'}
        </p>

        {!check && !error && <Skeleton h={120} />}
        {error && <p className="error" role="alert">{error}</p>}

        {check && clean && (
          <p className="pm-ok"><Icon name="check" size={15} /> {to} has every setting {from} runs with.</p>
        )}

        {check && !clean && (
          <div className="pm-findings" aria-live="polite">
            <p className="pm-warn"><Icon name="alert" size={15} />
              {to} lacks {missing.length + differs.length} setting{missing.length + differs.length === 1 ? '' : 's'} that {from} runs with. Promoting only the image may leave {to} broken.</p>

            {envAdds.length > 0 && <section aria-labelledby="pm-env-h">
              <h4 id="pm-env-h">Env vars missing in {to} (Copy settings adds them)</h4>
              <p className="muted fl-small">Values come from <code>values-{from}.yaml</code>; edit any that should differ in {to}.</p>
              <ul className="pm-list pm-env">
                {envAdds.map(f => {
                  const id = `pm-env-${f.name}`
                  return <li key={f.name} className={f.envSpecific ? 'pm-env-flag' : undefined}>
                    <label htmlFor={id}><code>{f.name}</code></label>
                    {f.value != null
                      ? <input id={id} value={values[f.name] ?? ''} onChange={e => setValues({ ...values, [f.name]: e.target.value })}
                               autoComplete="off" spellCheck={false} disabled={!!busy}
                               aria-describedby={f.envSpecific ? `${id}-hint` : undefined} />
                      : <span className="pm-ref" id={id}>from <code>{f.valueFrom}</code></span>}
                    {f.envSpecific && <span id={`${id}-hint`} className="pm-hint"><Icon name="alert" size={12} /> check this for {to}</span>}
                  </li>
                })}
              </ul>
            </section>}

            {otherAdds.length > 0 && <section aria-labelledby="pm-copyable-h">
              <h4 id="pm-copyable-h">Also missing in {to} (Copy settings adds them)</h4>
              <ul className="pm-list">
                {otherAdds.map(f => <li key={f.kind + f.name}><span className="pm-kind">{KIND[f.kind] || f.kind}</span> <code>{f.name}</code>
                  <span className="muted fl-small"> {f.detail}</span></li>)}
              </ul>
            </section>}

            {differs.length > 0 && <section aria-labelledby="pm-differs-h">
              <h4 id="pm-differs-h">Different (Copy settings uses {from}'s)</h4>
              <table className="pm-diff">
                <thead><tr><th scope="col">Setting</th><th scope="col">{from}</th><th scope="col">{to}</th></tr></thead>
                <tbody>{differs.map(d => <tr key={d.key}>
                  <th scope="row" data-label="Setting"><code>{d.key}</code></th>
                  <td data-label={from}><code>{d.from}</code></td>
                  <td data-label={to}><code>{d.to}</code></td>
                </tr>)}</tbody>
              </table>
            </section>}

            {objects.length > 0 && <section aria-labelledby="pm-objects-h">
              <h4 id="pm-objects-h">Not in namespace {check.namespace} (not in git, can't be copied)</h4>
              <p className="muted fl-small">Create {objects.length === 1 ? 'it' : 'them'} on cluster <code>{check.cluster}</code> first, by hand or copied from {from}:</p>
              <ul className="pm-list">
                {objects.map(f => <li key={f.kind + f.name}>
                  <span className="pm-kind bad">{KIND[f.kind]}</span> <code>{f.name}</code>
                  {f.commands.map(c => <CopyCmd key={c} cmd={c} />)}
                </li>)}
              </ul>
              <p className="muted fl-small">Fill in the real keys and values; copying from {from} only fits when both envs may share them.</p>
            </section>}
          </div>
        )}

        {check?.clusterNote && <p className="muted fl-small pm-note"><Icon name="info" size={13} /> {check.clusterNote}</p>}
        {check && !clean && <p className="muted fl-small">Never copied: {check.neverCopied.join(', ')}.</p>}

        {check && !clean && (
          <label className="pm-confirm">
            {canCopy && objects.length === 0
              ? <>To promote without copying, type <b>{project.name}</b></>
              : <>To promote anyway, type <b>{project.name}</b></>}
            <input value={typed} onChange={e => setTyped(e.target.value)} autoComplete="off" spellCheck={false}
                   aria-describedby="promote-sub" disabled={!!busy} />
          </label>
        )}

        <div className="pm-actions">
          {check && clean && (
            <button className={`btn primary${busy === 'promote' ? ' busy' : ''}`} disabled={!!busy} onClick={() => go('promote')}>
              <Icon name={gated ? 'lock' : 'arrow'} size={14} />{gated ? 'Request promotion' : `Promote to ${to}`}</button>
          )}
          {check && !clean && canCopy && (
            <button className={`btn primary${busy === 'copy' ? ' busy' : ''}`} disabled={!!busy || copyBlocked} onClick={() => go('copy')}
                    title={copyBlocked ? 'Create the missing Secrets/ConfigMaps first, or type the project name' : undefined}>
              <Icon name="layers" size={14} />Copy settings and promote</button>
          )}
          {check && !clean && (
            <button className={`btn danger${busy === 'anyway' ? ' busy' : ''}`} disabled={!!busy || !confirmed} onClick={() => go('anyway')}>
              <Icon name="alert" size={14} />Promote anyway</button>
          )}
          <button className="btn" onClick={onClose} disabled={!!busy}>Cancel</button>
        </div>
      </aside>
    </div>
  )
}
