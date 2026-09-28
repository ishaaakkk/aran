import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PORTS } from '../stack/ports';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const COMPOSE = path.join(REPO, 'docker/e2e/docker-compose.e2e.yml');

// `docker compose config` (text form) expands bind mounts to long form and
// port ranges to structured fields, so substring checks on the rendered text
// cannot distinguish a container port from a published port, nor prove a
// path was never mounted anywhere. We parse the structured `--format json`
// output instead and assert against real field paths.
interface ComposePort {
  target: number;
  published: string;
  protocol: string;
}
interface ComposeVolume {
  type: string;
  source?: string;
  target?: string;
}
interface ComposeService {
  ports?: ComposePort[];
  volumes?: ComposeVolume[];
}
interface ComposeConfig {
  name: string;
  services: Record<string, ComposeService>;
}

function renderCompose(): ComposeConfig {
  const raw = execFileSync(
    'docker',
    ['compose', '-p', 'aran-e2e', '-f', COMPOSE, 'config', '--format', 'json'],
    { encoding: 'utf8' },
  );
  return JSON.parse(raw) as ComposeConfig;
}

// service name -> expected PUBLISHED (host) port, taken from the same
// constants the Playwright stack itself uses, not re-typed literals.
const EXPECTED_PUBLISHED_PORTS: Record<string, number> = {
  postgres: PORTS.postgres,
  'source-postgres': PORTS.sourcePostgres,
  keycloak: PORTS.keycloak,
  fuseki: PORTS.fuseki,
  garage: PORTS.garage,
  'lake-postgres': PORTS.lakePostgres,
};

test('the compose file is valid and publishes every expected port', () => {
  const rendered = renderCompose();

  expect(rendered.name).toBe('aran-e2e');

  for (const [service, expectedPort] of Object.entries(EXPECTED_PUBLISHED_PORTS)) {
    const ports = rendered.services[service]?.ports ?? [];
    expect(ports, `${service} should publish exactly one port`).toHaveLength(1);
    expect(Number(ports[0].published), `${service} published port`).toBe(expectedPort);
  }
});

test('the e2e keycloak mounts only the throwaway e2e realm', () => {
  const rendered = renderCompose();

  const keycloakVolumes = rendered.services.keycloak?.volumes ?? [];
  expect(keycloakVolumes.some((v) => v.source?.endsWith('aran-realm-e2e.json'))).toBe(true);

  // The dev realm directory must never be mounted anywhere in this compose
  // file, under any service. Walk every service's resolved volumes (bind
  // mounts render with absolute `source` paths in the JSON form) so a wrongly
  // added mount is caught regardless of which service it's attached to.
  for (const [service, config] of Object.entries(rendered.services)) {
    for (const volume of config.volumes ?? []) {
      expect(volume.source ?? '', `${service} volume source`).not.toContain('keycloak/import');
      expect(volume.target ?? '', `${service} volume target`).not.toContain('keycloak/import');
    }
  }
});
