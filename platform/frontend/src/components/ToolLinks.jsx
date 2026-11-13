import { api } from '../api.js'
import { usePoll } from '../hooks.jsx'

export default function ToolLinks({ projectId }) {
  const { data } = usePoll(() => api(`/api/projects/${projectId}/tools`), 60000, [projectId])
  if (!data || (!Object.keys(data.argocd || {}).length && !data.tekton)) return null
  return <div className="card" style={{ padding: 16, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
    <span>Delivery tools · Log in with AIO</span>
    {Object.entries(data.argocd || {}).map(([env, url]) => <a key={env} className="btn" href={url} target="_blank" rel="noopener noreferrer">Argo CD · {env}</a>)}
    {data.tekton && <a className="btn" href={data.tekton} target="_blank" rel="noopener noreferrer">Tekton Dashboard · operators</a>}
  </div>
}
