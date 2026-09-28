package edu.upc.dtak.aran.core.tenant;

import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Holds the {@link TenantContext} for the current unit of work.
 *
 * <p>Implemented with {@link ScopedValue} (a standard feature as of Java 25)
 * rather than {@link ThreadLocal}. This is a deliberate choice for a rewrite
 * targeting virtual threads:
 * <ul>
 *   <li>scoped values are immutable for the duration of a bound scope, so a
 *       downstream call can never silently mutate the caller's tenant;</li>
 *   <li>they are cheap to inherit across the structured-concurrency forks a
 *       request fan-out will use, with none of the leak risk of
 *       {@code ThreadLocal} on pooled platform threads;</li>
 *   <li>the {@code where(...).run(...)} shape makes the tenant scope explicit
 *       and impossible to "forget to clear".</li>
 * </ul>
 *
 * <p>The web layer binds this once per request (see the application module's
 * tenant resolution filter); plugins never touch it directly — they receive
 * the tenant id explicitly on {@code PluginRequest}.
 */
@NullMarked
public final class TenantContextHolder {

    private static final ScopedValue<TenantContext> CURRENT = ScopedValue.newInstance();

    private TenantContextHolder() {
    }

    /**
     * Run {@code action} with {@code tenant} bound as the current context.
     * The binding is visible to everything called transitively from
     * {@code action} and is automatically torn down when it returns.
     */
    public static void runWith(TenantContext tenant, Runnable action) {
        ScopedValue.where(CURRENT, tenant).run(action);
    }

    /**
     * Call {@code action} with {@code tenant} bound as the current context,
     * returning its result.
     */
    public static <T extends @Nullable Object> T callWith(
            TenantContext tenant, Callable<T> action) throws Exception {
        return ScopedValue.where(CURRENT, tenant).call(action::call);
    }

    /**
     * @return the bound tenant context
     * @throws IllegalStateException if called outside any tenant scope — a
     *         loud failure here is intentional, it means a code path reached
     *         tenant-scoped logic without going through tenant resolution
     */
    public static TenantContext require() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException(
                    "No TenantContext bound — request reached tenant-scoped code "
                            + "without passing through tenant resolution");
        }
        return CURRENT.get();
    }

    /** @return {@code true} if a tenant context is currently bound. */
    public static boolean isBound() {
        return CURRENT.isBound();
    }
}
