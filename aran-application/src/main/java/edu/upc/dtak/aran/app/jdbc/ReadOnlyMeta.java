package edu.upc.dtak.aran.app.jdbc;

import org.apache.calcite.avatica.ColumnMetaData;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.MetaImpl;
import org.apache.calcite.avatica.MissingResultsException;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.avatica.QueryState;
import org.apache.calcite.avatica.remote.TypedValue;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The uninteresting three quarters of {@link Meta}: JDBC catalog methods Aran has no answer for,
 * and every write path.
 *
 * <p>{@code Meta} is a forty-method interface because it mirrors {@code DatabaseMetaData}. Aran
 * answers five of those questions — catalogs, schemas, table types, tables, columns — and has no
 * stored procedures, no foreign keys it exposes to JDBC, no indexes and no UDTs. Rather than
 * scatter thirty near-identical stubs through {@link AranMeta}, they live here so the subclass
 * contains only behaviour that is actually Aran's.
 *
 * <p>An unanswerable question returns an <em>empty result set</em> rather than throwing. A JDBC
 * client calls a dozen of these on connect to decide what it can offer the user; throwing turns a
 * working connection into a failed one over a question whose honest answer is "none".
 *
 * <p>Writes are the opposite: they throw. There is no DML path into an Aran integrated view — the
 * data belongs to the sources — and silently accepting an {@code INSERT} that changes nothing
 * would be worse than refusing it.
 */
@NullMarked
abstract class ReadOnlyMeta implements Meta {

    /**
     * Ids for statements the server hands out. Shared with metadata result sets: they are
     * statements as far as the client is concerned, and it closes them by id like any other.
     */
    protected final AtomicInteger statementIds = new AtomicInteger();

    /** An empty result set whose columns are all VARCHAR, named as given. */
    protected MetaResultSet emptyResultSet(ConnectionHandle ch, String... columnNames) {
        List<ColumnMetaData> columns = new ArrayList<>(columnNames.length);
        for (int i = 0; i < columnNames.length; i++) {
            columns.add(MetaImpl.columnMetaData(columnNames[i], i, String.class, true));
        }
        return resultSet(ch, columns, List.of());
    }

    /** A result set delivered whole in its first frame — every metadata answer is small. */
    protected MetaResultSet resultSet(ConnectionHandle ch, List<ColumnMetaData> columns,
                                      List<Object> rows) {
        Signature signature = Signature.create(columns, "", List.of(), CursorFactory.LIST,
                StatementType.SELECT);
        return MetaResultSet.create(ch.id, statementIds.incrementAndGet(), true, signature,
                Frame.create(0, true, rows));
    }

    // ── Catalog questions Aran has no answer for ────────────────────────────────

    @Override public MetaResultSet getProcedures(ConnectionHandle ch, String catalog, Pat schema,
                                                 Pat procedure) {
        return emptyResultSet(ch, "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "REMARKS",
                "PROCEDURE_TYPE", "SPECIFIC_NAME");
    }

    @Override public MetaResultSet getProcedureColumns(ConnectionHandle ch, String catalog, Pat schema,
                                                       Pat procedure, Pat column) {
        return emptyResultSet(ch, "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME");
    }

