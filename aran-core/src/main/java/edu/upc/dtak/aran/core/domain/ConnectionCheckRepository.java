package edu.upc.dtak.aran.core.domain;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Persistence port for the last {@link ConnectionCheck} of each stored repository (V46).
 *
 * <p>One row per repository: a new check replaces the previous one. Deleting the repository
 * deletes its check (the table's foreign key cascades).
 */
@NullMarked
public interface ConnectionCheckRepository {

    /** Record {@code check} as its repository's latest, replacing any earlier one. */
    void save(TenantContext tenant, ConnectionCheck check);

    /** Forget the repository's last check — its stored configuration changed. */
    void delete(TenantContext tenant, UUID repositoryId);

    /**
     * The last check of each of {@code repositoryIds} that has one still describing it: a check
     * that began before its repository was last saved ({@link DataRepositoryRepository#save}
     * records when) is left out. That is what keeps a test racing a save — "Test all" while the
     * host is being edited — from recording "Connected" against the new configuration after the
     * save has discarded the old check. {@link ConnectionCheck#testedAt} is when the test read the
     * configuration it dialed.
     */
    List<ConnectionCheck> findAllByRepositoryIds(TenantContext tenant, Collection<UUID> repositoryIds);
}
