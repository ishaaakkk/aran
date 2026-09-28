"""
aran-lake — Aran's data-lake service.

A thin data plane between Aran's services (Java, Python, future operators)
and the underlying object storage (Garage / S3-compatible) + query engine
(DuckDB). The lake owns physical artifacts (snapshots in object storage,
plus a small catalog binding them to opaque dataset ids); Aran owns the
logical layer (wrappers, schemas, integrated graphs).

v0.2 narrows the surface deliberately:

* Generic query against a snapshot — Arrow IPC over HTTP.
* Generic job runner with typed handlers — materialize-as-snapshot,
  remote-source imports.
* Format-flexible uploads: parquet, CSV, NDJSON, Excel.
* Materialize-as-snapshot — write the result of a SQL query against a
  source snapshot as a new (parquet) snapshot. Foundation for Aran's
  wrapper materialization (the "silver zone").
* Presigned-GET access for callers that need raw bytes.
* Per-caller bearer auth — distinguish Aran, FREYJA, and future operators.

See ``docs/architecture.md`` for the design rationale, ``docs/adr/`` for
the recorded design decisions, and ``docs/v0.2-changes.md`` for the
specific changes since v0.1.
"""
# Tracks the platform version, not a lineage of its own: the lake ships in the
# same compose file and the same release as the Aran backend, so "which lake
# works with which Aran" is answered by the number being equal. The 0.1/0.2 in
# docs/v0.2-changes.md refers to the lake's own API contract before that
# unification.
__version__ = "0.1.0"
