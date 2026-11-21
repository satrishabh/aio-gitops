import { useEffect, useRef, useState } from 'react'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { Empty, PageHead, SkeletonTable, ago } from '../components/ui.jsx'
import { Stale, useAction, usePoll } from '../hooks.jsx'
import CloudTargets from '../components/CloudTargets.jsx'
import { refreshEnvironments } from '../environments.js'

const STATUS = { Successful: ['Healthy', 'Connected'], Failed: ['Degraded', 'Failed'], NotRegistered: ['Missing', 'Not registered'] }
const HEALTH = { Connected: ['green', 'Reachable'], Unreachable: ['red', 'Unreachable'], Unknown: ['gray', 'Not checked yet'] }
const SUB = 'Where each environment deploys. The hub runs the platform, Tekton CI and Argo CD; apps run on clusters reached through Argo CD.'
const CLOUDS = [['aks', 'Azure AKS'], ['gke', 'Google GKE'], ['eks', 'AWS EKS'], ['oke', 'Oracle OKE'], ['doks', 'DigitalOcean DOKS'],
  ['onprem', 'On-premises'], ['other', 'Other Kubernetes']]
const BUILT_IN = ['dev', 'staging', 'prod']
const ENV_NAME = /^[a-z][a-z0-9-]{1,19}$/
const CLUSTER_NAME = /^[a-z0-9]([-a-z0-9]{0,38}[a-z0-9])?$/

/** Native <dialog>: focus trap, Esc and the backdrop come from the browser; focus returns to the opener. */
function Dialog({ title, onClose, children, wide }) {
  const ref = useRef(null)
  const opener = useRef(typeof document !== 'undefined' ? document.activeElement : null)
  useEffect(() => {
    const d = ref.current
    if (d && !d.open) d.showModal()
    const back = opener.current
    return () => { if (back && back.focus) back.focus() }
  }, [])
  const id = `dlg-${title.replace(/\W+/g, '-').toLowerCase()}`
  return (
    <dialog ref={ref} className={`cl-dialog${wide ? ' wide' : ''}`} aria-labelledby={id} onCancel={e => { e.preventDefault(); onClose() }}>
      <div className="cl-dialog-head">
        <h2 id={id}>{title}</h2>
        <button type="button" className="btn ghost small" onClick={onClose} aria-label="Close"><Icon name="x" size={16} /></button>
      </div>
      {children}
    </dialog>
  )
}

function Checks({ checks }) {
  if (!checks?.length) return null
  return (
    <ul className="cl-checks" aria-label="Checks">
      {checks.map((c, i) => {
        const [tone, word] = c.ok === true ? ['green', 'pass'] : c.ok === false ? ['red', 'fail'] : ['amber', 'pending']
        return <li key={i} className={`cl-check ${tone}`}>
          <Badge tone={tone} label={word} /><b>{c.name}</b><span className="muted">{c.detail}</span>
        </li>
      })}
    </ul>
  )
}

function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/x-shellscript' }))
  const a = document.createElement('a')
  a.href = url; a.download = name
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const EMPTY_CLUSTER = { name: '', cloud: 'oke', server: '', ingressController: 'nginx', ingressClass: '', ingressNamespace: '',
  ingressIp: '', baseDomain: '', issuer: '', tls: false }

