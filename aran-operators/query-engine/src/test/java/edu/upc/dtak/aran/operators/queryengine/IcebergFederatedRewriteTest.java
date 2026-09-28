package edu.upc.dtak.aran.operators.queryengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.upc.dtak.aran.core.domain.DataProductRepository;
import edu.upc.dtak.aran.core.domain.DataRepository;
import edu.upc.dtak.aran.core.domain.DataRepositoryRepository;
import edu.upc.dtak.aran.core.domain.Dataset;
import edu.upc.dtak.aran.core.domain.DatasetRepository;
import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.IcebergCatalogClient;
import edu.upc.dtak.aran.core.domain.IcebergRestRepository;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rewrite-oql over a federated Iceberg class (Gap 2) — the twin of
 * {@link ObjectStorageFederatedRewriteTest}, pinning the same two properties for the new kind:
 * the plan annotates the source FEDERATED, and neither the catalog token nor the data-plane
 * secret travels into any surfaced plan string.
 *
 * <p>Offline: rewrite-oql never dials the catalog (the metadata location is fetched only at
 * execution), so the client mock below must never be called — asserted by handing it none.
 */
@DisplayName("rewrite-oql over a federated Iceberg table — FEDERATED annotation, zero credential leak")
class IcebergFederatedRewriteTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeee01");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778801");
    private static final String TOKEN = "catalog-token-do-not-leak";
    private static final String SECRET = "SEKRET-do-not-leak-9d1e";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final LakeClient lake = mock(LakeClient.class);
    private final IcebergCatalogClient icebergCatalog = mock(IcebergCatalogClient.class);

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                mock(GraphStore.class), catalogOperator, lake, new ObjectMapper(),
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class),
                Optional.of(icebergCatalog));
    }

    private static MappingCatalog peopleCatalog() {
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"), 2L);
        return new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("the plan annotates the source FEDERATED and never contains either credential")
    void rewriteOqlAnnotatesFederatedWithoutLeaking() {
        when(catalogOperator.getCatalog(any())).thenReturn(peopleCatalog());
        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "db.people", "")));
        DataRepository repo = new IcebergRestRepository(REPO, UUID.randomUUID(), UUID.randomUUID(),
                "lakehouse", "", "http://localhost:8181/api/catalog", "prod", TOKEN,
                "MINIO", "http://localhost:9000", "AKID", SECRET, "us-east-1");
        when(dataRepositoryRepository.findById(any(), eq(REPO))).thenReturn(Optional.of(repo));

        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "rewrite-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        assertThat(result.data().get("planShape")).contains("FEDERATED");
        for (String key : List.of("sql", "logicalPlan", "optimizedPlan", "planShape", "planShapeText")) {
            assertThat(result.data().get(key))
                    .as("credential must not appear in %s", key)
                    .doesNotContain(SECRET)
                    .doesNotContain(TOKEN);
        }
    }
}
