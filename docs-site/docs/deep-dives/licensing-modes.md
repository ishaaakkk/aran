---
title: 'Licensing modes'
sidebar_label: 'Licensing modes'
sidebar_position: 6
description: 'The three license providers, what each enforces, and which deployment shapes they suit.'
# Diátaxis mode: explanation
---

Aran enforces per-tenant limits on users, projects, storage and monthly
egress, plus a small set of boolean features. One property selects where the
limits come from.

## One port, three providers

`LicenseProvider` is a port in `aran-core` with a single method: given a
tenant id, return its `LicenseEntitlements`. Three adapters implement it,
each `@ConditionalOnProperty` on `aran.licensing.mode`:

| Mode | Provider | Source of truth |
| --- | --- | --- |
| `database` (default) | `DatabaseLicenseProvider`, in the persistence adapter | Rows in the platform's own `license` table |
| `signed-token` | `SignedTokenLicenseProvider`, in its own adapter module | A JWS token signed by the vendor, verified against a configured public key |
| `unlimited` | `UnlimitedLicensingConfig` | None. Every tenant answers `ENTERPRISE`, `ACTIVE`, effectively unbounded limits, every feature, no expiry |

The standalone (single-node OSS) compose file runs `unlimited`. That mode also
supplies a no-op license admin port, so there is nothing to provision and
nothing to expire, and Aran ignores the `aran.licensing.plans.*` block.

All three providers return the same record. Every gate, the HTTP 402 mapping
and the `/api/me` response read only from that record, so nothing downstream
branches on deployment mode and the code that consumes entitlements does not
know which provider answered.

The signed-token adapter is a separate Gradle module because some deployments
want the token provider absent from the build. The database provider lives
inside the persistence adapter.

## Limits come from the provider; usage comes from the database

The split is asymmetric. **Limits** (how many users the tenant is entitled to)
come from the provider. **Current usage** (how many users it has) always comes
from the application database, in every mode. `LicenseService` composes the
two.

In `signed-token` mode this asymmetry does security work. The operator of the
deployment controls their own database and could edit a limits row, but cannot
forge a signature. Their database is still the only authoritative answer to
how many memberships exist.

## Plans and entitlements are different things

`LicensePlan` is a small fixed enum (`FREE`, `PRO`, `ENTERPRISE`) used as a
human-facing label. The values Aran enforces are the entitlements:
`maxUsers`, `maxProjects`, `maxStorageBytes`, `maxMonthlyEgressBytes`, a
`status`, a set of `LicenseFeature` values and an expiry. The two byte
ceilings are nullable, where null means "no ceiling", and they are the limits a
programmatic user is most likely to hit:

- `maxStorageBytes` gates uploads.
- `maxMonthlyEgressBytes` gates every export, **every JDBC statement** and
  every read of a product's data file through the [Iceberg
  catalog](../programmatic-access/iceberg.md), checked before the read runs
  against the egress of the last 30 days.

The plan-to-limits mapping is configuration, under `aran.licensing.plans.*`:

```yaml
aran:
  licensing:
    mode: database
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
        # The byte ceilings are absent by default = no ceiling. Set them
        # per plan when you want one, e.g. 100 GiB:
        # max-storage-bytes: 107374182400
        # max-monthly-egress-bytes: 107374182400
```

Per-tenant overrides live on the `license` row and take precedence over the
plan defaults. This is how `ENTERPRISE` gets negotiated limits without a new
plan.

The plan enum is fixed and not data-driven. The database stores the plan as a
string, so adding a tier needs one enum value and a column check with no data
migration, but it does need a code change, and that is intended.

`LicenseFeature` is likewise a short enum and not string keys, so every gate
gets a compile-time check and no admin endpoint or token can enable a
misspelled feature name. Today the enum holds one placeholder value,
`AUDIT_LOG_EXPORT`.

## Expiry and suspension are soft

When `expiresAt` is in the past, the tenant enters **read-only mode**. A
`SUSPENDED` status has the same effect: in the cloud it is an explicit admin
action, and self-hosted it is a token claim. Every read endpoint keeps
working and every mutating endpoint rejects with HTTP 402.

Aran soft-stops instead of hard-stopping so that a commercial dispute does not
become an outage. A customer whose licence lapsed can still read their own data
while it is sorted out.

A tenant with no licence on file is denied, not given an implicit unlimited
grant. `findFor` returning empty maps to `LicenseRequiredException`.

## The three 402s

Licensing failures map to HTTP 402 in three distinct problem-detail types,
so a client can act on each:

| Title | When | Extra problem-detail fields |
| --- | --- | --- |
| *License required* | No licence on file for the tenant | None |
| *License expired* | `expiresAt` past, or status `SUSPENDED` | None |
| *License limit exceeded* | A ceiling hit: users, projects, storage or egress | `limit`, `currentUsage`, `maxAllowed` |

Scripts should handle the third. Once a tenant's egress over the last 30 days
is above its ceiling, every export, JDBC statement and Iceberg data-file read
fails this way before running. The check compares usage so far with the
ceiling, because a result's size is unknown until the query runs, so the read
that crosses the ceiling still completes.

## Which mode to run

- `database` (the default) suits a deployment where whoever runs the platform
  also owns the database and the tenants. Provisioning a tenant inserts a
  licence row in the same transaction.
- `signed-token` suits a deployment run by someone other than whoever sets the
  limits. It verifies locally against a public key, needs no outbound
  phone-home and works air-gapped.
- `unlimited` suits the standalone single-node install. Nothing is enforced.

Configuring `database` and `signed-token` is covered in
[License configuration](../administration/license-configuration.md).

## Related

- [Architecture](../overview/architecture.mdx): the hexagonal boundary this rides on.
- [Configuration reference](../administration/configuration-reference.md): every licensing property.