/** Add cluster (3 steps) or, with `rotate`, steps 2-3 for an existing cluster. */
function AddCluster({ rotate, onClose, onDone }) {
  const [step, setStep] = useState(rotate ? 2 : 1)
  const [form, setForm] = useState(rotate ? { ...EMPTY_CLUSTER, name: rotate.name } : EMPTY_CLUSTER)
  const [script, setScript] = useState(null)
  const [line, setLine] = useState('')
  const [manual, setManual] = useState({ token: '', caData: '' })
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [result, setResult] = useState(null)
  const [copied, setCopied] = useState(false)
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))

  useEffect(() => { if (rotate) loadScript() }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  async function loadScript() {
    setBusy(true); setErr('')
    try {
      setScript(await api(`/api/clusters/bootstrap${rotate ? '?rotate=true' : ''}`, { method: 'POST', body: form }))
      setStep(2)
    } catch (e) { setErr(e.message) } finally { setBusy(false) }
  }
  async function connect() {
    setBusy(true); setErr(''); setResult(null)
    const body = { ...form, bootstrapJson: line.trim() || null, token: manual.token || null, caData: manual.caData || null }
    try {
      const r = await api(rotate ? `/api/clusters/${rotate.name}/token` : '/api/clusters', { method: 'POST', body })
      setResult(r)
      if (r.ok) { setLine(''); setManual({ token: '', caData: '' }); onDone() }
    } catch (e) { setErr(e.message) } finally { setBusy(false) }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(script.script); setCopied(true); setTimeout(() => setCopied(false), 2000) }
    catch { setErr('Copy failed — use Download instead') }
  }
  const nameBad = form.name && !CLUSTER_NAME.test(form.name)
  const stepOk = form.name && !nameBad && /^https:\/\//.test(form.server)
  const steps = rotate ? [[2, 'Bootstrap script'], [3, 'Paste token & test']] : [[1, 'Describe'], [2, 'Bootstrap script'], [3, 'Paste token & test']]

  return (
    <Dialog title={rotate ? `Rotate token · ${rotate.name}` : 'Add cluster'} onClose={onClose} wide>
      <ol className="cl-steps" aria-label="Steps">
        {steps.map(([n, label]) => (
          <li key={n} className={n === step ? 'current' : n < step ? 'done' : ''} aria-current={n === step ? 'step' : undefined}>
            <span className="cl-step-n" aria-hidden="true">{n < step ? <Icon name="check" size={12} /> : n}</span>{label}
          </li>
        ))}
      </ol>
      {err && <p className="error" role="alert">{err}</p>}

      {step === 1 && <form className="form cl-form" onSubmit={e => { e.preventDefault(); if (stepOk) loadScript() }}>
        <div className="cl-grid">
          <label>Name<input required value={form.name} onChange={e => set('name', e.target.value.trim())} placeholder="oci-edge"
            aria-invalid={nameBad || undefined} aria-describedby="cl-name-hint" />
            <span id="cl-name-hint" className="hint">lowercase letters, digits and “-”; Argo CD registers it under this name</span></label>
          <label>Cloud<select value={form.cloud} onChange={e => set('cloud', e.target.value)}>
            {CLOUDS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
          <label className="span-2">API server URL<input required type="url" value={form.server} onChange={e => set('server', e.target.value.trim())}
            placeholder="https://<cluster endpoint>:6443" /></label>
          <label>Ingress controller<select value={form.ingressController} onChange={e => set('ingressController', e.target.value)}>
            <option value="nginx">ingress-nginx</option><option value="traefik">Traefik</option></select></label>
          <label>Ingress class<input value={form.ingressClass} onChange={e => set('ingressClass', e.target.value.trim())} placeholder={form.ingressController} /></label>
          <label>Ingress namespace<input value={form.ingressNamespace} onChange={e => set('ingressNamespace', e.target.value.trim())}
            placeholder={form.ingressController === 'traefik' ? 'traefik' : 'ingress-nginx'} />
            <span className="hint">allowed in through each environment's NetworkPolicy</span></label>
          <label>Ingress IP<input value={form.ingressIp} onChange={e => set('ingressIp', e.target.value.trim())} placeholder="203.0.113.10" /></label>
          <label>Base domain<input value={form.baseDomain} onChange={e => set('baseDomain', e.target.value.trim())}
            placeholder={form.ingressIp ? `${form.ingressIp}.nip.io` : 'apps.example.com'} />
            <span className="hint">apps get &lt;app&gt;-&lt;env&gt;.&lt;base domain&gt;</span></label>
          <label>Certificate issuer<input value={form.issuer} onChange={e => set('issuer', e.target.value.trim())} placeholder="letsencrypt" /></label>
          <label className="check"><input type="checkbox" role="switch" checked={form.tls} onChange={e => set('tls', e.target.checked)} />
            HTTPS on generated hosts (cert-manager installed)</label>
        </div>
        <div className="cl-actions">
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className={`btn primary${busy ? ' busy' : ''}`} disabled={!stepOk || busy}>Next: bootstrap script<Icon name="arrow" size={14} /></button>
        </div>
      </form>}

      {step === 2 && !script && <p className="muted">Generating the script…</p>}
      {step === 2 && script && <div className="cl-step2">
        <p>Run this once with <code>kubectl</code> pointing at <b>{form.name}</b>, as someone allowed to create RBAC there. It creates a
          service account <code>aio-system/aio-deployer</code> that can only create and label namespaces, read health data, and manage
          app resources inside the environment namespaces AIO binds it to — no cluster-admin, and no admin kubeconfig leaves your machine.
          It prints one line of JSON with the token and CA. The script itself contains no secrets.</p>
        <div className="cl-script-tools">
          <button type="button" className="btn" onClick={copy}><Icon name="file" size={14} />{copied ? 'Copied' : 'Copy script'}</button>
          <button type="button" className="btn" onClick={() => download(script.fileName, script.script)}><Icon name="download" size={14} />Download {script.fileName}</button>
          <span className="muted small-print" aria-live="polite">Revoke later with <code>bash {script.fileName} --revoke</code></span>
        </div>
        <pre className="cl-script" tabIndex={0} aria-label="Bootstrap script">{script.script}</pre>
        <div className="cl-actions">
          {!rotate && <button type="button" className="btn" onClick={() => setStep(1)}>Back</button>}
          <button type="button" className="btn primary" onClick={() => setStep(3)}>I ran it: paste the token<Icon name="arrow" size={14} /></button>
        </div>
      </div>}

      {step === 3 && <form className="form cl-form" onSubmit={e => { e.preventDefault(); connect() }}>
        <label>The line the script printed
          <textarea rows={3} value={line} onChange={e => setLine(e.target.value)} spellCheck="false" autoComplete="off"
            placeholder={'{"cluster":"' + form.name + '","server":"https://…","token":"…","caData":"…"}'} /></label>
        <details className="cl-manual"><summary>Or paste the token and CA separately</summary>
          <label>Token<input type="password" autoComplete="off" value={manual.token} onChange={e => setManual({ ...manual, token: e.target.value })} /></label>
          <label>CA (base64)<textarea rows={2} value={manual.caData} onChange={e => setManual({ ...manual, caData: e.target.value })} spellCheck="false" /></label>
        </details>
        <p className="hint">AIO tests the token (API reachable, each needed permission), stores it only in the Argo CD cluster Secret on the hub
          and saves the cluster without credentials.</p>
        <Checks checks={result?.checks} />
        {result && <p className={result.ok ? 'tone-green' : 'tone-red'} role="status">{result.ok ? `${result.cluster} is connected.` : result.message}</p>}
        <div className="cl-actions">
          <button type="button" className="btn" onClick={() => setStep(2)}>Back</button>
          {result?.ok ? <button type="button" className="btn primary" onClick={onClose}>Done</button>
            : <button type="submit" className={`btn primary${busy ? ' busy' : ''}`} disabled={busy || !(line.trim() || (manual.token && manual.caData))}>
              <Icon name="check" size={14} />{rotate ? 'Replace token & test' : 'Register & test'}</button>}
        </div>
      </form>}
    </Dialog>
  )
}

