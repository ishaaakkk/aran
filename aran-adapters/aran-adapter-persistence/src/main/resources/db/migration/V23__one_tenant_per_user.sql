-- One tenant per user.
--
-- A tenant is an organisation paying for Aran. A person who needs access to two of them — a
-- personal account and a work account — holds two Aran users, exactly as they hold two Google
-- accounts. The multiplicity lives at the human level, never at the user record.
--
-- Why this is a constraint rather than a collapse of tenant_id + role onto app_user: the
-- constraint is the half that actually enforces the product rule, and the risk is asymmetric. If
-- the rule ever relaxes — a researcher genuinely spanning a university and a hospital — dropping
-- a unique constraint is one line, while un-collapsing a table is a data migration. Keeping the
-- join table also leaves `role` a natural home.
--
-- The consequence worth knowing: there is no tenant switcher, in-session or otherwise. Switching
-- accounts means logging in as a different user.

-- Fail loudly rather than silently discarding a membership. A row lost here is a person's access
-- to a tenant, and a migration is the worst possible place to decide which one they keep.
DO $$
DECLARE
    offending_user UUID;
    membership_count INT;
BEGIN
    SELECT user_id, COUNT(*)
      INTO offending_user, membership_count
      FROM membership
     GROUP BY user_id
    HAVING COUNT(*) > 1
     LIMIT 1;

    IF offending_user IS NOT NULL THEN
        RAISE EXCEPTION
            'V23 cannot apply: user % holds % memberships, and Aran now allows exactly one. '
            'Decide which tenant that user keeps and delete the other membership rows by hand, '
            'then re-run.',
            offending_user, membership_count;
    END IF;
END $$;

ALTER TABLE membership DROP CONSTRAINT uq_membership_user_tenant;
ALTER TABLE membership ADD CONSTRAINT uq_membership_user UNIQUE (user_id);

COMMENT ON CONSTRAINT uq_membership_user ON membership IS
    'One tenant per user (V23). A person needing two tenants holds two Aran users.';

-- idx_membership_user_id from V1 is now redundant: the unique constraint above creates its own
-- index on (user_id), and Postgres will use that for the lookup the old index served.
DROP INDEX IF EXISTS idx_membership_user_id;
