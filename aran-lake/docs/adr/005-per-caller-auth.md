# ADR 005 — Per-caller bearer auth (v0.2)

**Status:** Accepted, 2026-05-24
**Supersedes:** the single-secret model implied by v0.1

## Context

v0.1 had a single shared bearer secret. The lake trusted any caller who
held it; that caller was assumed to be Aran. The threat model assumed
"only Aran talks to the lake."

This stopped working as soon as we sketched the v0.2 roadmap:

* FREYJA needs to query the lake to compute column profiles.
* BOOTSTRAP (in Aran, but conceptually a distinct operator) needs to call
  materialize.
* Future operators (ALIGN-prep, data products) will all need their own access.

With one secret shared by everyone, the lake cannot tell Aran from FREYJA
from a hypothetical attacker who learned the secret. There's no audit
trail of which service did what, no way to scope future per-caller policy,
and no way to rotate one caller's credential without rotating all callers.

## Decision

Replace the single secret with a **map of caller-id → secret**, kept in
`ARAN_LAKE_SHARED_SECRETS` as a JSON object:

```
ARAN_LAKE_SHARED_SECRETS='{"aran":"<secret-a>","freyja":"<secret-b>"}'
```

The bearer middleware checks the presented token against every configured
secret (constant-time across all of them, regardless of which matches)
and returns a `CallerIdentity` containing the matched caller-id. Handlers
that want it can inject `CallerIdentity` via `Depends(require_bearer)`;
the caller-id is recorded on every snapshot row and job row for audit.

The legacy single-secret form (`ARAN_LAKE_SHARED_SECRET`) still works —
it's internally promoted to `{"default": "<secret>"}`. This keeps single-
caller dev deployments simple, and keeps v0.1 installations functional
during upgrade.

## Implementation notes

* **Constant-time comparison.** `hmac.compare_digest` per secret, and we
  iterate through *all* configured secrets even after a match, so total
  work doesn't reveal which secret matched (or whether any matched).
* **Caller-id is server-controlled.** The caller doesn't get to say who
  they are — the secret that matched is what determines the identity.
  An `X-Caller-Id` header would be spoofable.
* **`X-Actor-Id` remains optional.** It captures the human user behind a
  request (when meaningful), not the service caller. Snapshots and jobs
  record both.
* **Tenant assertion unchanged.** `X-Tenant-Id` is still trusted because
  the bearer secret proves the caller is one we trust. Per-tenant access
  control still lives in Aran.

## Considered alternatives

1. **JWT validation against Keycloak.** Most secure; biggest change. The
   lake would parse the user's JWT directly, extract tenant claims, and
   not trust headers at all. Decided against for v0.2 because it deeply
   couples lake auth to Aran's IdP and complicates the "the lake is
   operationally independent of Aran" property. Revisit when we have a
   second IdP customer or stricter zero-trust requirements.

2. **mTLS with per-caller certs.** Strongest mutual auth; requires cert
   distribution and rotation infrastructure we don't have today. The
   middleware lift is small but the operations story is real work.
   Tracked for v0.3 (ADR-TBD).

3. **Single secret + caller-asserted `X-Caller-Id`.** Lightest change.
   Rejected because an `X-Caller-Id` header from a compromised caller is
   not auditable — anyone with the secret can claim to be anyone else.

## Consequences

* Per-caller credential rotation becomes possible.
* Audit logs and database rows now identify the calling service.
* Future per-caller policy (e.g. "FREYJA can't materialize") has a place
  to live without re-engineering auth.
* Operations cost increases slightly: there are now N secrets to manage
  instead of 1. We expect N to stay small (single digits) for the
  foreseeable future.
