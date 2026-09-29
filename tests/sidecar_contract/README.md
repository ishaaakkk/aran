# Sidecar security contract

One test suite, run against every Python service Aran calls over HTTP.

```bash
PYTHONPATH=".:aran-lake/src" aran-lake/.venv/bin/python -m pytest tests/sidecar_contract -q
```

`scripts/validate.sh` runs it in both the full and `--offline` paths. It imports each
service's configuration and bearer check directly, so it needs neither Docker nor a
database.

## Why it exists

`aran-lake` and `freyja` had drifted apart while each looked correct on its own:

- freyja served unauthenticated when its secret was unset, logging a warning.
  It reads `tenant_id` from the request body and asserts it to the lake with a
  privileged secret, so that configuration was a cross-tenant read primitive.
- The lake rejected a lowercase `bearer` scheme that freyja accepted, so the same
  client passed one service and got a 401 from the other.
- The lake accepted a whitespace-only secret; freyja rejected it.

Neither service's own tests caught this, because each was internally consistent, and a
written contract did not catch it either. This suite catches it.

## Adding a sidecar

1. Implement the four rules described on the contributor guide's
   [Python sidecar security contract](../../docs-site/docs/contributing/python-sidecar-security.md)
   page. `freyja/api/security.py` is written to be copied.
2. Add an entry to `registry.py` adapting your service to two operations:
   - `resolve(env)` → `{caller_id: secret}`, or raise. Startup-time.
   - `authenticate(env, authorization)` → the matched caller id, or raise. Request-time.
3. Run the suite. Every test now applies to your service.

The adapters exist because the two services implement the contract differently: the
lake through pydantic settings and a FastAPI `Header` dependency, freyja through a
plain resolver and a `Request`. The contract is about behaviour, not shared code. A
service can implement it any way it likes, as long as it behaves identically at the
boundary.

## What is checked

| Rule | Tests |
| --- | --- |
| 1. Fail closed at startup | No secret, blank secret, empty map, malformed JSON, non-object JSON |
| 2. Refuse dev secrets in prod | Dev sentinel rejected under `prod`, allowed under `dev`, real secret accepted |
| Resolution shape | Plural map, singular promoted, plural wins over singular |
| 3. Constant-time comparison | `compare_digest` is called; the comparison loop has no early `break` |
| 4. Wire behaviour | Correct token names the caller; scheme is case-insensitive; absent/blank/scheme-only/empty/wrong-scheme/wrong-secret all 401; whitespace tolerated; the right caller is named among several; a misconfigured service denies |

Rule 3 is checked structurally, by parsing the module, because a timing assertion in
CI would be flaky. It is parsed rather than grepped because both modules mention "break"
in prose explaining why they do not do it.

## More

The [documentation site](https://dtak-upc.github.io/aran/) has the contributor guide.
[CONTRIBUTING.md](../../CONTRIBUTING.md) explains the validation gate.
