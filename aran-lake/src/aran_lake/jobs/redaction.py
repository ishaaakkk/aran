"""
Credential redaction for job payloads.

One definition, three readers: the runner scrubs the stored row at claim time, the
handler's ``finally`` scrubs it again when the job ends, and ``GET /api/jobs/{id}``
scrubs whatever is still there on its way out. Two copies of the marker or of the
key list would be two things to keep in step — and a mismatch reads as "redacted"
in one place while serving the secret in another (audit IMPORTS-9 / JOBS_EVENTS-5).
"""
from __future__ import annotations

from typing import Any

#: What replaces a credential. Asserted on by the job tests and by Aran's own logs.
REDACTED = "<redacted>"

#: Payload keys whose value is a credential, wherever they appear. Today only the
#: import payload's ``source.connection_string``; matched by name at any depth so a
#: second source kind cannot become a leak by nesting its DSN somewhere else.
SECRET_KEYS = frozenset({"connection_string"})


def redacted(value: Any) -> Any:
    """A copy of ``value`` with every credential-bearing key replaced."""
    if isinstance(value, dict):
        return {
            key: REDACTED if key in SECRET_KEYS else redacted(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [redacted(item) for item in value]
    return value
