package edu.upc.dtak.aran.app.jdbc;

import edu.upc.dtak.aran.core.domain.ProductCatalog;
import edu.upc.dtak.aran.core.identity.AuthenticatedUser;
import org.jspecify.annotations.NullMarked;

/**
 * The one question the JDBC {@code products} schema asks of the data-product subsystem: what are
 * this caller's products, as SQL tables?
 *
 * <p>A seam rather than a direct call on {@code DataProductService}, because the JDBC tree needs
 * none of that service's lifecycle — create, materialize, stale-mark, preview — and taking the
 * whole of it would make every JDBC test stand up a job runner and a lake client to read a list.
 */
@FunctionalInterface
@NullMarked
public interface ProductRegistry {

    /** The caller's tenant's products, named and ordered by {@link ProductCatalog}. */
    ProductCatalog catalogFor(AuthenticatedUser caller);
}
