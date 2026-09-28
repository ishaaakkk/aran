package edu.upc.dtak.aran.app.config;

import edu.upc.dtak.aran.core.source.SingerTapCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the Singer tap allowlist from configuration.
 *
 * <p>The commands Aran may execute are deployment configuration, never user input — see
 * {@link SingerTapCatalog} for why. Configuration lives here rather than in {@code aran-core}
 * because binding is a Spring concern and the core holds the rule, matching how
 * {@code LicensePlansProperties} sits in the persistence adapter.
 *
 * <p>Example:
 *
 * <pre>
 * aran:
 *   singer:
 *     taps:
 *       - id: exchangeratesapi
 *         label: Exchange Rates API
 *         command: [/opt/taps/bin/tap-exchangeratesapi]
 *         config-keys: [base, start_date, api_key]
 *         secret-keys: [api_key]
 * </pre>
 *
 * <p>Absent configuration means an empty catalogue, which means the feature is off. A deployment
 * has to opt in by naming an executable it already trusts, which is the whole safety property:
 * installing the tap is a deliberate act by whoever runs the server.
 */
@Configuration
public class SingerTapConfig {

    private static final Logger log = LoggerFactory.getLogger(SingerTapConfig.class);

    /**
     * One configured tap, in Spring's mutable-bean shape.
     *
     * <p>Not the immutable {@link SingerTapCatalog.Tap} record directly: relaxed binding for a
     * record needs constructor binding, and validation failures there surface as binding errors
     * that name the constructor rather than the setting. Converting explicitly in
     * {@link #singerTapCatalog} keeps Aran's own error messages.
     */
    @ConfigurationProperties(prefix = "aran.singer")
    public static class SingerProperties {

        private List<TapProperties> taps = new ArrayList<>();

        public List<TapProperties> getTaps() {
            return taps;
        }

        public void setTaps(List<TapProperties> taps) {
            this.taps = taps == null ? new ArrayList<>() : taps;
        }
    }

    /** A single entry under {@code aran.singer.taps}. */
    public static class TapProperties {

        private String id;
        private String label;
        private List<String> command = new ArrayList<>();
        private Set<String> configKeys = Set.of();
        private Set<String> secretKeys = Set.of();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public List<String> getCommand() {
            return command;
        }

        public void setCommand(List<String> command) {
            this.command = command == null ? new ArrayList<>() : command;
        }

        public Set<String> getConfigKeys() {
            return configKeys;
        }

        public void setConfigKeys(Set<String> configKeys) {
            this.configKeys = configKeys == null ? Set.of() : configKeys;
        }

        public Set<String> getSecretKeys() {
            return secretKeys;
        }

        public void setSecretKeys(Set<String> secretKeys) {
            this.secretKeys = secretKeys == null ? Set.of() : secretKeys;
        }
    }

    @Bean
    public SingerProperties singerProperties() {
        return new SingerProperties();
    }

    /**
     * Build the catalogue, failing startup on a bad entry.
     *
     * <p>Refusing to start is the right response to a misconfigured allowlist: the alternative is
     * an instance that looks healthy and silently offers fewer taps than its operator believes,
     * and the day they notice is the day someone needed one.
     */
    @Bean
    public SingerTapCatalog singerTapCatalog(SingerProperties properties) {
        List<SingerTapCatalog.Tap> taps = properties.getTaps().stream()
                .map(p -> new SingerTapCatalog.Tap(
                        p.getId(), p.getLabel(), p.getCommand(),
                        p.getConfigKeys(), p.getSecretKeys()))
                .toList();

        SingerTapCatalog catalog = new SingerTapCatalog(taps);
        if (catalog.isEnabled()) {
            // Ids only. The commands are paths on this host and the key names hint at what a tap
            // authenticates with; neither belongs in a log that gets pasted into an issue.
            log.info("Singer taps enabled: {}", catalog.all().stream().map(SingerTapCatalog.Tap::id).toList());
        }
        return catalog;
    }
}
