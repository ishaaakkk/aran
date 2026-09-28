package edu.upc.dtak.aran.operators.bootstrapping;

import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;

/**
 * The naming scheme for source-schema IRIs and the Fuseki graph that holds them.
 *
 * <p>Extraction itself lives behind {@code SourceConnector} ({@code SourceConnectors.forSource}
 * → {@code connector.introspect}); this class is the shared vocabulary those connectors, the
 * drift probes and the query engine all mint against, so every source graph agrees on where a
 * class and a property live.
 */
@NullMarked
public final class BootstrapEngine {

    /** Namespace for all source-schema IRIs. Matches the old NextiaDI namespace. */
    public static final String SOURCE_NS = "http://www.essi.upc.edu/DTIM/NextiaDI/source/";

    /** Named graph URI prefix used in Fuseki to store one graph per source. */
    public static final String SOURCE_GRAPH_PREFIX = "urn:aran:source:";

    private BootstrapEngine() {}

    /** The named graph in Fuseki that holds the bootstrapped schema of one dataset. */
    public static String sourceGraphUri(String datasetId) {
        return SOURCE_GRAPH_PREFIX + datasetId;
    }

    /** Mints the IRI for the top-level class of a source. */
    static String sourceClassIri(String datasetId, String pathSoFar) {
        return SOURCE_NS + datasetId + "/" + Names.iriFragment(pathSoFar);
    }

    /** Mints the IRI for a property hanging off a class. */
    static String sourcePropertyIri(String datasetId, String pathSoFar, String key) {
        return SOURCE_NS + datasetId + "/" + Names.iriFragment(pathSoFar) + "/" + Names.iriFragment(key);
    }
}
