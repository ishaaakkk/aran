# ADR 004 — No bronze/silver/gold medallion

**Status:** Accepted
**Date:** 2026-05-23

## Context

The medallion architecture (bronze → silver → gold) is the industry-
standard naming convention for ETL pipelines that batch-transform raw
enterprise data into analytical marts. We considered whether the lake's
catalog should adopt this vocabulary.

## Decision

**No.** The lake's catalog vocabulary is **snapshot**, **profile**,
**projection**. Medallion is *neither modeled in the catalog nor exposed
in the API*.

## Rationale

1. **Medallion is for batch ETL into analytical marts.** Aran's value
   proposition is federated semantic integration. Data lives in source
   systems; the lake materializes projections of it when performance
   requires. Medallion's mental model — "data lands in bronze, gets
   transformed in silver, gets queried from gold" — is not how Aran
   works.

2. **It would impose two paradigms on users.** "First build a
   bronze→silver→gold pipeline, then attach an ontology to it" is
   strictly worse than either pure paradigm. The features roadmap's
   data-products concept (Layer 5) is Aran's answer to "queryable
   business-ready artifact" — and it lives above the lake, not as a
   layer inside it.

3. **The capabilities are present, just under different names.**
   - "Bronze" = uploaded snapshot.
   - "Silver" = snapshot + profile (validated, content-hashed,
     statistically characterized).
   - "Gold" = data products at Layer 5, on-demand OQL results
     optionally backed by lake projections.

4. **Medallion warehouses Aran federates against don't go away.**
   A customer with a Databricks medallion warehouse can register the
   gold layer as a data repository in Aran. We sit on top of their
   medallion; we don't replace it. This is a defensible positioning
   we can lean into in marketing.

## Consequences

- No `layer` column on snapshots. No `bronze_snapshot`, `silver_table`,
  `gold_mart` concepts in the API.
- Users coming from Databricks/Snowflake will need to map their
  vocabulary to ours. Acceptable — the documentation should call this
  out explicitly.
- We can revisit if a future market signal (multiple lost deals
  citing medallion as the gap) emerges. None today.

## Alternatives considered

- **Add a `layer` column as a convention-only tag.** Rejected —
  conventions without enforcement create the worst of both worlds.
- **Build a real ETL pipeline framework with medallion stages.**
  Rejected as scope creep into the Databricks/Snowflake market we
  explicitly don't compete in.