    @Override public MetaResultSet getColumnPrivileges(ConnectionHandle ch, String catalog, String schema,
                                                       String table, Pat column) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR",
                "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override public MetaResultSet getTablePrivileges(ConnectionHandle ch, String catalog, Pat schema,
                                                      Pat table) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE",
                "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override public MetaResultSet getBestRowIdentifier(ConnectionHandle ch, String catalog, String schema,
                                                        String table, int scope, boolean nullable) {
        return emptyResultSet(ch, "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME");
    }

    @Override public MetaResultSet getVersionColumns(ConnectionHandle ch, String catalog, String schema,
                                                     String table) {
        return emptyResultSet(ch, "SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME");
    }

    /**
     * Empty on purpose. The integrated schema knows join keys — {@code MappingCatalog.JoinProperty}
     * carries them — but a JDBC primary key is a uniqueness guarantee, and a discovered join key is
     * a hypothesis a human accepted. Reporting the second as the first would let a BI tool infer
     * cardinality that nothing enforces.
     */
    @Override public MetaResultSet getPrimaryKeys(ConnectionHandle ch, String catalog, String schema,
                                                  String table) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ",
                "PK_NAME");
    }

    // getImportedKeys/getExportedKeys are deliberately NOT stubbed here. AranMeta — the sole
    // subclass — overrides both with the real 14-column JDBC shape off MappingCatalog.JoinProperty,
    // so a base stub was unreachable, and its 4-column shape was wrong besides: a client that ever
    // did reach it would read PKCOLUMN_NAME out of the wrong ordinal (audit JDBC_AVATICA-5).
    // Avatica's MetaImpl supplies the abstract-method obligation.

    @Override public MetaResultSet getCrossReference(ConnectionHandle ch, String parentCatalog,
                                                     String parentSchema, String parentTable,
                                                     String foreignCatalog, String foreignSchema,
                                                     String foreignTable) {
        return emptyResultSet(ch, "PKTABLE_CAT", "PKTABLE_NAME", "FKTABLE_CAT", "FKTABLE_NAME");
    }

    @Override public MetaResultSet getTypeInfo(ConnectionHandle ch) {
        return emptyResultSet(ch, "TYPE_NAME", "DATA_TYPE", "PRECISION", "NULLABLE");
    }

    @Override public MetaResultSet getIndexInfo(ConnectionHandle ch, String catalog, String schema,
                                                String table, boolean unique, boolean approximate) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "INDEX_NAME");
    }

    @Override public MetaResultSet getUDTs(ConnectionHandle ch, String catalog, Pat schema, Pat type,
                                           int[] types) {
        return emptyResultSet(ch, "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE");
    }

    @Override public MetaResultSet getSuperTypes(ConnectionHandle ch, String catalog, Pat schema, Pat type) {
        return emptyResultSet(ch, "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME");
    }

    @Override public MetaResultSet getSuperTables(ConnectionHandle ch, String catalog, Pat schema, Pat table) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override public MetaResultSet getAttributes(ConnectionHandle ch, String catalog, Pat schema, Pat type,
                                                 Pat attribute) {
        return emptyResultSet(ch, "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME");
    }

    @Override public MetaResultSet getClientInfoProperties(ConnectionHandle ch) {
        return emptyResultSet(ch, "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    @Override public MetaResultSet getFunctions(ConnectionHandle ch, String catalog, Pat schema,
                                                Pat function) {
        return emptyResultSet(ch, "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS",
                "FUNCTION_TYPE", "SPECIFIC_NAME");
    }

    @Override public MetaResultSet getFunctionColumns(ConnectionHandle ch, String catalog, Pat schema,
                                                      Pat function, Pat column) {
        return emptyResultSet(ch, "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME");
    }

    @Override public MetaResultSet getPseudoColumns(ConnectionHandle ch, String catalog, Pat schema,
                                                    Pat table, Pat column) {
        return emptyResultSet(ch, "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME");
    }

    // ── Writes: refused, not ignored ────────────────────────────────────────────

    @Override public ExecuteBatchResult prepareAndExecuteBatch(StatementHandle h, List<String> sqlCommands)
            throws NoSuchStatementException {
        throw readOnly();
    }

    @Override public ExecuteBatchResult executeBatch(StatementHandle h,
                                                     List<List<TypedValue>> parameterValues)
            throws NoSuchStatementException {
        throw readOnly();
    }

    /**
     * No-op rather than a refusal, unlike the batch methods above.
     *
     * <p>A JDBC client on autocommit sends a commit after a plain {@code SELECT}; refusing it
     * would break reading, which is the one thing this endpoint is for. With no write path there
     * is nothing to commit, so the honest implementation of "make my changes durable" when there
     * are no changes is to do nothing.
     */
    @Override public void commit(ConnectionHandle ch) {
    }

    @Override public void rollback(ConnectionHandle ch) {
    }

    protected static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException(
                "Aran's JDBC endpoint is read-only: it runs OQL queries. "
                        + "Data belongs to the underlying sources and is not writable through the "
                        + "integrated schema.");
    }

    // ── Result-set paging the subclass owns ─────────────────────────────────────

    @Override public abstract Frame fetch(StatementHandle h, long offset, int fetchMaxRowCount)
            throws NoSuchStatementException, MissingResultsException;

    /**
     * Always false: this endpoint cannot re-create a lost result set.
     *
     * <p>Avatica calls this when a client asks for a frame the server no longer holds — an expired
     * or closed statement. Answering true would promise the rows are coming; false makes the
     * client raise the missing-results error it already knows how to report.
     */
    @Override public boolean syncResults(StatementHandle sh, QueryState state, long offset) {
        return false;
    }

    @Override public Iterable<Object> createIterable(StatementHandle handle, QueryState state,
                                                     Signature signature, List<TypedValue> parameters,
                                                     Frame firstFrame) {
        // Server-side Meta never materialises an iterable; the client assembles rows from frames.
        return List.of();
    }
}