function AddEnvironment({ envs, clusters, onClose, onDone }) {
  const last = envs[envs.length - 1]
  const [form, setForm] = useState({ name: '', cluster: clusters.find(c => !c.hub)?.name || clusters[0]?.name || '', namespace: '',
    promoteFrom: last?.name || '', prodLike: !!last?.prodLike, approvalRequired: null, spotAllowed: false })
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [result, setResult] = useState(null)
  const set = (k, v) => setForm(f => ({ ...f, [k]: v }))
  const from = envs.find(e => e.name === form.promoteFrom)
  const approval = form.approvalRequired ?? (form.prodLike || !!from?.prodLike)
  const nameBad = form.name && !ENV_NAME.test(form.name)

  async function save(e) {
    e.preventDefault()
    setBusy(true); setErr('')
    try {
      const r = await api('/api/environments', { method: 'POST', body: { ...form, namespace: form.namespace || null, approvalRequired: approval } })
      setResult(r); onDone()
    } catch (x) { setErr(x.message) } finally { setBusy(false) }
  }

  return (
    <Dialog title="Add environment" onClose={onClose}>
      {err && <p className="error" role="alert">{err}</p>}
      {result ? <>
        <Checks checks={result.steps.map(s => ({ name: s.step, ok: s.ok, detail: s.detail }))} />
        <p className="hint">Every project now has <code>values-{result.environment.name}.yaml</code> (nothing running yet) and an Argo CD app.
          Promote from {result.environment.promoteFrom} to deploy there.</p>
        <div className="cl-actions"><button type="button" className="btn primary" onClick={onClose}>Done</button></div>
      </> : <form className="form cl-form" onSubmit={save}>
        <div className="cl-grid">
          <label>Name<input required value={form.name} onChange={e => set('name', e.target.value.trim())} placeholder="edge" aria-invalid={nameBad || undefined} />
            <span className="hint">2–20 characters: a–z, 0–9, “-”, starting with a letter</span></label>
          <label>Cluster<select value={form.cluster} onChange={e => set('cluster', e.target.value)}>
            {clusters.map(c => <option key={c.name} value={c.name}>{c.name}{c.hub ? ' (hub)' : ''}</option>)}</select></label>
          <label>Namespace<input value={form.namespace} onChange={e => set('namespace', e.target.value.trim())} placeholder={form.name || 'same as the name'} /></label>
          <label>Promote from<select value={form.promoteFrom} onChange={e => set('promoteFrom', e.target.value)}>
            {envs.map(e => <option key={e.name} value={e.name}>{e.name}</option>)}</select>
            <span className="hint">default: the last one, so it goes after {last?.name}</span></label>
          <label className="check"><input type="checkbox" role="switch" checked={form.prodLike} onChange={e => set('prodLike', e.target.checked)} />
            Production (counts in DORA metrics, never Spot)</label>
          <label className="check"><input type="checkbox" role="switch" checked={approval} onChange={e => set('approvalRequired', e.target.checked)} />
            Promotions and rollbacks need an approver</label>
          {!form.prodLike && <label className="check"><input type="checkbox" role="switch" checked={form.spotAllowed} onChange={e => set('spotAllowed', e.target.checked)} />
            New apps may use Spot nodes</label>}
        </div>
        <p className="hint">Saving creates the namespace on {form.cluster || 'the cluster'} with the same baseline as the others (Pod Security labels,
          NetworkPolicies, LimitRange, pull secret), then a values file and an Argo CD app for every project.</p>
        <div className="cl-actions">
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button type="submit" className={`btn primary${busy ? ' busy' : ''}`} disabled={busy || !form.name || nameBad || !form.cluster}><Icon name="plus" size={14} />Add {form.name || 'environment'}</button>
        </div>
      </form>}
    </Dialog>
  )
}

