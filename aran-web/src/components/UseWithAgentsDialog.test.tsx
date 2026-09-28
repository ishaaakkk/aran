import { describe, it, expect, afterEach } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { UseWithAgentsDialog } from './UseWithAgentsDialog'
import { buildMcpClientConfig, suggestBackendUrl } from '../lib/mcpConfig'
import type { DataProduct } from '../services/types'

/**
 * The connect panel that hands a user an MCP client configuration.
 *
 * The interesting bug here is not layout, it is emitting a config that does not work —
 * most plausibly a wrong `ARAN_URL`, because the page's own origin is right in a packaged
 * deployment and wrong under the Vite dev server, which proxies /api to another port. A
 * user pasting that gets a connection error pointing at the wrong thing.
 *
 * `suggestBackendUrl` is pure so that case can be asserted without a DOM; the suite runs
 * in node with `renderToStaticMarkup`, and there is no jsdom in this project.
 */

const PROJECT_ID = '11111111-1111-1111-1111-111111111111'

function product(overrides: Partial<DataProduct> = {}): DataProduct {
  return {
    id: 'prod-1',
    projectId: PROJECT_ID,
    name: 'papers',
    description: 'Integrated publications',
    oql: 'FIND Paper.title',
    schemaVersionId: 'sv-1',
    status: 'READY',
    snapshotId: 'snap-1',
    rowCount: 42,
    materializedAt: '2026-08-01T10:00:00Z',
    lastError: null,
    createdBy: null,
    createdAt: '2026-07-01T09:00:00Z',
    ...overrides,
  }
}

/** The component reads `window.location`; the suite has no DOM, so supply one. */
function withLocation(loc: Partial<Location>, fn: () => void) {
  const original = (globalThis as { window?: unknown }).window
  ;(globalThis as { window?: unknown }).window = { location: loc }
  try {
    fn()
  } finally {
    if (original === undefined) delete (globalThis as { window?: unknown }).window
    else (globalThis as { window?: unknown }).window = original
  }
}

const PROD_LOCATION = {
  protocol: 'https:',
  hostname: 'aran.example.org',
  port: '',
  origin: 'https://aran.example.org',
}

const DEV_LOCATION = {
  protocol: 'http:',
  hostname: 'localhost',
  port: '5173',
  origin: 'http://localhost:5173',
}

afterEach(() => {
  delete (globalThis as { window?: unknown }).window
})

describe('suggestBackendUrl', () => {
  it('uses the page origin when the SPA is served by the backend', () => {
    expect(suggestBackendUrl(PROD_LOCATION)).toEqual({
      url: 'https://aran.example.org',
      isDevGuess: false,
    })
  })

  it('points past the Vite dev server, which the sidecar cannot use', () => {
    // :5173 serves the page and proxies /api to :8081. A config naming :5173 would
    // fail at connect time with an error blaming the wrong component.
    expect(suggestBackendUrl(DEV_LOCATION)).toEqual({
      url: 'http://localhost:8081',
      isDevGuess: true,
    })
  })

  it('keeps a non-default port that is not the dev server', () => {
    expect(
      suggestBackendUrl({
        protocol: 'http:',
        hostname: 'box.internal',
        port: '9000',
        origin: 'http://box.internal:9000',
      }),
    ).toEqual({ url: 'http://box.internal:9000', isDevGuess: false })
  })
})

describe('buildMcpClientConfig', () => {
  it('is valid JSON naming the URL, the project and no real secret', () => {
    const parsed = JSON.parse(buildMcpClientConfig('http://localhost:8081', PROJECT_ID))
    expect(parsed.mcpServers.aran.env.ARAN_URL).toBe('http://localhost:8081')
    expect(parsed.mcpServers.aran.env.ARAN_PROJECT).toBe(PROJECT_ID)
    expect(parsed.mcpServers.aran.env.ARAN_TOKEN).not.toMatch(/aran_pat_[A-Za-z0-9]{8,}/)
    expect(parsed.mcpServers.aran.command).toContain('aran-mcp')
  })
})

