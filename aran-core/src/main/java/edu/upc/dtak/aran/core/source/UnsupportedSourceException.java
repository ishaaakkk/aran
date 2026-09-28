package edu.upc.dtak.aran.core.source;

import org.jspecify.annotations.NullMarked;

/** No registered {@link SourceConnector} claimed a descriptor. */
@NullMarked
public final class UnsupportedSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public UnsupportedSourceException(SourceDescriptor source) {
        super("No source connector supports dataset '" + source.datasetName()
                + "' (contentType=" + source.contentType()
                + ", filenameHint=" + source.filenameHint()
                + ", explicitKind=" + source.explicitKind() + ")");
    }
}
