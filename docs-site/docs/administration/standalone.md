---
title: 'Run Aran standalone'
sidebar_label: 'Standalone'
sidebar_position: 0
description: 'The supported single-node topology: one compose file, one command, one origin.'
# Diátaxis mode: how-to
---

The standalone topology runs the whole platform from one compose file. It is
the supported way to deploy Aran on a single node.

```bash
docker compose -f compose.standalone.yml up --build
```

Then open http://localhost:8080 and sign in. In v0.1.0 that address is the
only one sign-in works from — see
[Reaching it from another machine](#reaching-it-from-another-machine).

`--build` builds the three Aran images from your checkout. Without it,
Compose pulls them from `ghcr.io/dtak-upc` at the version your checkout
declares (`ARAN_VERSION` overrides the tag) and falls back to building
whenever the pull fails.

## What is running

Seven services and one one-shot job, on one Compose network:

| Service | Image | Role |
| --- | --- | --- |
| `postgres` | `postgres:17-alpine` | Both databases — `aran` and `aran_lake`, created by `deploy/standalone/init-db.sh` |
| `keycloak` | `quay.io/keycloak/keycloak:26.2` | Authentication. You should never see it — see [the `/auth` proxy](#the-auth-same-origin-proxy) |
| `fuseki` | `stain/jena-fuseki:5.1.0` | The schema graph, one dataset per tenant |
| `garage` | `dxflrs/garage:v1.0.1` | S3-compatible object storage for snapshots |
| `aran-lake` | `ghcr.io/dtak-upc/aran-lake`, or built from `./aran-lake` | Snapshot service. Publishes no host port |
| `freyja` | `ghcr.io/dtak-upc/aran-freyja`, or built from `./freyja` | Profiling, join discovery and constraint discovery — the evidence behind integration suggestions and the query planner's costs. Publishes no host port |
| `aran-app` | `ghcr.io/dtak-upc/aran`, or built from `./Dockerfile` | The API **and** the UI, one origin |
| `garage-init` | one-shot job | Creates the bucket and access key on first start, then exits |

Only `aran-app` publishes a port, and only on loopback by default:
`"${ARAN_BIND:-127.0.0.1}:8080:8081"`. Nothing else needs to be reachable
from the host — Aran is the only thing that talks to the lake, and here that
is a topology rule, not a convention.

Data lives in four named volumes: `standalone-pg`, `standalone-fuseki`,
`standalone-garage-meta`, `standalone-garage-data`.

The app runs with `SPRING_PROFILES_ACTIVE: standalone` and is built with the
`WITH_UI: "true"` build arg, which bakes the SPA into the image — the
`standalone` profile serves it from `/app/static/`. Without that build arg
the image ships no UI at all; see
[Production build](./production-build.md#build-the-image).

Licensing runs in `unlimited` mode: no caps, no `license` row, nothing to
expire. See [License configuration](./license-configuration.md#unlimited-mode).

FREYJA profiles every upload right after bootstrap, reading snapshots from
the lake as a caller of its own. Its lake secret, `ARAN_LAKE_FREYJA_SECRET`,
defaults to `ARAN_LAKE_SECRET`; set it separately and the lake's audit
columns tell FREYJA's reads apart from the application's, and either secret
can be rotated alone.

## The first-run administrator password

The first boot creates one tenant and one owner, `alice`. The password
depends on how you started the stack:

- **`ARAN_ADMIN_PASSWORD` set** — that is the password.
- **Not set** — a 20-character password is generated and printed **once**,
  at WARN level, to `aran-app`'s log. Retrieve it with:

  ```bash
  docker compose -f compose.standalone.yml logs aran-app | grep -A3 'first-run administrator'
  ```

The password is temporary either way: Keycloak forces a change at first
login. It is not stored anywhere. The generated alphabet contains no
ambiguous characters (no `I`/`l`/`1`, no `O`/`0`) — it is meant to be read
off a terminal and typed once.

If a seeded `alice` already exists — the dev realm import ships one with a
published password — her credential is **rotated** to the generated or
configured one, not kept. A fresh install never carries a password anyone
can read out of the repository.

## Locked out — reset a password

Recovery does not go through Aran's API: the person locked out is the
administrator, and an unauthenticated admin endpoint would be a backdoor.
The CLI drives Keycloak's `kcadm.sh` inside the container instead, using the
bootstrap admin credential:

```bash
./aran admin reset-password <username>
```

It prints a new 20-character temporary password once; the user must change
it at next login. It reads these variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `KC_ADMIN_USER` | `admin` | Keycloak bootstrap admin |
| `KC_ADMIN_PASSWORD` | `admin` | Its password |
| `ARAN_KEYCLOAK_REALM` | `aran` | Realm name |
| `ARAN_KEYCLOAK_SERVICE` | `keycloak` | Compose service running Keycloak |

If the Keycloak service is not running, it fails with
`error: the 'keycloak' service is not running.` and tells you to run
`./aran start`. Called without a username, it prints its usage and exits 2.

There is no self-service reset: the stack has no SMTP, and the realm sets
`resetPasswordAllowed: false` so the login page shows no dead link.

## The `/auth` same-origin proxy

The browser only ever sees `http://localhost:8080/auth/…` — no Keycloak
port, no hostname change. Two variables enable it:

```yaml
ARAN_AUTH_PROXY_ENABLED: "true"
ARAN_AUTH_PROXY_TARGET: http://keycloak:8080
```

`aran-app` then forwards every `/auth/**` request to the fixed upstream —
the request contributes only path and query. Keycloak is served under
`/auth` (`KC_HTTP_RELATIVE_PATH`) with its hostname pinned to
`${ARAN_PUBLIC_URL}/auth`, so every issued token's `iss` claim carries the
proxied URL while the JWK set is fetched over the container network. The
login page uses Aran's own theme; users are created from Aran's admin UI,
so Keycloak stays invisible.

The realm also ships a second, public client, **`aran-cli`**, for scripted
password-grant logins. The SPA client (`aran-web`) has direct access grants
off on purpose, so any script that needs a token — the smoke test included —
authenticates as `aran-cli`:

```bash
curl -s -X POST http://localhost:8080/auth/realms/aran/protocol/openid-connect/token \
  -d client_id=aran-cli -d grant_type=password \
  -d username=alice -d "password=$ARAN_ADMIN_PASSWORD"
```

## Verify the install

Run the smoke test from the repository root:

```bash
ARAN_ADMIN_PASSWORD=<the password> ./scripts/standalone-smoke.sh
```

It signs in as the seeded owner, mints an API token, confirms the owner is a
real member of a real tenant, creates a project and a repository, uploads
the demo dataset, waits for bootstrap, waits for FREYJA to profile the
upload, and runs a query that must fail with a 4xx saying no schema is
active yet. The profiling check reads the `column_profile` table through
`docker compose exec`, so run the script on the host running the stack.
Each step prints a `▸` line; the final line on success is exactly:

```
STANDALONE SMOKE: OK
```

A failure prints `FAILED: <reason>` to stderr and exits non-zero. With
`ARAN_ADMIN_PASSWORD` unset it exits 2 and prints the log-grep recipe above.

Its inputs, all overridable:

| Variable | Default |
| --- | --- |
| `ARAN_BASE_URL` | `http://localhost:8080` |
| `ARAN_KEYCLOAK_BASE` | `http://localhost:8080/auth` |
| `ARAN_ADMIN_USERNAME` | `alice` |
| `ARAN_ADMIN_PASSWORD` | none — required |
| `ARAN_DEMO_CSV` | `demo/seminar/acm_papers.csv` |
| `ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET` | `dev-backend-secret-change-in-prod` |
| `ARAN_COMPOSE_FILE` | `compose.standalone.yml` |

## Override the development defaults

The topology ships development defaults so that one command boots on a
laptop. The `standalone` profile sets
`aran.security.allow-insecure-defaults: true`, so the
[startup guard](./configuration-reference.md#guarded-settings--the-startup-refusal)
warns instead of refusing — the log names every defaulted credential.
Override all of these on any host other people can log in to:

| Variable | Shipped default |
| --- | --- |
| `POSTGRES_PASSWORD` | `aran` |
| `KC_ADMIN_PASSWORD` | `admin` |
| `ARAN_LAKE_SECRET` | `standalone-secret-change-me` |
| `ARAN_LAKE_FREYJA_SECRET` | the value of `ARAN_LAKE_SECRET` |
| `ARAN_FREYJA_SECRET` | `dev-freyja-secret-change-in-prod` |
| `ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET` | `dev-backend-secret-change-in-prod` |
| `ARAN_ENCRYPTION_KEY` | `standalone-encryption-key-change-me` |
| `ARAN_FUSEKI_PASSWORD` | `admin` |

The two lake secrets are spliced into a JSON map for the lake, so use hex
or base64url values (`openssl rand -hex 32`) — no quotes or backslashes.
Set `ARAN_ENCRYPTION_KEY` **before** registering any repository — rotating
it later makes already-stored repository credentials undecryptable.

## Reaching it from another machine

**v0.1.0 supports loopback only.** Sign-in works from `http://localhost:8080`
and from nowhere else, so to use a stack running on a server, tunnel to it
and browse to localhost on your own machine:

```bash
ssh -L 8080:localhost:8080 <server>
```

Publishing it beyond loopback (`ARAN_BIND=0.0.0.0`, a public
`ARAN_PUBLIC_URL`) is **unsupported until the hardening phase**, and does
not work today: the login library needs a browser secure context (HTTPS, or
`localhost`) for its Web Crypto calls, and the realm accepts only
`localhost` redirect URIs. TLS termination, `ARAN_AUTH_REQUIRE_TLS`, the
public hostname and the realm's redirect URIs arrive together in that phase.

## Limits

- **One tenant, one user to start.** Add colleagues from **Administration →
  Users** — *Administration* is in the user menu (your avatar, bottom of the
  left rail) and only an owner sees it. A user belongs to exactly one tenant.
- **Not multi-node.** Single instance; the JDBC endpoint keeps per-statement
  cursors in memory.
- **No email**, so no self-service password reset — use
  `./aran admin reset-password`.
- **Loopback only** in v0.1.0 — see
  [Reaching it from another machine](#reaching-it-from-another-machine).

## Related

- [Configuration reference](./configuration-reference.md)
- [License configuration](./license-configuration.md)
- [Production build](./production-build.md)
- [Troubleshooting](./troubleshooting.md)
