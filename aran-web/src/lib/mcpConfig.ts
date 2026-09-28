/**
 * Building the MCP client configuration the "Use with agents" panel hands over.
 *
 * Pure, and in `lib/` rather than beside the component, for two reasons: the suite runs
 * in node with no DOM, and a component file that also exports helpers breaks Fast
 * Refresh (`react-refresh/only-export-components`).
 */

export interface LocationLike {
  protocol: string;
  hostname: string;
  port: string;
  origin: string;
}

/** The Vite dev server's port, per `vite.config.ts`. */
export const VITE_DEV_PORT = '5173';

/** Where the backend actually listens, per `application.yml`. */
export const BACKEND_PORT = '8081';

/** Placeholder, never a real secret — this panel has no access to one. */
export const TOKEN_PLACEHOLDER = 'aran_pat_…';

/** Stand-in for the sidecar's absolute path, which the browser cannot know. */
export const COMMAND_PLACEHOLDER = '/absolute/path/to/aran/aran-mcp/.venv/bin/aran-mcp';

/**
 * Which URL the sidecar should call.
 *
 * In a packaged deployment the SPA is served by the backend, so the page's own origin is
 * right. Under `npm run dev` it is not: Vite serves the page on 5173 and proxies `/api` to
 * 8081, so the origin the browser sees is a dev server the sidecar cannot use. Guessing
 * silently would produce a config that fails with a connection error blaming the wrong
 * component, so the dev case is detected and the caller is expected to say so.
 */
export function suggestBackendUrl(loc: LocationLike): { url: string; isDevGuess: boolean } {
  if (loc.port === VITE_DEV_PORT) {
    return { url: `${loc.protocol}//${loc.hostname}:${BACKEND_PORT}`, isDevGuess: true };
  }
  return { url: loc.origin, isDevGuess: false };
}

/**
 * The block a user pastes into their MCP client.
 *
 * `ARAN_PROJECT` is included because the panel is always opened from inside one project;
 * it narrows what the agent sees to that project's products. It limits noise, not access
 * — the token's tenant is the boundary, and Aran enforces it.
 */
export function buildMcpClientConfig(aranUrl: string, projectId: string): string {
  return JSON.stringify(
    {
      mcpServers: {
        aran: {
          command: COMMAND_PLACEHOLDER,
          env: {
            ARAN_URL: aranUrl,
            ARAN_TOKEN: TOKEN_PLACEHOLDER,
            ARAN_PROJECT: projectId,
          },
        },
      },
    },
    null,
    2,
  );
}
