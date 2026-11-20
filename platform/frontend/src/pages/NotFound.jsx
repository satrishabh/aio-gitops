import { Link, useLocation } from 'react-router-dom'
import Icon from '../components/Icon.jsx'
import { Empty } from '../components/ui.jsx'

/** Any address that matches no route. The title is the page's h1, so the focus move to <main> announces it. */
export default function NotFound() {
  const { pathname } = useLocation()
  return (
    <div className="card not-found">
      <Empty icon="compass" heading="h1" title="Page not found"
        action={<>
          <Link className="btn primary" to="/"><Icon name="home" size={15} />Back to overview</Link>
          <Link className="btn" to="/projects"><Icon name="grid" size={15} />Projects</Link>
        </>}>
        <p>This address does not match an AIO page.</p>
        <p className="not-found-path"><code>{pathname}</code></p>
      </Empty>
    </div>
  )
}
