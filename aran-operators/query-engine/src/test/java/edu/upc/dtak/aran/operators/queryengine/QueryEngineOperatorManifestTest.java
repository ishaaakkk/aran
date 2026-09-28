package edu.upc.dtak.aran.operators.queryengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.operator.OperationSpec;
import edu.upc.dtak.aran.core.operator.OperatorManifest;
import edu.upc.dtak.aran.core.operator.ParameterSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QueryEngineOperatorManifestTest {

    private QueryEngineOperator newOperator() {
        return new QueryEngineOperator(
                mock(GraphStore.class),
                mock(MappingCatalogOperator.class),
                mock(LakeClient.class),
                new ObjectMapper(),
                mock(IntegrationModelRepository.class),
                mock(DatasetRepository.class),
                mock(DataRepositoryRepository.class),
                mock(SnapshotCache.class),
                mock(edu.upc.dtak.aran.core.domain.DataProductRepository.class));
    }

    @Test
    void manifestListsAllTwelveOperations() {
        OperatorManifest manifest = newOperator().manifest();

        assertThat(manifest.operatorId()).isEqualTo("query-engine");
        assertThat(manifest.operations().stream().map(OperationSpec::name))
                .containsExactlyInAnyOrder(
                        "catalog", "cube-evidence", "explain", "run", "rewrite-oql", "run-oql",
                        "validate-constraint", "verify-wrapper", "materialize-oql", "preview-parquet",
                        "materialize-product-sql", "materialize-source-sql");
    }

    @Test
    void runOqlHasExactlyOneRequiredOqlParameter() {
        OperationSpec runOql = findOperation(newOperator().manifest(), "run-oql");

        // The name is the assertion: exactly one REQUIRED parameter. This used to check the total
        // count, which is a different claim — it went red when paging added an optional `offset`,
        // even though the property it is named for still held.
        List<ParameterSpec> required = runOql.parameters().stream()
                .filter(ParameterSpec::required).toList();
        assertThat(required).hasSize(1);
        assertThat(required.get(0).name()).isEqualTo("oql");

        // `limit` is a data product read's own page size (J6-F11, V37 gap 3).
        assertThat(runOql.parameters().stream().filter(p -> !p.required()).map(ParameterSpec::name))
                .containsExactly("offset", "limit");
    }

    @Test
    void previewParquetHasOneRequiredAndOneOptionalParameter() {
        OperationSpec previewParquet = findOperation(newOperator().manifest(), "preview-parquet");

        assertThat(previewParquet.parameters()).hasSize(2);

        List<ParameterSpec> required = previewParquet.parameters().stream()
                .filter(ParameterSpec::required).toList();
        assertThat(required).hasSize(1);
        assertThat(required.get(0).name()).isEqualTo("snapshotId");

        List<ParameterSpec> optional = previewParquet.parameters().stream()
                .filter(p -> !p.required()).toList();
        assertThat(optional).hasSize(1);
        assertThat(optional.get(0).name()).isEqualTo("limit");
    }

    private static OperationSpec findOperation(OperatorManifest manifest, String name) {
        Optional<OperationSpec> found = manifest.operations().stream()
                .filter(op -> op.name().equals(name))
                .findFirst();
        assertThat(found).as("operation '%s' present in manifest", name).isPresent();
        return found.get();
    }
}
