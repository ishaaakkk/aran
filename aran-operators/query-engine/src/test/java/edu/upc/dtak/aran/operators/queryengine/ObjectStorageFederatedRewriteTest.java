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
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.LakeClient;
import edu.upc.dtak.aran.core.domain.ObjectStorageRepository;
import edu.upc.dtak.aran.core.operator.OperatorRequest;
import edu.upc.dtak.aran.core.operator.OperatorResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rewrite-oql over a federated object-storage class — no execution, and (load-bearing) no
 * credential in any surfaced plan string. rewrite-oql annotates the plan with each dataset's access
 * mode, which loads the repository (and thus its secret key) to ask {@code SourceAccess.resolve}; so
 * even though nothing is executed, this pins that the secret does not travel from that lookup into
 * the {@code sql} / {@code logicalPlan} / {@code optimizedPlan} / {@code planShape} it returns.
 *
 * <p>Offline: rewrite-oql never touches the lake or DuckDB, so this needs no container.
 */
@DisplayName("rewrite-oql over federated object storage — FEDERATED annotation, zero credential leak")
class ObjectStorageFederatedRewriteTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NS = "http://www.essi.upc.edu/DTIM/NextiaDI/";
    private static final String PEOPLE = NS + "integration/People";
    private static final String P_ID = NS + "integration/id";
    private static final String P_NAME = NS + "integration/name";
    private static final UUID DS = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REPO = UUID.fromString("11112222-3333-4444-5555-666677778888");
    private static final String SECRET = "SEKRET-do-not-leak-4a3b9c";

    private final MappingCatalogOperator catalogOperator = mock(MappingCatalogOperator.class);
    private final DatasetRepository datasetRepository = mock(DatasetRepository.class);
    private final DataRepositoryRepository dataRepositoryRepository = mock(DataRepositoryRepository.class);
    private final LakeClient lake = mock(LakeClient.class);

    private QueryEngineOperator operator() {
        return new QueryEngineOperator(
                mock(GraphStore.class), catalogOperator, lake, new ObjectMapper(),
                mock(IntegrationModelRepository.class), datasetRepository, dataRepositoryRepository,
                new SnapshotCache(lake, "", 0L), mock(DataProductRepository.class));
    }

    private static MappingCatalog peopleCatalog() {
        MappingCatalog.SourceView sv = new MappingCatalog.SourceView(
                DS.toString(), "SELECT * FROM ${SNAPSHOT}", Map.of(P_ID, "id", P_NAME, "name"), 2L);
        return new MappingCatalog(
                Map.of(PEOPLE, new MappingCatalog.IntegratedClass(PEOPLE, List.of(sv))),
                List.of(), List.of(), "v1");
    }

    @Test
    @DisplayName("the plan annotates the source FEDERATED and never contains the secret key")
    void rewriteOqlAnnotatesFederatedWithoutLeaking() {
        when(catalogOperator.getCatalog(any())).thenReturn(peopleCatalog());
        when(datasetRepository.findById(any(), eq(DS)))
                .thenReturn(Optional.of(new Dataset(DS, REPO, "People", "")));
        DataRepository repo = new ObjectStorageRepository(REPO, UUID.randomUUID(), UUID.randomUUID(),
                "lake", "", "MINIO", "http://minio:9000", "people-bucket", "AKID", SECRET, "us-east-1");
        when(dataRepositoryRepository.findById(any(), eq(REPO))).thenReturn(Optional.of(repo));

        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "rewrite-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).as(String.valueOf(result.errorMessage())).isTrue();
        // The source is annotated as served federated (httpfs) in the plan shape.
        assertThat(result.data().get("planShape")).contains("FEDERATED");
        // No credential in any surfaced plan string.
        for (String key : List.of("sql", "logicalPlan", "optimizedPlan", "planShape", "planShapeText")) {
            assertThat(result.data().get(key))
                    .as("secret must not appear in %s", key)
                    .doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("sanity: the emitted SQL names the integrated view, not an s3:// read")
    void rewriteOqlSqlHasNoReadExpression() {
        when(catalogOperator.getCatalog(any())).thenReturn(peopleCatalog());
        // datasetRepository/dataRepositoryRepository are left as default (empty) mocks: the SQL is a
        // view-name translation that never carries the read expression — that lives only in the DDL
        // the executor builds at run time, never in the returned SQL.

        OperatorResult result = operator().execute(new OperatorRequest(TENANT, null, "rewrite-oql",
                Map.of("oql", "SELECT People.name FROM People")));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("sql")).doesNotContain("s3://").doesNotContain("read_parquet");
    }
}
