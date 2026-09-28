package edu.upc.dtak.aran.core.licensing;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown when an action would push tenant usage past a license limit
 * (e.g. adding a member when {@code currentUsers == maxUsers}).
 *
 * <p>Carries the limit kind and the current/max values so the API exception
 * handler can surface them to the client — a frontend can then prompt "you
 * have reached 10/10 users; upgrade your plan".
 *
 * <p>Maps to HTTP 402.
 */
@NullMarked
public class LicenseLimitExceededException extends LicenseException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String limitName;
    private final long currentUsage;
    private final long limit;

    public LicenseLimitExceededException(String limitName, long currentUsage, long limit) {
        super("license limit '" + limitName + "' exceeded: " + currentUsage + " / " + limit);
        this.limitName = limitName;
        this.currentUsage = currentUsage;
        this.limit = limit;
    }

    public String limitName() {
        return limitName;
    }

    public long currentUsage() {
        return currentUsage;
    }

    public long limit() {
        return limit;
    }
}
