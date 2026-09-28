---
title: 'Python sidecar security contract'
sidebar_label: 'Python sidecar security'
sidebar_position: 6
description: 'The authentication rules every Python service Aran calls over HTTP must follow, and why each one is not optional.'
# Diátaxis mode: explanation
---

Aran calls two Python services over HTTP — `aran-lake` and `freyja` — and
both hold credentials that let them read data across tenant boundaries. Any
future one will too, because that is what being Aran's data plane means.

This page is the contract those services follow. A new sidecar must
implement all four rules before it is merged, not before it is deployed.

:::tip The contract is executable
`tests/sidecar_contract/` runs one suite against **every** registered
sidecar. `scripts/validate.sh` runs it in both the full and `--offline`
paths. Add your service to `registry.py`, and it is held to the same rules
from then on.

This is not belt-and-braces. `aran-lake` and `freyja` had already drifted
apart while each looked correct in isolation — different behaviour for a
lowercase `bearer` scheme, different handling of a whitespace-only secret,
and one of them serving unauthenticated. Neither service's own tests caught
it, and neither did a written contract. The shared suite did.
:::

## Why the bar is here and not lower

A sidecar is not a low-value internal service. Consider what FREYJA actually
is:

- It takes `tenant_id` **from the request body**, not from a verified token.
- It then asserts that tenant to the lake using the **lake's privileged
  shared secret**.

So an unauthenticated FREYJA is not a degraded service. It is a cross-tenant
read primitive listening on a port. Send it any tenant id, and it fetches
that tenant's snapshot data for you. The bearer check is the entire
boundary.

`aran-lake` is in the same position by construction — it is the thing
holding the data.

## The four rules

### 1. Fail closed at startup, not at request time

A service with no secret configured **must refuse to start**.

Warning-and-serving is not an acceptable middle ground. The warning goes to
a log nobody reads, while the port answers everyone who asks — and it fails
in the direction that loses data. A process that will not start gets noticed
within seconds.

```python
# freyja/api/security.py
def resolve_caller_secrets(env: dict[str, str] | None = None) -> dict[str, str]:
    # FREYJA_SHARED_SECRETS (JSON map) or FREYJA_SHARED_SECRET ({"default": …});
    # neither set → raise. There is no unauthenticated mode.
    ...
    raise MisconfiguredError(f"{SECRET_ENV} is not set. …")
```

The FastAPI `lifespan` calls this, so uvicorn's startup fails.

`aran-lake` does the same thing through a pydantic `model_validator`:

```python
@model_validator(mode="after")
def _validate_secret_presence(self) -> Settings:
    if not self.caller_secret_map():
        raise ValueError("no shared secret configured: set ARAN_LAKE_SHARED_SECRETS …")
```

Either mechanism is fine. Refusing to start is the part that matters.

### 2. Refuse development credentials in production

The dev secrets are committed to this repository. They are public strings.
Accepting one in production is the same as accepting no secret. So a
profile flag must reject them:

```python
if profile == "prod" and secret == DEV_DEFAULT_SECRET:
    raise MisconfiguredError("… is still the development default while FREYJA_PROFILE=prod")
```

`aran-lake` implements this as `_validate_prod_safety`. It runs at startup,
never at request time.

### 3. Compare in constant time

`secrets.compare_digest` (or `hmac.compare_digest`), never `==`. A byte-by-byte
comparison leaks the secret to anyone who can measure response times.

### 4. Authenticate on the router, not on each endpoint

```python
router = APIRouter(
    prefix="/operators",
    dependencies=[Depends(require_bearer)],
)
```

An endpoint added a year from now is then protected by default. A
per-endpoint decorator stays protected only as long as everyone remembers.
The failure mode of forgetting is an open endpoint.

Health probes are the deliberate exception. Register them on the **app**,
not the router, so orchestrators can reach them.

## The environment-variable shape

Both services read the same two variables. They differ only in their
service prefix. A new sidecar uses the same shape.

