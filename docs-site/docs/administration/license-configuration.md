---
title: 'License configuration'
sidebar_label: 'License configuration'
sidebar_position: 4
description: 'Configure the database, signed-token, or unlimited license provider for a deployment.'
# Diátaxis mode: how-to
---

One property, `ARAN_LICENSING_MODE`, selects which of three providers is
active: `database` (the default), `signed-token`, or `unlimited`. Everything
downstream reads the same entitlements record in every mode — see
[Licensing modes](../deep-dives/licensing-modes.md) for why the seam is
shaped that way.

## Database mode

The default. Limits live in the platform's own `license` table.

```yaml
aran:
  licensing:
    mode: database
```

or `ARAN_LICENSING_MODE=database`.

Provisioning a tenant inserts its licence row in the same transaction. There
is no separate step. Configure what each plan grants:

```yaml
aran:
  licensing:
    plans:
      free:
        max-users: 5
        max-projects: 3
        features: []
      pro:
        max-users: 50
        max-projects: 100
        features: [AUDIT_LOG_EXPORT]
      enterprise:
        max-users: 10000
        max-projects: 10000
        features: [AUDIT_LOG_EXPORT]
```

Every plan in the `LicensePlan` enum needs an entry. A missing one fails at
startup, with a message naming the key to add. Per-tenant overrides on the
`license` row take precedence over these defaults. This is how `ENTERPRISE`
gets negotiated limits without inventing a plan.

`features` accepts only names from the `LicenseFeature` enum. Aran rejects a
misspelling outright, rather than silently granting nothing.

### Byte ceilings — the storage and egress circuit breakers

Two per-plan limits are deliberately absent from every default plan:
`max-storage-bytes` and `max-monthly-egress-bytes`. Absent means no ceiling.
Set one to cap a tenant — this is what stops a runaway materialisation
filling the volume:

```yaml
aran:
  licensing:
    plans:
      pro:
        max-storage-bytes: 107374182400        # 100 GiB
        max-monthly-egress-bytes: 53687091200  # 50 GiB
```

## Signed-token mode

Limits come from a vendor-signed JWS, verified locally against a public key.
Aran does not trust the deployment's own database for entitlement values.

```yaml
aran:
  licensing:
    mode: signed-token
    token:
      public-key-path: /etc/aran/license-public.pem
      value: <the token>
```

or:

```bash
ARAN_LICENSING_MODE=signed-token
ARAN_LICENSE_PUBLIC_KEY_PATH=/etc/aran/license-public.pem
ARAN_LICENSE_TOKEN=eyJhbGciOi…
```

Requirements:

- The public key is a standard PEM file, readable by the application's user.
  The container image declares a `VOLUME /etc/aran` for exactly this — mount
  the PEM there.
- The `aran-adapter-license-token` JAR must be on the classpath. It is in the
  default build, and inert in `database` mode.
- You need no outbound network access. Verification is local, so this works
  air-gapped.

The plan block is unused in this mode. Limits come from the token instead.

## Unlimited mode

The OSS single-node mode, and what `compose.standalone.yml` runs:

```bash
ARAN_LICENSING_MODE=unlimited
```

Every tenant gets uncapped entitlements — no cap on users, projects, storage
or egress, every feature on, nothing to expire. No `license` row is
consulted, and the plan block is ignored entirely.

This mode exists because `database` mode denies a tenant with no licence row
on file, and the standalone bootstrap creates its tenant without one —
issuing licences is a cloud-only concern. A standalone install in `database`
mode would fail on its first project.

## What is enforced, and what is not

**Limits** come from the provider. **Current usage** always comes from the
application database, in every mode. The gate composes the two.

In signed-token mode, that asymmetry does security work. Whoever runs the
deployment controls their database and could edit a limits row, but cannot
forge a signature. Their database is still the only authoritative answer to
how many memberships exist.

## Expiry

When the licence expires, the tenant enters **read-only mode**. Reads keep
working. Every mutating endpoint returns HTTP 402.

That is deliberate, over a hard stop — a commercial dispute should not become
an outage. Plan for it operationally: a 402 on writes, with reads still
working, is a licence problem, not a bug.

In `database` mode, Aran also denies a tenant with **no** licence on file,
rather than treating it as unlimited. If that is not what you want on a
single-node install, run `unlimited` mode.

## Verify

Sign in and open **Organization** from the user menu (your avatar, bottom of
the left rail), or call `GET /api/tenants/{tenantId}/license`. Either shows
the resolved plan and limits; `/api/me` does not report them. Then confirm
the mode does what you expect: in signed-token mode, editing a limit
directly in the database must have no effect.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Startup fails naming `aran.licensing.plans.<x>` | A plan in the enum has no configured limits. Add the block. |
| Every mutating request returns 402, reads work | Expired licence, or none on file. |
| Signed-token mode behaves like no licence | The token failed verification — wrong key, wrong issuer, or malformed. |
| Public key not found | Path is resolved inside the container. Mount the PEM into the image's `/etc/aran` volume. |
| A feature gate stays closed | The feature name is not in the token's or plan's `features`. |

## Related

- [Licensing modes](../deep-dives/licensing-modes.md) — the design.
- [Configuration reference](./configuration-reference.md)
