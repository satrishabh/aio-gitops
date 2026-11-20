import { useState } from 'react'
import { api, fmtTime } from '../api.js'
import Badge from '../components/Badge.jsx'
import Icon from '../components/Icon.jsx'
import { useToast } from '../components/Toast.jsx'
import { Empty, PageHead, Skeleton } from '../components/ui.jsx'
import { Stale, usePoll } from '../hooks.jsx'

// status -> [badge colour key, label]
const STATUS = {
  HEALTHY: ['Healthy', 'Healthy'], PROGRESSING: ['Progressing', 'Installing'], DEGRADED: ['Degraded', 'Degraded'],
  NOT_INSTALLED: ['Unknown', 'Not installed'], PLANNED: ['CANCELLED', 'Planned'], NOT_FOR_THIS_CLOUD: ['CANCELLED', 'Not for this cloud'],
  OFF: ['CANCELLED', 'Off'], UNKNOWN: ['Unknown', 'Unknown'],
}
const KIND = { CORE: 'core', MANAGED: 'managed', PLANNED: 'planned' }

export default function Connectors() {
  const { data, error, stale, refresh } = usePoll(() => api('/api/connectors'), 15000)
  const toast = useToast()
  const [busy, setBusy] = useState({})
  const [tests, setTests] = useState({})

  if (!data) return error ? <p className="error">{error}</p> : <Skeleton h={260} />

  async function act(c, action, confirmText) {
    if (confirmText && !window.confirm(confirmText)) return
    setBusy(b => ({ ...b, [c.id]: action }))
    try {
      const r = await api(`/api/connectors/${c.id}/${action}`, { method: 'POST' })
      if (action === 'test') {
        setTests(t => ({ ...t, [c.id]: r }))
        toast(r.ok ? 'ok' : 'error', `${c.name}: ${r.ok ? 'all checks passed' : 'some checks failed'}`)
      } else {
        toast('ok', `${c.name}: ${r.status}${r.version ? ` (${r.version})` : ''}`)
      }
      refresh()
    } catch (err) {
      toast('error', `${c.name}: ${err.message}`)
    } finally {
      setBusy(b => ({ ...b, [c.id]: null }))
    }
  }

  async function switchPhase(p, state) {
    const text = state === 'on'
      ? `Switch on phase ${p.phase}? This installs ${p.connectors.join(', ')} (dependencies first). Do it once every system they need is ready (see docs/architecture/CONNECTORS.md).`
      : `Switch off phase ${p.phase}? This removes ${p.connectors.join(', ')} and everything they installed, stored data included.`
    if (!window.confirm(text)) return
    const key = `phase-${p.phase}`
    setBusy(b => ({ ...b, [key]: state }))
    try {
      const r = await api(`/api/connectors/phases/${encodeURIComponent(p.phase)}/${state}`, { method: 'POST' })
      const failed = r.steps.filter(s => !s.ok)
      toast(failed.length ? 'error' : 'ok', failed.length
        ? `Phase ${p.phase}: ${failed.map(s => `${s.name}: ${s.detail}`).join('; ')}`
        : `Phase ${p.phase} switched ${state}: ${r.steps.map(s => `${s.name} ${s.detail}`).join(', ')}`)
      refresh()
    } catch (err) {
      toast('error', `Phase ${p.phase}: ${err.message}`)
    } finally {
      setBusy(b => ({ ...b, [key]: null }))
    }
  }

  const groups = data.connectors.reduce((acc, c) => ((acc[c.category] ||= []).push(c), acc), {})
  const count = k => data.connectors.filter(c => c.kind === k).length

  return (
    <>
      <PageHead title="Connectors"
        sub={`Open-source tools AIO works with: ${count('CORE')} core, ${count('MANAGED')} managed from here, ${count('PLANNED')} planned. Cloud: ${data.cloud}.`} />
      <Stale stale={stale} error={error} onRetry={refresh} />
      {data.catalog && <p className="muted" style={{ fontSize: '0.8rem', margin: '0 0 8px' }}>
        Catalog: <span className="mono">{data.catalog.location}</span>{data.catalog.revision ? ` @ ${data.catalog.revision}` : ''}
        {data.catalog.loadedAt ? ` · loaded ${fmtTime(data.catalog.loadedAt)}` : ''}{data.site ? ` · site ${data.site}` : ''}
      </p>}
      {data.catalog?.lastError && <p className="error">Catalog refresh failed, showing the last good catalog: {data.catalog.lastError}</p>}
      <div className="banner"><Icon name="plug" size={15} />
        Managed connectors install through Argo CD from a pinned chart. <b>Test</b> checks they answer, <b>Roll back</b> returns to the
        previous pinned version, <b>Disable</b> removes them. If one is down, AIO keeps working without it.
      </div>

      {data.phases?.length > 0 && <section style={{ marginTop: 20 }}>
        <h3 style={{ margin: '0 0 4px' }}>Phases</h3>
        <p className="muted" style={{ fontSize: '0.8rem', margin: '0 0 8px' }}>
          A phase stays off until you switch it on: do that once every system it needs is ready. Switching off is the way back.
        </p>
        <div className="card flush">
          <table>
            <thead><tr><th>Phase</th><th>Connectors</th><th>State</th><th style={{ textAlign: 'right' }}>Switch</th></tr></thead>
            <tbody>
              {data.phases.map(p => {
                const b = busy[`phase-${p.phase}`]
                const state = p.on === 0 ? ['CANCELLED', 'Off'] : p.on === p.total ? ['Healthy', 'On'] : ['Progressing', `${p.on} of ${p.total} on`]
                return (
                  <tr key={p.phase}>
                    <td><b>Phase {p.phase}</b></td>
                    <td style={{ fontSize: '0.8rem' }}>{p.connectors.join(', ')}</td>
                    <td><Badge value={state[0]} label={state[1]} /></td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {p.on < p.total && <button className="btn primary" disabled={!!b} onClick={() => switchPhase(p, 'on')}>
                        <Icon name="play" size={13} />{b === 'on' ? 'Switching on…' : 'Switch on'}</button>}
                      {p.on > 0 && <button className="btn danger" disabled={!!b} onClick={() => switchPhase(p, 'off')}>
                        <Icon name="trash" size={13} />{b === 'off' ? 'Switching off…' : 'Switch off'}</button>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </section>}

      {data.connectors.length === 0 ? <Empty icon="plug" title="No connectors in the catalog" /> : Object.entries(groups).map(([cat, list]) => (
        <section key={cat} style={{ marginTop: 20 }}>
          <h3 style={{ margin: '0 0 8px' }}>{cat}</h3>
          <div className="card flush">
            <table>
              <thead><tr><th>Connector</th><th>Status</th><th>Version</th><th>Last test</th><th style={{ textAlign: 'right' }}>Actions</th></tr></thead>
              <tbody>
                {list.map(c => {
                  const [tone, label] = STATUS[c.status] || STATUS.UNKNOWN
                  const test = tests[c.id] || c.lastTest
                  const installed = c.kind === 'MANAGED' && c.status !== 'NOT_INSTALLED' && c.status !== 'NOT_FOR_THIS_CLOUD'
                  const b = busy[c.id]
                  return (
                    <tr key={c.id}>
                      <td><b>{c.name}</b> <span className="badge gray quiet">{KIND[c.kind]}{c.phase && c.kind !== 'CORE' ? ` · phase ${c.phase}` : ''}</span>
                        <div className="muted" style={{ fontSize: '0.8rem', maxWidth: 420 }}>{c.description}</div>
                        {c.requires?.length > 0 && <div className="muted" style={{ fontSize: '0.75rem' }}>Needs {c.requires.join(', ')}</div>}
                        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 4 }}>
                          {c.license && <span className="badge gray quiet" title="License">{c.license}</span>}
                          {c.foundation && <span className="badge gray quiet" title="Governance">{c.foundation}</span>}
                          {c.origin && <span className="badge gray quiet" title="Project origin">{c.origin}</span>}
                          {c.sso && c.sso !== 'none' && <span className="badge gray quiet" title="Single sign-on">SSO: {c.sso === 'oidc' ? 'Keycloak' : 'gate'}</span>}
                          {c.homepage && <a href={c.homepage} target="_blank" rel="noreferrer" style={{ fontSize: '0.75rem' }}>project <Icon name="external" size={11} /></a>}
                        </div>
                        {installed && c.accessHint && <details style={{ fontSize: '0.75rem' }}><summary className="muted">How to open</summary>
                          {c.id === 'prometheus' && data.links?.prometheus
                            ? <a href={data.links.prometheus} target="_blank" rel="noreferrer">Open Grafana <Icon name="external" size={12} /></a>
                            : <code style={{ whiteSpace: 'normal' }}>{c.accessHint}</code>}
                        </details>}
                      </td>
                      <td><Badge value={tone} label={label} />
                        {c.message && <div className="muted" style={{ fontSize: '0.75rem', maxWidth: 260 }}>{c.message}</div>}</td>
                      <td className="mono" style={{ fontSize: '0.8rem' }}>
                        {c.installedVersion || c.pinnedVersion || '—'}
                        {c.installedVersion && c.installedVersion !== c.pinnedVersion && <div className="muted">pinned {c.pinnedVersion}</div>}
                      </td>
                      <td style={{ fontSize: '0.8rem' }}>
                        {test ? <>
                          <Badge value={test.ok ? 'Healthy' : 'Degraded'} label={test.ok ? 'Passed' : 'Failed'} quiet />
                          <div className="muted">{fmtTime(test.at)}</div>
                          <ul style={{ margin: '4px 0 0', paddingLeft: 16 }}>
                            {test.probes.map(p => <li key={p.name}>{p.ok ? '✓' : '✗'} {p.name} <span className="muted">({p.detail}, {p.millis} ms)</span></li>)}
                          </ul>
                        </> : <span className="muted">—</span>}
                      </td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        {(c.kind === 'CORE' || installed) && c.status !== 'NOT_FOR_THIS_CLOUD' &&
                          <button className="btn" disabled={!!b} onClick={() => act(c, 'test')}><Icon name="play" size={13} />{b === 'test' ? 'Testing…' : 'Test'}</button>}
                        {c.kind === 'MANAGED' && c.status === 'NOT_INSTALLED' &&
                          <button className="btn primary" disabled={!!b} onClick={() => act(c, 'enable', `Install ${c.name} ${c.pinnedVersion} through Argo CD?`)}>
                            <Icon name="plus" size={13} />{b === 'enable' ? 'Installing…' : 'Enable'}</button>}
                        {installed && c.previousVersion && c.installedVersion !== c.previousVersion &&
                          <button className="btn" disabled={!!b} onClick={() => act(c, 'rollback', `Roll ${c.name} back to ${c.previousVersion}?`)}>
                            <Icon name="undo" size={13} />Roll back</button>}
                        {installed && c.installedVersion && c.installedVersion !== c.pinnedVersion &&
                          <button className="btn" disabled={!!b} onClick={() => act(c, 'enable', `Move ${c.name} back to the pinned ${c.pinnedVersion}?`)}>
                            <Icon name="refresh" size={13} />Use {c.pinnedVersion}</button>}
                        {installed &&
                          <button className="btn danger" disabled={!!b} onClick={() => act(c, 'disable', `Disable ${c.name}? This deletes everything it installed (dashboards and stored data included).`)}>
                            <Icon name="trash" size={13} />Disable</button>}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </>
  )
}
