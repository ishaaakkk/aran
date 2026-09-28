-- V43: "one ACTIVE schema version per tenant" becomes a database fact (D5 part A).
--
-- V9 constrained only (tenant_id, version_number), so nothing forbade two ACTIVE rows for one
-- tenant. That is not a read that degrades: TenantGraphVersionJpaRepository.findByTenantIdAndStatus
-- returns an Optional, so the second row makes EVERY currentVersion() for that tenant throw
-- IncorrectResultSizeDataAccessException — the schema, every query over it, every product and every
-- cube fail at once, for the whole tenant, until someone edits the table by hand.
--
-- The row is reachable. FusekiGraphStoreAdapter.promote is the only writer of an ACTIVE version and
-- it demotes the outgoing ACTIVE and writes the incoming one as two independent saves, issued with
-- no transaction around them: two promotes running at once both read the same outgoing ACTIVE, both
-- demote it, and both leave their own ACTIVE behind. (Promote's other failure mode — zero ACTIVE
-- rows when the process dies between the two saves — is a different defect and is NOT what this
-- index addresses; it needs the transaction that D5 sequences after the alias deletion.)

-- ---------------------------------------------------------------------------------------------
-- Repair first: CREATE UNIQUE INDEX fails outright against a tenant that already holds two, and a
-- migration that cannot apply is a worse outage than the one it prevents.
--
-- The highest version_number stays ACTIVE. Version numbers are minted monotonically per tenant
-- (replaceDraft takes max + 1) and every path to ACTIVE goes through promote — a rollback included,
-- since SchemaVersionService.rollback re-materialises the old shape as a NEW version rather than
-- mutating a status — so the highest-numbered ACTIVE row is the one promoted last, and therefore
-- the one whose triples urn:aran:schema actually holds. Demoting the others makes this index agree
-- with the graph rather than picking a winner arbitrarily.
--
-- The alternative was "keep the most recently promoted" (promoted_at DESC). Rejected: promoted_at
-- is nullable, so a row can be ACTIVE with no value at all; it is wall-clock, so two hosts can tie
-- or invert it; and it is unconstrained, whereas version_number is UNIQUE per tenant since V9, which
-- makes MAX() here provably single-valued. A third option — refuse to migrate and make an operator
-- choose — was rejected because the state it refuses on is one the application cannot serve either.
UPDATE tenant_graph_version v
   SET status = 'HISTORICAL'
 WHERE v.status = 'ACTIVE'
   AND v.version_number < (SELECT MAX(w.version_number)
                             FROM tenant_graph_version w
                            WHERE w.tenant_id = v.tenant_id
                              AND w.status = 'ACTIVE');

-- ---------------------------------------------------------------------------------------------
-- A PARTIAL unique index, which is why it lives only here. A WHERE clause has no JPA
-- @UniqueConstraint equivalent, so TenantGraphVersionEntity cannot declare it — and it would not
-- help if it could: ddl-auto: validate compares tables and columns only, never constraints or
-- indexes. What proves this is enforced is the Postgres-backed IT
-- (OneActiveGraphVersionPerTenantIT).
--
-- Promote's existing statement order is already compatible: it demotes the outgoing ACTIVE before
-- writing the incoming one, so a sequential promote never holds two at once. What changes is the
-- concurrent case — the losing promote now fails with a constraint violation instead of silently
-- corrupting the tenant.
CREATE UNIQUE INDEX uq_tenant_graph_version_one_active
    ON tenant_graph_version (tenant_id)
 WHERE status = 'ACTIVE';
