package edu.upc.dtak.aran.core.domain.cube;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.cube.CubeEvidence.EvidenceEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stringly-typed seam to the query engine, read once here. The engine is in a module core
 * cannot see, so these assertions are the contract's only compile-independent half.
 */
@DisplayName("CubeEvidence.parse")
class CubeEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("classes, properties and edges arrive with their profile evidence")
    void parses_a_full_payload() {
        CubeEvidence evidence = CubeEvidence.parse("""
                {"classes":[
                  {"iri":"g#Sales","name":"Sales","rowCount":2100000,"properties":[
                    {"iri":"g#amount","name":"amount","xsd":"xsd:decimal","numeric":true,
                     "ndv":98000,"nullFraction":0.0}]}],
                 "edges":[
                  {"propertyIri":"g#hasCustomer","propertyName":"hasCustomer",
                   "leftClassIri":"g#Sales","rightClassIri":"g#Customer",
                   "leftColIri":"g#customerId","rightColIri":"g#customerKey",
                   "tier":"DECLARED"}]}
                """, MAPPER);

        assertThat(evidence.classOf("g#Sales")).hasValueSatisfying(sales -> {
            assertThat(sales.rowCount()).isEqualTo(2_100_000L);
            assertThat(sales.property("g#amount")).hasValueSatisfying(amount -> {
                assertThat(amount.numeric()).isTrue();
                assertThat(amount.xsd()).isEqualTo("xsd:decimal");
                assertThat(amount.ndv()).isEqualTo(98_000L);
                assertThat(amount.nullFraction()).isEqualTo(0.0);
            });
        });
        assertThat(evidence.edgeOf("g#hasCustomer")).hasValueSatisfying(edge -> {
            assertThat(edge.propertyName())
                    .as("the engine's propertyName, not the IRI a missing name falls back to")
                    .isEqualTo("hasCustomer");
            assertThat(edge.tier()).isEqualTo("DECLARED");
            assertThat(edge.touches("g#Sales")).isTrue();
            assertThat(edge.otherEnd("g#Sales")).isEqualTo("g#Customer");
            assertThat(edge.columnOn("g#Sales")).isEqualTo("g#customerId");
            assertThat(edge.columnOppositeTo("g#Sales")).isEqualTo("g#customerKey");
        });
    }

    @Test
    @DisplayName("an unprofiled class or property parses with nulls, not with invented numbers")
    void absent_evidence_stays_absent() {
        CubeEvidence evidence = CubeEvidence.parse("""
                {"classes":[{"iri":"g#Sales","name":"Sales","rowCount":null,"properties":[
                  {"iri":"g#note","name":"note","xsd":null,"numeric":false,
                   "ndv":null,"nullFraction":null}]}],"edges":[]}
                """, MAPPER);

        assertThat(evidence.classOf("g#Sales")).hasValueSatisfying(sales -> {
            assertThat(sales.rowCount()).isNull();
            assertThat(sales.property("g#note")).hasValueSatisfying(note -> {
                assertThat(note.numeric()).isFalse();
                assertThat(note.xsd()).isNull();
                assertThat(note.ndv()).isNull();
                assertThat(note.nullFraction()).isNull();
            });
        });
    }

    @Test
    @DisplayName("an entry with no identity is dropped — nothing could reference it anyway")
    void entries_without_an_iri_are_dropped() {
        CubeEvidence evidence = CubeEvidence.parse("""
                {"classes":[{"name":"nameless","properties":[{"name":"nameless"}]},
                            {"iri":"g#Sales","name":"Sales","properties":[]}],
                 "edges":[{"propertyName":"nameless","leftClassIri":"g#Sales"}]}
                """, MAPPER);

        assertThat(evidence.classes()).extracting(CubeEvidence.EvidenceClass::iri)
                .containsExactly("g#Sales");
        assertThat(evidence.edges()).isEmpty();
    }

    @Test
    @DisplayName("an edge with no tier is read as HYPOTHESIS — the tier that is refused by default")
    void a_missing_tier_is_the_untrusted_one() {
        CubeEvidence evidence = CubeEvidence.parse("""
                {"classes":[],"edges":[{"propertyIri":"g#p","propertyName":"p",
                  "leftClassIri":"g#A","rightClassIri":"g#B",
                  "leftColIri":"g#a","rightColIri":"g#b"}]}
                """, MAPPER);

        assertThat(evidence.edgeOf("g#p")).hasValueSatisfying(
                edge -> assertThat(edge.tier()).isEqualTo(EvidenceEdge.HYPOTHESIS));
    }

    @Test
    @DisplayName("an empty payload parses to an empty evidence base rather than throwing")
    void an_empty_payload_is_empty_evidence() {
        CubeEvidence evidence = CubeEvidence.parse("{}", MAPPER);

        assertThat(evidence.classes()).isEmpty();
        assertThat(evidence.edges()).isEmpty();
        assertThat(evidence.classOf("g#Sales")).isEmpty();
    }

    @Test
    @DisplayName("a self-join's opposite column is the far side, not the near one read twice")
    void a_self_join_keeps_its_orientation() {
        CubeEvidence evidence = CubeEvidence.parse("""
                {"classes":[],"edges":[{"propertyIri":"g#manager","propertyName":"manager",
                  "leftClassIri":"g#Employee","rightClassIri":"g#Employee",
                  "leftColIri":"g#managerId","rightColIri":"g#employeeId",
                  "tier":"DECLARED"}]}
                """, MAPPER);

        assertThat(evidence.edgeOf("g#manager")).hasValueSatisfying(edge -> {
            assertThat(edge.columnOn("g#Employee")).isEqualTo("g#managerId");
            assertThat(edge.columnOppositeTo("g#Employee")).isEqualTo("g#employeeId");
        });
    }
}
