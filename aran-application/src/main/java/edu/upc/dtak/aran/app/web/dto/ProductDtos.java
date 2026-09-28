package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.DataProductStatus;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductTable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Data-transfer objects for the data products API (M8). The wire shape mirrors
 * the {@code data_product} table one-to-one.
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    /**
     * Body of {@code POST /api/projects/{projectId}/products}.
     *
     * @param accessMode MATERIALIZED or VIRTUAL (Phase 5); {@code null} — the field an existing
     *                   client's request body simply does not have — defaults to MATERIALIZED
     *                   at the controller, exactly today's only behavior.
     */
    public record CreateProductRequest(String name, @Nullable String description, String oql,
                                       @Nullable ProductAccessMode accessMode) {}

    /**
     * A data product as the API renders it.
     *
     * @param schemaVersionId       the version the product was defined against (never moves)
     * @param builtAgainstVersionId the version its last successful build ran under (UI-OD12) —
     *                              what a MATERIALIZED product's rows mean; null when never built,
     *                              VIRTUAL, or built before V47 recorded it
     * @param tableName             the table it is served as in the JDBC {@code products} schema
     *                              (and the Iceberg {@code products} namespace), from
     *                              {@code ProductCatalog} over the whole tenant — normalised and
     *                              possibly suffixed, which the name alone does not tell a reader
     * @param servedOverIceberg     whether the read-only Iceberg catalog serves it
     *                              ({@code ProductTable.servedOverIceberg}, the rule the catalog itself
     *                              filters on): false for VIRTUAL, never-built, and a product built
     *                              before V29 recorded its columns
     * @param memberOfCube          the cube whose star this product belongs to, or null; such a
     *                              product is deleted with its cube, never on its own
     */
    public record DataProductDto(
            UUID id,
            UUID projectId,
            String name,
            @Nullable String description,
            String oql,
            UUID schemaVersionId,
            ProductAccessMode accessMode,
            DataProductStatus status,
            @Nullable String snapshotId,
            @Nullable Long rowCount,
            @Nullable Instant materializedAt,
            @Nullable String lastError,
            @Nullable UUID createdBy,
            Instant createdAt,
            @Nullable UUID builtAgainstVersionId,
            String tableName,
            boolean servedOverIceberg,
            @Nullable String memberOfCube
    ) {
        /** @param table {@code p} as the tenant's {@code ProductCatalog} serves it — its name and doors */
        public static DataProductDto from(DataProduct p, ProductTable table, @Nullable String memberOfCube) {
            return new DataProductDto(
                    p.id(), p.projectId(), p.name(), p.description(), p.oql(),
                    p.schemaVersionId(), p.accessMode(), p.status(), p.snapshotId(), p.rowCount(),
                    p.materializedAt(), p.lastError(), p.createdBy(), p.createdAt(),
                    p.builtAgainstVersionId(), table.sqlName(), table.servedOverIceberg(), memberOfCube);
        }
    }
}
