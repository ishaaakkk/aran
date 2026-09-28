import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { initKeycloak } from './services/keycloak'
import { initTheme } from './lib/theme'

async function bootstrap() {
  // Before the first render, and before Keycloak: every shell reads the theme off
  // <html>, and only some of them mount the rail that used to set it.
  initTheme()

  try {
    await initKeycloak()
  } catch (err) {
    console.error('Failed to bootstrap Keycloak:', err)
  }

  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void bootstrap()
