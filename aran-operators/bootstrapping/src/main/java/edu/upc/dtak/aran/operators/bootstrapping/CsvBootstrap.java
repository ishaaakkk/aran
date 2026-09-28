package edu.upc.dtak.aran.operators.bootstrapping;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Bootstraps a CSV file into an RDFS-typed graph with wrapper metadata.
 *
 * <p>The header row produces one {@code rdf:Property} per column, all attached to a
 * single {@code rdfs:Class} for the dataset. Per the paper, all cells start out as
 * {@code xsd:string} — type refinement is profiling's job, not bootstrapping's.
 *
 * <p>Wrapper layer ({@link WrapperVocab}): the root class carries
 * {@code datasetId}, {@code snapshotId}, {@code format="CSV"} and the literal
 * iterator {@code "row"} (CSV iteration is implicit — one record per row). Each
 * property carries its column name as {@code sourceRef}.
 *
 * <p>Delimiter detection mirrors the {@code DataRepositoryService} preview logic
 * (tab → semicolon → comma) so a TSV uploaded as {@code .csv} still parses.
 */
@NullMarked
final class CsvBootstrap {

    Model bootstrap(String datasetId, String datasetName, String snapshotId, byte[] bytes) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        char delimiter = detectDelimiter(bytes);

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(delimiter)
                .setQuote('"')
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {

            String classIri = BootstrapEngine.sourceClassIri(datasetId, datasetName);
            Resource cls = model.createResource(classIri);
            model.add(cls, RDF.type, RDFS.Class);
            model.add(cls, RDFS.label, datasetName);
            WrapperVocab.attachClassWrapper(model, cls, datasetId, snapshotId, "CSV", "row",
                    WrapperVocab.Shape.FLAT);

            for (String header : parser.getHeaderNames()) {
                String h = header.replace("\"", "").trim();
                if (h.isEmpty()) continue;
                String propIri = BootstrapEngine.sourcePropertyIri(datasetId, datasetName, h);
                Resource prop = model.createResource(propIri);
                model.add(prop, RDF.type, RDF.Property);
                model.add(prop, RDFS.label, h);
                model.add(prop, RDFS.domain, cls);
                model.add(prop, RDFS.range, XSD.xstring);
                model.add(prop, WrapperVocab.SOURCE_REF, h);
            }
        }
        return model;
    }

    private static char detectDelimiter(byte[] bytes) {
        int end = Math.min(bytes.length, 4096);
        String head = new String(bytes, 0, end, StandardCharsets.UTF_8);
        int newline = head.indexOf('\n');
        String firstLine = newline >= 0 ? head.substring(0, newline) : head;
        if (firstLine.indexOf('\t') >= 0) return '\t';
        if (firstLine.indexOf(';') >= 0) return ';';
        return ',';
    }
}
