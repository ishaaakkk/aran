# aran-web

The Aran web client: a React 19 and TypeScript single-page app built with Vite.
It is the only frontend. It talks to the Aran backend, and to Keycloak to sign in.

## Run

```bash
npm install
npm run dev        # http://localhost:5173, needs the backend and Keycloak (make all from the repo root)
npm run dev:demo   # read-only tour over canned data. Sign in as demo / demo
```

`npm run dev` proxies `/api` to the backend on `http://localhost:8081` (override with
`VITE_API_TARGET`) and signs in through Keycloak on `http://localhost:8080`. Demo mode
needs no Aran backend: it answers requests from an in-browser scenario and refuses
writes. It still signs in through the development Keycloak, so start that first
(`docker compose up -d keycloak` from the repository root).

## Scripts

| Command | What it does |
| --- | --- |
| `npm run build` | Type-check and build for production. |
| `npm run lint` | ESLint. |
| `npm test` | Unit tests (Vitest). |
| `npm run test:e2e` | Browser end-to-end suite (Playwright). See [`e2e/README.md`](e2e/README.md). |
| `npm run generate:api` | Write `src/services/openapi.d.ts` from `http://localhost:8080/v3/api-docs`, for cross-checking a contract. Do not commit the file. |

## API types

`src/services/types.ts` is hand-written and is the only source for wire shapes.
It mirrors the backend DTOs. No generated `openapi.d.ts` is checked in, because a
committed copy went stale and contradicted the backend without anything noticing.
`npm run generate:api` reads `/v3/api-docs` from a running backend, so use it to
compare, not to produce a file to commit. Its URL is port 8080, where the standalone
stack serves the API. Against a development backend, which listens on 8081, run
`npx openapi-typescript http://localhost:8081/v3/api-docs -o src/services/openapi.d.ts`.

## More

- [Documentation site](https://dtak-upc.github.io/aran/)
- [Contributing](../CONTRIBUTING.md)