| | Canonical | Legacy single-secret | Profile |
| --- | --- | --- | --- |
| `aran-lake` | `ARAN_LAKE_SHARED_SECRETS` | `ARAN_LAKE_SHARED_SECRET` | `ARAN_LAKE_PROFILE` |
| `freyja` | `FREYJA_SHARED_SECRETS` | `FREYJA_SHARED_SECRET` | `FREYJA_PROFILE` |
| a new one | `<PREFIX>_SHARED_SECRETS` | `<PREFIX>_SHARED_SECRET` | `<PREFIX>_PROFILE` |

The canonical form is a JSON object of `{caller_id: secret}`:

```bash
FREYJA_SHARED_SECRETS='{"aran":"<secret>"}'
```

Resolution precedence, identical in both:

1. `_SHARED_SECRETS` set → parse as a JSON object and use it.
2. Else `_SHARED_SECRET` set → promote to `{"default": secret}`.
3. Else → refuse to start.

**Prefer the map even with one caller.** Aran returns the matched entry's
id to the handler. Structured logs can then record *which* service called,
without trusting a caller-asserted identity header. A second caller later
needs no redesign.

## Checklist for a new sidecar

- [ ] `<PREFIX>_SHARED_SECRETS` and `<PREFIX>_PROFILE`, following the table above
- [ ] Startup raises when no secret is configured, or one is blank
- [ ] Startup raises on a dev sentinel when the profile is `prod`
- [ ] `compare_digest`, over **all** configured secrets, with no early `break`
- [ ] The matched caller id is returned, not just a boolean
- [ ] Auth declared on the router; health endpoint outside it
- [ ] Bound to loopback unless it genuinely must be reachable off-host
- [ ] **Registered in `tests/sidecar_contract/registry.py`** — this is the step that
      makes the rest enforced rather than aspirational
- [ ] The launch path in `Makefile` and `scripts/startup.sh` sets the variables
- [ ] Documented in the [configuration reference](../administration/configuration-reference.md)

Copy `freyja/api/security.py` as the starting point. It is written to be
copied, and its module docstring states the contract.

## Two shell traps that silently defeat all of this

Both were live in this repository. Neither produced an error anyone saw. A
correct auth design is worth nothing if the launch script does not pass the
secret.

**A comment between backslash-continued lines is not a comment.** In a
shell script, the lines join first. Everything from `#` to the end of the
joined line vanishes — including environment assignments that came before
it:

```bash
# BROKEN — the child receives none of these
FOO="a" \
BAR="b" \
# explaining something
BAZ="c" \
  exec some-service
```

In a **Makefile**, the same shape is worse. Make does not strip `@#` as a
silence prefix mid-line. So the shell tries to execute `@#`, and the recipe
dies with `@#: not found`.

Put comments **above** the block. Both bugs existed in the FREYJA launch
path. `make dev` never started the sidecar at all. `scripts/startup.sh`
started it without `ARAN_LAKE_URL` or `ARAN_LAKE_FREYJA_SECRET`.

**Verify the launch path, not just the code.** The cheap check:

```bash
FOO=1 BAR=2 \
  env | grep -E '^(FOO|BAR)='
```

If a variable is missing from that output, it is missing from the service.

## Where the secrets have to match

| Sidecar variable | Must equal |
| --- | --- |
| `FREYJA_SHARED_SECRET` | Aran's `aran.freyja.secret` (`ARAN_FREYJA_SECRET`) |
| `ARAN_LAKE_FREYJA_SECRET` | FREYJA's entry in the lake's `ARAN_LAKE_SHARED_SECRETS` |
| Aran's `aran.lake.secret` | Aran's entry in the lake's `ARAN_LAKE_SHARED_SECRETS` |

The lake takes a **per-caller** map (`{"aran": "…", "freyja": "…"}`) rather
than one shared value. This lets its audit log tell callers apart. Prefer
that shape in a new sidecar with more than one caller. The single-secret
form is legacy.

## Related

- [Configuration reference](../administration/configuration-reference.md)
- [Multi-tenancy](../deep-dives/multi-tenancy.md) — the boundary these services can cross
