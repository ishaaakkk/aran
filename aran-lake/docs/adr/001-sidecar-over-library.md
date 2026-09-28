# ADR 001 — Lake as sidecar service, not embedded library

**Status:** Accepted
**Date:** 2026-05-23

## Context

Aran is a JVM application (Spring Boot 4, Java 25). The lake's natural
implementation language is Python — DuckDB's first-class bindings, the
mature pyarrow ecosystem, and the FREYJA codebase all live there. We
had to decide whether to:

- **(a)** embed DuckDB in the JVM via its JDBC driver and write the lake
  in Java, keeping everything in one process;
- **(b)** call out from Java to a separate Python service that owns the
  data plane.

## Decision

The lake is a **separate containerized service** speaking HTTP+JSON for
the control plane and Arrow IPC (over HTTP) for query results. Aran
talks to it the same way any other operator would.

## Rationale

1. **The lake is a polyglot resource.** Aran's Java services, FREYJA's
   Python operators, and future operators in other languages all need
   access to snapshots and profiles. A library approach would force
   every consumer into the JVM, defeating the point.

2. **DuckDB-Python is materially richer than DuckDB-JDBC.** The
   extension ecosystem (`httpfs`, postgres_scanner, the secrets API)
   has more polish on the Python side. The JDBC driver is competent
   but lags. For a lake whose value is its query surface, this matters.

3. **FREYJA stays publishable as a standalone library.** The lake
   imports FREYJA; FREYJA doesn't import the lake. The research artifact
   keeps its independence; the production deployment is the lake plus
   the library plus the catalog.

4. **Independent deployment lifecycle.** Aran's Spring monolith evolves
   slowly (compile, test, redeploy). The lake will iterate quickly in
   v0.x — DuckDB upgrades, profile-job tuning, new import sources.
   Coupling them at the binary level slows both down.

5. **Future scalability is a deployment change, not a refactor.** The
   stateless lake container can become N read replicas behind a load
   balancer with no code change. Dedicated worker containers for jobs
   are similarly free. An embedded library has no equivalent story.

## Consequences

- An additional container and process to operate. Acceptable cost.
- HTTP serialization overhead for every catalog read. Mitigated by
  client-side caching for hot metadata.
- Two languages in the operability surface. Acceptable given the team
  already operates Python (FREYJA).

## Alternatives considered

- **DuckDB-JDBC + Java lake.** Rejected for the polyglot and DuckDB-Python
  reasons above.
- **Embed Python in the JVM via GraalPy or JEP.** Rejected as fragile and
  not buying anything over a clean process boundary.
- **gRPC + protobuf instead of HTTP+JSON.** Deferred. Worth it when query
  volume justifies the dependency; not in v0.1.
