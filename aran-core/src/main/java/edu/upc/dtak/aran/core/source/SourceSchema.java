package edu.upc.dtak.aran.core.source;

import org.apache.jena.rdf.model.Model;
import org.jspecify.annotations.NullMarked;

/**
 * The result of introspecting one source dataset: an RDFS schema graph (classes + xsd-typed
 * properties + wrapper metadata, exactly what schema-integration consumes).
 *
 * @param rdfs the schema graph, typed against the RDFS metamodel with wrapper triples.
 */
@NullMarked
public record SourceSchema(Model rdfs) {}
