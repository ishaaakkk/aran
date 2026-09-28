/**
 * Every e2e port is offset from its dev counterpart so the suite can run
 * alongside a live `make all` stack without either clobbering the other.
 */
export const PORTS = {
  vite: 5273,
  backend: 8181,
  backendManagement: 8182,
  keycloak: 8180,
  lake: 8185,
  freyja: 8186,
  fuseki: 3130,
  postgres: 55432,
  sourcePostgres: 55433,
  lakePostgres: 55434,
  garage: 33900,
} as const;

export const BASE_URL = `http://localhost:${PORTS.vite}`;
