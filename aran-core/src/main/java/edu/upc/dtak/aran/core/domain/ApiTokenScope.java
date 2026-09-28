package edu.upc.dtak.aran.core.domain;

/**
 * What an {@link ApiToken} is allowed to do.
 *
 * <p>Two levels rather than a permission matrix. The point of a token is to let a notebook export
 * a result or a scheduler re-materialize a product; a finer model would be guesswork about
 * use cases that do not exist yet, and every extra level is another thing to get wrong.
 */
public enum ApiTokenScope {

    /**
     * Safe HTTP methods only — GET, HEAD, OPTIONS — plus the small set of POST endpoints that
     * read rather than write, named explicitly in the authentication filter. Covers querying,
     * exporting and browsing the catalog, which is most of what automation wants, and cannot
     * promote a schema, delete a dataset or edit a model.
     *
     * <p>The default, because a credential that lives for months should not also be able to
     * destroy things.
     */
    READ,

    /** Everything the owning user could do through the UI. */
    FULL
}
