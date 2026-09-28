---
title: 'Licensing modes'
sidebar_label: 'Licensing modes'
sidebar_position: 6
description: 'The three license providers, what each enforces, and which deployment shapes they suit.'
# Diátaxis mode: explanation
---

Aran enforces per-tenant limits on users, projects, storage, and monthly
egress, plus a small set of boolean features. Where those limits come from
is a deployment choice. One property selects between three options.

## One port, three providers

`LicenseProvider` is a port in `aran-core` with a single method: given a
tenant id, return its `LicenseEntitlements`. Three adapters implement it,
each `@ConditionalOnProperty` on `aran.licensing.mode`:

| Mode | Provider | Source of truth |
| --- | --- | --- |
| `database` (default) | `DatabaseLicenseProvider`, in the persistence adapter | Rows in the platform's own `license` table |
| `signed-token` | `SignedTokenLicenseProvider`, in its own adapter module | A JWS token signed by the vendor, verified against a configured public key |
| `unlimited` | `UnlimitedLicensingConfig` | None — every tenant answers `ENTERPRISE`, `ACTIVE`, effectively unbounded limits, every feature, no expiry |

`unlimited` is the mode the standalone (single-node OSS) compose file
runs. It also supplies a no-op license admin port, so there is nothing to
provision and nothing to expire. The `aran.licensing.plans.*` block is
ignored entirely in this mode.

All three return the same record. Every gate, the HTTP 402 mapping, and the
`/api/me` response read only from that record. Nothing downstream branches on
deployment mode. Selecting the provider is configuration, so the code that
consumes entitlements does not know which one answered.

That is why the signed-token adapter is a separate Gradle module. The
database provider, in contrast, lives inside the persistence adapter. Some
deployments want the token provider physically absent from the build, and a
separate module is what makes that possible.

## Limits come from the provider; usage comes from the database

The split is not symmetric. **Limits** — how many users is this tenant
entitled to? — come from the provider. **Current usage** — how many users
does it actually have? — always comes from the application database, in
every mode. `LicenseService` composes the two.

In `signed-token` mode that asymmetry is doing security work. The operator of
the deployment controls their own database. They could edit a limits row.
They cannot forge a signature. But their database is still the only
authoritative answer to how many memberships exist. There is no point
pretending otherwise.

## Plans and entitlements are different things

`LicensePlan` is a small fixed enum — `FREE`, `PRO`, `ENTERPRISE`. It is a
human-facing label. The machine-enforced values are the entitlements:
`maxUsers`, `maxProjects`, `maxStorageBytes`, `maxMonthlyEgressBytes`, a
`status`, a set of `LicenseFeature` values, and an expiry. The two byte
ceilings are nullable, meaning "no ceiling" — and they are the limits a
programmatic user is most likely to hit:

- `maxStorageBytes` gates uploads.
- `maxMonthlyEgressBytes` gates every export **and every JDBC statement**,
  checked before the query runs.

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

Per-tenant overrides live on the `license` row. When set, they take
precedence over the plan defaults. This is how `ENTERPRISE` gets negotiated
limits without a new plan.

The plan enum is deliberately fixed, not data-driven. The database stores the
plan as a string, so adding a tier later needs only one enum value and a
column check — no data migration. But it does need a code change. That
is the intent.

`LicenseFeature` is likewise a short enum, not string keys, so every gate
gets a compile-time check. No admin endpoint or token can enable a
misspelled feature name. Today the enum holds a single placeholder value,
`AUDIT_LOG_EXPORT`.

## Expiry and suspension are soft

When `expiresAt` is in the past, the tenant enters **read-only mode**. A
`SUSPENDED` status — an explicit admin action in the cloud, or a token
claim self-hosted — has the same effect. Every read endpoint continues to
work. Every mutating endpoint rejects with HTTP 402.

That is a deliberate choice over hard-stopping. A commercial dispute should
not become an outage. A customer whose licence lapsed should still be able to
read their own data while it is sorted out.

A tenant with no licence on file at all is also a denial, not an implicit
unlimited grant. `findFor` returning empty maps to `LicenseRequiredException`.

## The three 402s

Licensing failures map to HTTP 402 in three distinct problem-detail types,
so a client can act on each:

| Title | When | Extra problem-detail fields |
| --- | --- | --- |
| *License required* | No licence on file for the tenant | — |
| *License expired* | `expiresAt` past, or status `SUSPENDED` | — |
| *License limit exceeded* | A ceiling hit — users, projects, storage, egress | `limit`, `currentUsage`, `maxAllowed` |

The third is the one scripts should handle: an export or JDBC statement
that would breach the monthly egress ceiling fails this way before running.

## Which mode to run

- **`database`** suits a deployment where whoever runs the platform also owns the
  database and the tenants. Provisioning a tenant inserts a licence row in the
  same transaction. This is the default.
- **`signed-token`** suits a deployment run by someone other than whoever sets
  the limits. It verifies locally against a public key, needs no outbound
  phone-home, and works air-gapped.
- **`unlimited`** suits the standalone single-node install, where licensing
  would only be friction. Nothing is enforced.

Configuring either is covered in
[License configuration](../administration/license-configuration.md).

## Related

- [Architecture](../overview/architecture.mdx) — the hexagonal boundary this rides on.
- [Configuration reference](../administration/configuration-reference.md) — every licensing property.