describe('UseWithAgentsDialog', () => {
  it('emits a config carrying the backend URL and the project', () => {
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).toContain('mcpServers')
      expect(html).toContain('https://aran.example.org')
      expect(html).toContain(PROJECT_ID)
      expect(html).toContain('aran-mcp')
    })
  })

  it('never puts a real token in the pasteable config', () => {
    // The placeholder must stay a placeholder: this dialog has no access to a secret
    // and must not look like it is handing one over.
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).toContain('aran_pat_')
      expect(html).not.toMatch(/aran_pat_[A-Za-z0-9]{8,}/)
    })
  })

  it('warns when the page is the dev server, and not otherwise', () => {
    withLocation(DEV_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).toContain('use-with-agents-dev-note')
    })
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('use-with-agents-dev-note')
    })
  })

  it('tells a VIRTUAL product the truth: ready now, nothing to refresh (V37)', () => {
    // snapshotId is null for VIRTUAL by design, forever. The old snapshot-keyed warning
    // told these users to refresh a button the row deliberately hides, while the real
    // sidecar reads a virtual product fine (V37, watched live 2026-08-27).
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog
          product={product({ accessMode: 'VIRTUAL', snapshotId: null, materializedAt: null })}
          projectId={PROJECT_ID}
          onClose={() => {}}
        />,
      )
      expect(html).toContain('use-with-agents-virtual-live')
      expect(html).not.toContain('use-with-agents-not-materialized')
      // The config a user needs is still offered.
      expect(html).toContain('mcpServers')
    })
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('use-with-agents-virtual-live')
    })
  })

  it('states the scoped privacy truth, never "never leaves your machine" (J6-F3)', () => {
    // The sidecar runs locally, but the rows it returns enter the agent's context, and a cloud
    // client sends that context to its model provider. The pasted READ token can read everything
    // its owner can, not only this product. Saying less than that is the claim that made an
    // engineer connect governed health data to a hosted model.
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('never leaves your machine')
      expect(html).toContain('use-with-agents-privacy')
      expect(html).toContain('model provider')
      expect(html).toContain('everything you can read')
    })
  })

  it('names the real install and token paths (J6-F10, UI-OD10)', () => {
    // There is no "key icon in the left rail", and a standalone install has no repository root:
    // the token lives in the avatar menu, and the sidecar installs from a source checkout until
    // wheels are published.
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('key icon')
      expect(html).toContain('API Tokens')
      expect(html).toContain('source checkout')
      expect(html).not.toContain('from the repository root')
    })
  })

  it('tells a VIRTUAL product what it gives up for agents, not "live" (J6-F2)', () => {
    // A virtual product recomputes from whatever the active schema points at — a month-old
    // upload included — so "serves live" overclaims (V37).
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog
          product={product({ accessMode: 'VIRTUAL', snapshotId: null, materializedAt: null })}
          projectId={PROJECT_ID}
          onClose={() => {}}
        />,
      )
      expect(html).not.toContain('serves live')
      expect(html).toContain('recomputed on every call')
      // J6-F11 made the VIRTUAL read honour `limit`; the old '1,000 rows, limit ignored' claim is retired.
      expect(html).not.toContain('ignore the agent')
    })
  })

  it('keys the warning off status, as the sidecar does: a FAILED or MATERIALIZING product with an old snapshot is not offered (J6-F11)', () => {
    // aran_mcp/_products.py READABLE_STATUSES = {READY, STALE}. A failed refresh keeps its
    // snapshotId, so the old snapshot-keyed banner stayed silent while an agent answered
    // "no readable data product".
    for (const status of ['FAILED', 'MATERIALIZING'] as const) {
      withLocation(PROD_LOCATION, () => {
        const html = renderToStaticMarkup(
          <UseWithAgentsDialog product={product({ status })} projectId={PROJECT_ID} onClose={() => {}} />,
        )
        expect(html, status).toContain('use-with-agents-not-materialized')
        expect(html, status).toContain(`data-status="${status}"`)
      })
    }
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product({ status: 'STALE' })} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('use-with-agents-not-materialized')
    })
  })

  it('says how many rows one agent read returns (J6-F11)', () => {
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).toContain('use-with-agents-row-cap')
      expect(html).toMatch(/at most 500 rows/)
    })
  })

  it('says so when the product has no snapshot an agent could read', () => {
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog
          product={product({ snapshotId: null, status: 'DRAFT', materializedAt: null })}
          projectId={PROJECT_ID}
          onClose={() => {}}
        />,
      )
      expect(html).toContain('use-with-agents-not-materialized')
    })
    withLocation(PROD_LOCATION, () => {
      const html = renderToStaticMarkup(
        <UseWithAgentsDialog product={product()} projectId={PROJECT_ID} onClose={() => {}} />,
      )
      expect(html).not.toContain('use-with-agents-not-materialized')
    })
  })
})
