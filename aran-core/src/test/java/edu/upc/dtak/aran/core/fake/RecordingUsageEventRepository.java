package edu.upc.dtak.aran.core.fake;

import edu.upc.dtak.aran.core.domain.UsageEvent;
import edu.upc.dtak.aran.core.domain.UsageEventRepository;
import edu.upc.dtak.aran.core.domain.UsageKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps every usage event in a list so a test can assert what was metered.
 *
 * <p>The aggregate queries are the one thing this cannot stand in for: {@code usagePerUser} and
 * {@code totals} are native SQL, {@code percentile_cont} included, and re-implementing them here
 * would assert that the fake agrees with itself. They throw, so a test that needs them reaches
 * for the real repository instead of getting a plausible-looking wrong answer.
 */
public final class RecordingUsageEventRepository implements UsageEventRepository {

    private final List<UsageEvent> events = new ArrayList<>();

    @Override
    public UsageEvent save(UsageEvent event) {
        events.add(event);
        return event;
    }

    public List<UsageEvent> events() {
        return List.copyOf(events);
    }

    public List<UsageEvent> ofKind(UsageKind kind) {
        return events.stream().filter(e -> e.kind() == kind).toList();
    }

    @Override
    public List<UsagePerUser> usagePerUser(UUID tenantId, Instant since) {
        throw new UnsupportedOperationException(
                "aggregation is native SQL — test it against the real repository");
    }

    @Override
    public UsageTotals totals(UUID tenantId, Instant since) {
        throw new UnsupportedOperationException(
                "aggregation is native SQL — test it against the real repository");
    }
}
