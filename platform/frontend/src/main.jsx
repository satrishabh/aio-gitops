import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.jsx'
// Self-hosted fonts (no third-party font CDN). Default: Manrope + DM Mono; Bright: Bricolage + Inter + IBM Plex Mono;
// Dark: Space Grotesk + Inter + JetBrains Mono. Browsers fetch a file only when a theme renders text in it.
import '@fontsource-variable/manrope'
import '@fontsource/dm-mono/latin-400.css'
import '@fontsource/dm-mono/latin-500.css'
import '@fontsource-variable/bricolage-grotesque'
import '@fontsource-variable/inter'
import '@fontsource/ibm-plex-mono/latin-500.css'
import '@fontsource/ibm-plex-mono/latin-600.css'
import '@fontsource-variable/space-grotesk'
import '@fontsource-variable/jetbrains-mono'
import './tokens.css'
import './styles.css'
import './appearance.css'
import './account.css'
import './shell.css'
import './overview.css'
import './metrics.css'
import './polish.css'
import './connectors.css'
import './flow.css'
import './services.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
