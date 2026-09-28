package edu.upc.dtak.aran.app.web;

import edu.upc.dtak.aran.app.identity.CurrentUserResolver;
import edu.upc.dtak.aran.app.web.dto.JobDtos.JobSubmission;
import edu.upc.dtak.aran.app.web.dto.ProductDtos.CreateProductRequest;
import edu.upc.dtak.aran.app.web.dto.ProductDtos.DataProductDto;
import edu.upc.dtak.aran.core.domain.DataProduct;
import edu.upc.dtak.aran.core.domain.ProductAccessMode;
import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.domain.ProductColumn;
import edu.upc.dtak.aran.core.domain.ProductTable;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import edu.upc.dtak.aran.core.identity.ProjectAccess;
import edu.upc.dtak.aran.core.service.DataProductService;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for a project's data products (M8, V18).
 *
 * <p>A data product is a named OQL view pinned to a schema version, materialized to
 * a parquet snapshot in the lake. Materialization runs on the job substrate
 * ({@code POST /{id}/materialize} → 202 + jobId); preview serves rows from the
 * materialized snapshot, never re-executing the query.
 *
 * <p>Every endpoint asks {@link ProjectAccess} first, and a by-id endpoint then requires the
 * product to be that project's (S2-1): the project gate alone would make any project the caller
 * can see a door to every product in the tenant by id.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/products")
public class DataProductController {

    private final DataProductService productService;
    private final CurrentUserResolver currentUser;
    private final ProjectAccess projectAccess;

    public DataProductController(DataProductService productService,
                                 CurrentUserResolver currentUser,
                                 ProjectAccess projectAccess) {
        this.productService = productService;
        this.currentUser = currentUser;
        this.projectAccess = projectAccess;
    }

    @GetMapping
    public List<DataProductDto> list(@PathVariable UUID projectId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        Map<UUID, ProductTable> tables = servedTables(tenant);
        Map<UUID, String> cubes = productService.cubeMembership(tenant, projectId);
        return productService.list(tenant, projectId).stream()
                .map(p -> toDto(p, tables, cubes)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DataProductDto create(@PathVariable UUID projectId,
                                 @RequestBody CreateProductRequest body) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        // A request body an existing client sent before this field existed carries no
        // accessMode at all (null, not a wire default) — MATERIALIZED is what that always meant.
        ProductAccessMode accessMode =
                body.accessMode() != null ? body.accessMode() : ProductAccessMode.MATERIALIZED;
        DataProduct created = productService.create(
                tenant(caller), projectId, body.name(), body.description(),
                body.oql(), caller.userId(), accessMode);
        return toDto(created, servedTables(tenant(caller)), Map.of());
    }

    @GetMapping("/{productId}")
    public DataProductDto get(@PathVariable UUID projectId, @PathVariable UUID productId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        TenantContext tenant = tenant(caller);
        return toDto(productService.get(tenant, projectId, productId), servedTables(tenant),
                productService.cubeMembership(tenant, projectId));
    }

    @PostMapping("/{productId}/materialize")
    public ResponseEntity<JobSubmission> materialize(@PathVariable UUID projectId,
                                                     @PathVariable UUID productId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        productService.get(tenant(caller), projectId, productId);
        UUID jobId = productService.materialize(tenant(caller), productId, caller.userId());
        return ResponseEntity.accepted().body(new JobSubmission(jobId));
    }

    /** The materialized snapshot's first rows, shaped like a workbench query result. */
    @GetMapping("/{productId}/preview")
    public ResponseEntity<String> preview(@PathVariable UUID projectId,
                                          @PathVariable UUID productId,
                                          @RequestParam(defaultValue = "100") int limit) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        productService.get(tenant(caller), projectId, productId);
        String payload = productService.preview(tenant(caller), productId, limit);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID productId) {
        AuthenticatedUser caller = currentUser.require();
        projectAccess.getVisibleProject(caller, projectId);
        productService.get(tenant(caller), projectId, productId);
        productService.delete(tenant(caller), productId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Every product as it is served, from {@link ProductCatalog} over the WHOLE tenant — the one
     * derivation the JDBC tree, the query engine and the Iceberg catalog share. A per-project catalog
     * would drop the collision suffix another project's earlier product forces, and name a table
     * nothing serves.
     */
    private Map<UUID, ProductTable> servedTables(TenantContext tenant) {
        Map<UUID, ProductTable> tables = new HashMap<>();
        for (ProductTable table : ProductCatalog.of(productService.listAll(tenant)).all()) {
            tables.put(table.product().id(), table);
        }
        return tables;
    }

    private static DataProductDto toDto(DataProduct product, Map<UUID, ProductTable> tables,
                                        Map<UUID, String> cubes) {
        ProductTable table = tables.get(product.id());
        if (table == null) {
            // Not in the tenant listing it was read from (a concurrent write): its base name, and
            // doors judged off this row alone.
            table = new ProductTable(ProductCatalog.baseTableName(product.name()), product,
                    ProductColumn.fromJson(product.outputSchema()));
        }
        return DataProductDto.from(product, table, cubes.get(product.id()));
    }

    private static TenantContext tenant(AuthenticatedUser caller) {
        return new TenantContext(caller.tenantId().toString());
    }
}
