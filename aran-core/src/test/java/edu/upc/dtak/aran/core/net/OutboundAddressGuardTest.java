package edu.upc.dtak.aran.core.net;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.upc.dtak.aran.core.net.OutboundAddressGuard.BlockedHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The guard's contract, pinned on IP literals so no test here needs DNS.
 *
 * <p>The two tiers are the point: link-local must be refused in every configuration because
 * that is where the cloud metadata endpoint lives, while loopback and RFC1918 must stay
 * reachable by default because that is where an on-prem deployment's databases actually are.
 */
class OutboundAddressGuardTest {

    private final OutboundAddressGuard permissive = new OutboundAddressGuard(false);
    private final OutboundAddressGuard restricted = new OutboundAddressGuard(true);

    @Nested
    @DisplayName("always blocked, whatever the configuration")
    class AlwaysBlocked {

        @Test
        @DisplayName("the cloud instance-metadata endpoint is refused by both policies")
        void metadataEndpointRefused() {
            // 169.254.169.254 returns IAM credentials on AWS, GCP and Azure. This is the single
            // address the guard exists for.
            assertThatThrownBy(() -> permissive.checkHost("169.254.169.254"))
                    .isInstanceOf(BlockedHostException.class)
                    .hasMessageContaining("link-local");
            assertThatThrownBy(() -> restricted.checkHost("169.254.169.254"))
                    .isInstanceOf(BlockedHostException.class);
        }

        @Test
        @DisplayName("IPv6 link-local is refused too, not just the IPv4 form")
        void ipv6LinkLocalRefused() {
            assertThatThrownBy(() -> permissive.checkHost("fe80::1"))
                    .isInstanceOf(BlockedHostException.class);
        }

        @Test
        @DisplayName("the wildcard address is refused")
        void wildcardRefused() {
            assertThatThrownBy(() -> permissive.checkHost("0.0.0.0"))
                    .isInstanceOf(BlockedHostException.class);
        }

        @Test
        @DisplayName("a multicast address is refused")
        void multicastRefused() {
            assertThatThrownBy(() -> permissive.checkHost("224.0.0.1"))
                    .isInstanceOf(BlockedHostException.class);
        }
    }

    @Nested
    @DisplayName("private ranges follow the configuration")
    class PrivateRanges {

        @Test
        @DisplayName("loopback is allowed by default — local dev and the e2e stack probe it")
        void loopbackAllowedByDefault() {
            assertThatCode(() -> permissive.checkHost("127.0.0.1")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("RFC1918 is allowed by default — this is where on-prem databases live")
        void rfc1918AllowedByDefault() {
            assertThatCode(() -> permissive.checkHost("10.1.2.3")).doesNotThrowAnyException();
            assertThatCode(() -> permissive.checkHost("192.168.1.10")).doesNotThrowAnyException();
            // Compose networks land here, which is what the e2e stack uses.
            assertThatCode(() -> permissive.checkHost("172.18.0.5")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the restricted policy refuses loopback and RFC1918")
        void restrictedRefusesPrivate() {
            assertThatThrownBy(() -> restricted.checkHost("127.0.0.1"))
                    .isInstanceOf(BlockedHostException.class)
                    .hasMessageContaining("private or loopback");
            assertThatThrownBy(() -> restricted.checkHost("10.1.2.3"))
                    .isInstanceOf(BlockedHostException.class);
        }

        @Test
        @DisplayName("the restricted policy refuses IPv6 unique-local (fc00::/7)")
        void restrictedRefusesUniqueLocal() {
            // The JDK has no predicate for fc00::/7, so this is checked by prefix — both halves
            // of the /7 must be caught.
            assertThatThrownBy(() -> restricted.checkHost("fc00::1"))
                    .isInstanceOf(BlockedHostException.class);
            assertThatThrownBy(() -> restricted.checkHost("fd12:3456::1"))
                    .isInstanceOf(BlockedHostException.class);
        }
    }

    @Nested
    @DisplayName("input handling")
    class InputHandling {

        @Test
        @DisplayName("a blank or absent host is refused rather than passed to the driver")
        void blankRefused() {
            assertThatThrownBy(() -> permissive.checkHost(null)).isInstanceOf(BlockedHostException.class);
            assertThatThrownBy(() -> permissive.checkHost("   ")).isInstanceOf(BlockedHostException.class);
        }

        @Test
        @DisplayName("a bracketed IPv6 literal from a URI authority is unwrapped before resolving")
        void bracketedIpv6Unwrapped() {
            assertThatThrownBy(() -> permissive.checkHost("[fe80::1]"))
                    .isInstanceOf(BlockedHostException.class)
                    .hasMessageContaining("link-local");
        }

        @Test
        @DisplayName("checkUri screens the URI's host, metadata endpoint included")
        void checkUriScreensHost() {
            assertThatThrownBy(() -> permissive.checkUri("http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(BlockedHostException.class);
            assertThatCode(() -> permissive.checkUri("mongodb://10.0.0.4:27017"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a URI with no host component is refused, so a hostless scheme cannot slip past")
        void hostlessUriRefused() {
            assertThatThrownBy(() -> permissive.checkUri("file:///etc/passwd"))
                    .isInstanceOf(BlockedHostException.class)
                    .hasMessageContaining("no host");
        }

        @Test
        @DisplayName("an unresolvable host is refused, not passed through as a distinct outcome")
        void unresolvableRefused() {
            assertThatThrownBy(() ->
                    permissive.checkHost("no-such-host.invalid"))
                    .isInstanceOf(BlockedHostException.class)
                    .hasMessageContaining("does not resolve");
        }
    }
}