/** Dry run of "re-apply cluster settings" (ingress class, controller, default host), then apply. */
function Reapply({ env, onClose }) {
  const [preview, setPreview] = useState(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [applied, setApplied] = useState(null)
  useEffect(() => {
    api(`/api/environments/${env.name}/reapply-cluster-settings?dryRun=true`, { method: 'POST' }).then(setPreview, e => setErr(e.message))
  }, [env.name])
  async function apply() {
    setBusy(true)
    try { setApplied(await api(`/api/environments/${env.name}/reapply-cluster-settings?dryRun=false`, { method: 'POST' })) }
    catch (e) { setErr(e.message) } finally { setBusy(false) }
  }
  const changed = (preview?.changes || []).filter(c => c.changed)
  return (
    <Dialog title={`Re-apply ${env.cluster} settings to ${env.name}`} onClose={onClose} wide>
      <p>Writes the cluster's <code>ingress.className</code>, <code>ingress.controller</code> and moves generated hosts on another
        nip.io IP to <code>&lt;app&gt;-{env.name}.{env.baseDomain || '<base domain>'}</code>. Custom hosts and routes are never touched.</p>
      {err && <p className="error" role="alert">{err}</p>}
      {!preview && !err && <p className="muted">Computing the preview…</p>}
      {preview && (changed.length === 0 ? <p className="tone-green">Every project already matches the cluster settings.</p> : <>
        <p><b>{changed.length}</b> values file{changed.length === 1 ? '' : 's'} {applied ? 'committed.' : 'would change:'}</p>
        {changed.map(c => <details key={c.path} className="cl-diff" open={changed.length <= 3}>
          <summary><code>{c.path}</code></summary>
          <pre>{c.diff.map((l, i) => <span key={i} className={l.startsWith('+') ? 'add' : 'del'}>{l}{'\n'}</span>)}</pre>
        </details>)}
      </>)}
      {preview?.errors?.length > 0 && <p className="tone-red">{preview.errors.join(' · ')}</p>}
      <div className="cl-actions">
        <button type="button" className="btn" onClick={onClose}>{applied ? 'Done' : 'Cancel'}</button>
        {!applied && changed.length > 0 && <button type="button" className={`btn primary${busy ? ' busy' : ''}`} disabled={busy} onClick={apply}>
          <Icon name="check" size={14} />Commit {changed.length} change{changed.length === 1 ? '' : 's'}</button>}
      </div>
    </Dialog>
  )
}

export default function Clusters() {
  const { data, error, stale, refresh } = usePoll(() => Promise.all([api('/api/clusters'), api('/api/environments')]), 15000)
  const after = () => { refresh(); refreshEnvironments() }
  const { busy, run } = useAction(after)
  const [dialog, setDialog] = useState(null)

  if (!data) return <><PageHead title="Clusters" sub={SUB} />
    {error ? <p className="error" role="alert">{error}</p> : <SkeletonTable rows={3} cols={7} label="Loading clusters" />}</>
  const [clusters, envs] = data
  const close = () => setDialog(null)

  function remove(c) {
    if (!window.confirm(`Remove ${c.name}? Argo CD forgets the cluster (its cluster Secret is deleted). Revoke the token on the cluster with the bootstrap script's --revoke.`)) return
    run(`rm-${c.name}`, () => api(`/api/clusters/${c.name}`, { method: 'DELETE' }), `${c.name} removed`)
  }
  function removeEnv(e) {
    if (!window.confirm(`Remove environment ${e.name}? Every project's ${e.name} Argo CD app and values file are deleted; the namespace on ${e.cluster} stays.`)) return
    run(`rm-env-${e.name}`, () => api(`/api/environments/${e.name}`, { method: 'DELETE' }), `${e.name} removed`)
  }

  return (
    <>
      <Stale stale={stale} error={error} onRetry={refresh} />
      <PageHead title="Clusters" sub={SUB}>
        <button type="button" className="btn primary" onClick={() => setDialog({ kind: 'cluster' })}><Icon name="plus" size={15} />Add cluster</button>
      </PageHead>

      {clusters.length === 0 ? <div className="card"><Empty icon="server" title="No clusters yet">Add one with <b>Add cluster</b>.</Empty></div> : (
        <div className="card flush">
          <table className="stack-table clusters-table">
            <caption className="sr-only">Clusters</caption>
            <thead><tr><th>Cluster</th><th>Environments</th><th>Health</th><th>Argo CD</th><th>Ingress</th><th>Blue-green</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {clusters.map(c => {
                const [tone, label] = STATUS[c.status] || ['Unknown', c.status || 'Unknown']
                const [htone, hlabel] = HEALTH[c.health] || HEALTH.Unknown
                return (
                  <tr key={c.name}>
                    <td className="stack-title"><span className="cluster-name"><Icon name="server" size={15} /><b>{c.name}</b>
                      {c.hub && <Badge tone="gray" label="hub" quiet />}{c.cloud && <Badge tone="blue" label={c.cloud} quiet />}</span>
                      <div className="muted mono small-print">{c.server}</div></td>
                    <td data-label="Environments"><span className="badge-row">{c.envs.length ? c.envs.map(e => {
                      const env = envs.find(x => x.name === e)
                      return <Badge key={e} tone={env?.prodLike ? 'amber' : 'blue'} label={e} quiet />
                    }) : <span className="muted">none</span>}</span></td>
                    <td data-label="Health"><Badge tone={htone} label={hlabel} />
                      {c.healthError && <div className="muted small-print cluster-message">{c.healthError}</div>}
                      {c.checkedAt && <div className="muted small-print" title={fmtTime(c.checkedAt)}>checked {ago(c.checkedAt)}</div>}</td>
                    <td data-label="Argo CD"><Badge value={tone} label={label} />
                      {c.version && <div className="muted mono small-print">{c.version}</div>}
                      {c.message && <div className="muted small-print cluster-message">{c.message}</div>}</td>
                    <td data-label="Ingress">{c.baseDomain ? <code>{c.baseDomain}</code> : <span className="muted">no domain</span>}
                      <div className="muted mono small-print">{c.ingressClass} · {c.ingressController}{c.ingressNamespace ? ` · ns ${c.ingressNamespace}` : ''}</div></td>
                    <td data-label="Blue-green">{c.rollouts ? <Badge tone="green" label="Argo Rollouts" quiet /> : <span className="muted">rolling update</span>}</td>
                    <td className="cl-row-actions">
                      <button type="button" className={`btn small${busy === `chk-${c.name}` ? ' busy' : ''}`} disabled={!!busy}
                        onClick={() => run(`chk-${c.name}`, () => api(`/api/clusters/${c.name}/check`, { method: 'POST' }), r => `${c.name}: ${r.health}`)}
                        aria-label={`Check ${c.name} now`}><Icon name="refresh" size={13} />Check</button>
                      {c.managed && <button type="button" className="btn small" onClick={() => setDialog({ kind: 'rotate', cluster: c })}
                        aria-label={`Rotate the token of ${c.name}`}><Icon name="lock" size={13} />Rotate token</button>}
                      {c.managed && <button type="button" className={`btn small danger${busy === `rm-${c.name}` ? ' busy' : ''}`} disabled={!!busy || c.envs.length > 0}
                        title={c.envs.length ? `Used by ${c.envs.join(', ')}: remove those environments first` : 'Remove from AIO and Argo CD'}
                        onClick={() => remove(c)} aria-label={`Remove ${c.name}`}><Icon name="trash" size={13} />Remove</button>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <section className="card cl-envs" aria-labelledby="cl-envs-h">
        <div className="card-head">
          <h3 id="cl-envs-h">Environments</h3>
          <button type="button" className="btn primary small" onClick={() => setDialog({ kind: 'env' })}><Icon name="plus" size={14} />Add environment</button>
        </div>
        <p className="muted">Images move along this path; each stop runs in its own namespace on its cluster.</p>
        <ol className="cl-env-path">
          {envs.map((e, i) => {
            const down = e.clusterStatus === 'Unreachable'
            return (
              <li key={e.name} className={`cl-env${e.prodLike ? ' prod' : ''}${down ? ' down' : ''}`}>
                <div className="cl-env-head"><span className="cl-env-n" aria-hidden="true">{i + 1}</span><b>{e.name}</b>
                  {e.approvalRequired && <Badge tone="amber" label="approval" quiet />}{e.prodLike && <Badge tone="orange" label="production" quiet />}</div>
                <dl>
                  <div><dt>From</dt><dd>{e.promoteFrom || <span className="muted">CI builds</span>}</dd></div>
                  <div><dt>Cluster</dt><dd><code>{e.cluster}</code>{down && <span className="tone-red"> · cluster unreachable</span>}</dd></div>
                  <div><dt>Namespace</dt><dd><code>{e.namespace}</code></dd></div>
                </dl>
                <div className="cl-env-actions">
                  <button type="button" className="btn small" onClick={() => setDialog({ kind: 'reapply', env: e })}
                    title="Preview, then write this cluster's ingress class, controller and default hosts into every project's values"><Icon name="settings" size={13} />Re-apply cluster settings</button>
                  {e.promoteFrom && !BUILT_IN.includes(e.name) && !envs.some(x => x.promoteFrom === e.name) && <button type="button"
                    className={`btn small danger${busy === `rm-env-${e.name}` ? ' busy' : ''}`} disabled={!!busy}
                    onClick={() => removeEnv(e)} aria-label={`Remove environment ${e.name}`}><Icon name="trash" size={13} />Remove</button>}
                </div>
              </li>
            )
          })}
        </ol>
      </section>

      <p className="hint page-hint">
        A cluster added here is reached with a limited token kept only in its Argo CD cluster Secret. Connectors keep running on the
        hub only: environments on other clusters show project services as not available there. Health is checked every 5 minutes;
        an unreachable cluster never blocks the rest of AIO.
      </p>
      <CloudTargets />

      {dialog?.kind === 'cluster' && <AddCluster onClose={close} onDone={after} />}
      {dialog?.kind === 'rotate' && <AddCluster rotate={dialog.cluster} onClose={close} onDone={after} />}
      {dialog?.kind === 'env' && <AddEnvironment envs={envs} clusters={clusters} onClose={close} onDone={after} />}
      {dialog?.kind === 'reapply' && <Reapply env={dialog.env} onClose={close} />}
    </>
  )
}
