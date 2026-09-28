package edu.upc.dtak.aran.core.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Screens a user-supplied host before Aran opens an outbound connection to it.
 *
 * <p>The connection tester takes host, port and URL straight from a request body and connects.
 * Without a screen that is a server-side request forgery primitive: an authenticated user can
 * point it at anything Aran's network can reach and read the outcome from the structured
 * {@code ConnectionTestResult}, turning the endpoint into an internal port scanner.
 *
 * <p><b>Two tiers, because "private" is not the same as "dangerous" here.</b> Aran is deployed
 * next to the databases it federates, so a repository on {@code 10.x} or a compose service on
 * {@code 172.18.x} is the normal case, not an attack — blanket-blocking private ranges would
 * break the product's primary use case. So:
 *
 * <ul>
 *   <li><b>Always blocked</b> — link-local ({@code 169.254/16}, {@code fe80::/10}), wildcard
 *       ({@code 0.0.0.0}, {@code ::}), and multicast. Link-local is the one that matters:
 *       {@code 169.254.169.254} is the cloud instance-metadata endpoint on AWS, GCP and Azure,
 *       and a hit there yields IAM credentials. No data source legitimately lives at any of
 *       these addresses, so blocking them costs nothing.</li>
 *   <li><b>Blocked only when {@code blockPrivateNetworks} is set</b> — loopback, site-local
 *       ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) and IPv6 unique-local
 *       ({@code fc00::/7}). Correct for a hosted multi-tenant deployment where no tenant should
 *       reach Aran's own subnet; wrong for the on-prem install, which is why it is off by
 *       default. Local dev and the e2e stack both probe loopback.</li>
 * </ul>
 *
 * <p><b>What this does not do.</b> The check resolves the name and the connection resolves it
 * again, so a DNS entry that changes between the two calls defeats it (classic rebinding).
 * Closing that needs the connect itself to be pinned to the vetted address, which means a
 * custom socket factory per driver — out of proportion to a connectivity probe whose result is
 * a boolean. The guard raises the cost of the scan; it is not a sandbox.
 */
@NullMarked
public final class OutboundAddressGuard {

    /** Raised when a host is refused. Carries no resolved address — that is itself a probe result. */
    public static class BlockedHostException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BlockedHostException(String message) {
            super(message);
        }
    }

    private final boolean blockPrivateNetworks;

    public OutboundAddressGuard(boolean blockPrivateNetworks) {
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    /**
     * Verify that {@code host} is a permissible outbound target.
     *
     * @throws BlockedHostException if the host is blank, does not resolve, or resolves to a
     *                              blocked range. An unresolvable host is refused rather than
     *                              passed through: letting it reach the driver would report
     *                              DNS failure as a distinct outcome, which is already a
     *                              (weak) oracle, and the driver would fail anyway.
     */
    public void checkHost(@Nullable String host) {
        checkHost(host, false);
    }

    /**
     * As {@link #checkHost} but a host that does NOT resolve is allowed rather than refused — for
     * the registration path (create/update), which must not require the target to be live at save
     * time (DNS may not have propagated, the database may be down, or it may only resolve from the
     * worker's network). A host that DOES resolve to a link-local/metadata (or, when configured,
     * private) address is still refused, so a literal metadata IP or a name resolving to one cannot
     * be stored. The actual connect paths still apply the strict {@link #checkHost}.
     */
    public void checkHostIfResolvable(@Nullable String host) {
        checkHost(host, true);
    }

    private void checkHost(@Nullable String host, boolean allowUnresolved) {
        if (host == null || host.isBlank()) {
            throw new BlockedHostException("No host to connect to");
        }
        String cleaned = stripBrackets(host.trim());

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(cleaned);
        } catch (UnknownHostException e) {
            if (allowUnresolved) {
                return;
            }
            throw new BlockedHostException("Host does not resolve: " + cleaned);
        }

        // Every address the name resolves to must pass. A name resolving to one public and one
        // link-local address is exactly how a metadata-endpoint probe would be smuggled past a
        // check that only inspected the first entry.
        for (InetAddress address : resolved) {
            if (isAlwaysBlocked(address)) {
                throw new BlockedHostException(
                        "Host '" + cleaned + "' resolves to a link-local, wildcard or multicast "
                                + "address, which Aran never connects to");
            }
            if (blockPrivateNetworks && isPrivate(address)) {
                throw new BlockedHostException(
                        "Host '" + cleaned + "' resolves to a private or loopback address, and "
                                + "this deployment restricts outbound connections to public hosts");
            }
        }
    }

    /**
     * Verify the host component of a URI. Also refuses a URI with no host at all, so a caller
     * cannot slip past the screen with a scheme the parser leaves hostless (e.g. {@code file:}).
     */
    public void checkUri(@Nullable String uri) {
        checkUri(uri, false);
    }

    /** As {@link #checkUri} but an unresolvable host is allowed — see {@link #checkHostIfResolvable}. */
    public void checkUriIfResolvable(@Nullable String uri) {
        checkUri(uri, true);
    }

    private void checkUri(@Nullable String uri, boolean allowUnresolved) {
        if (uri == null || uri.isBlank()) {
            throw new BlockedHostException("No endpoint to connect to");
        }
        String host;
        try {
            host = URI.create(uri.trim()).getHost();
        } catch (IllegalArgumentException e) {
            throw new BlockedHostException("Endpoint is not a valid URI");
        }
        if (host == null) {
            throw new BlockedHostException("Endpoint has no host component");
        }
        checkHost(host, allowUnresolved);
    }

    /**
     * Ranges with no legitimate data source behind them, refused regardless of configuration.
     * {@code isLinkLocalAddress} covers {@code 169.254/16} and {@code fe80::/10} — the cloud
     * metadata endpoint sits in the former.
     */
    private static boolean isAlwaysBlocked(InetAddress address) {
        return address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }

    /**
     * Loopback plus RFC1918 / RFC4193. {@code isSiteLocalAddress} is the JDK's RFC1918 test for
     * IPv4; IPv6 unique-local is checked by prefix because the JDK has no predicate for it.
     */
    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** IPv6 literals arrive bracketed from a URI authority; {@code getAllByName} rejects those. */
    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") && host.length() > 2
                ? host.substring(1, host.length() - 1)
                : host;
    }
}
