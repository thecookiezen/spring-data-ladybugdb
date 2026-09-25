package com.thecookiezen.ladybugdb.spring.vector;

import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
import com.thecookiezen.ladybugdb.spring.mapper.RowMapper;
import com.thecookiezen.ladybugdb.spring.mapper.ValueMappers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Managed API for the lifecycle of HNSW vector indexes exposed by the
 * LadybugDB vector extension: {@code CREATE_VECTOR_INDEX},
 * {@code DROP_VECTOR_INDEX} and {@code SHOW_INDEXES}.
 * <p>
 * The operations are safe to run repeatedly: {@link #create} is idempotent and
 * {@link #drop} is tolerant of a missing index, so callers never need to parse
 * error messages. The vector extension is loaded on the connection before every
 * operation; it must be installed once per environment with
 * {@code INSTALL vector} (see the LadybugDB vector extension documentation).
 * <p>
 * Because the documented maintenance procedure for write-heavy workloads is a
 * periodic DROP + CREATE (dead HNSW edges accumulate after updates and
 * deletes), {@link #rebuild} exposes that pattern as a single supported call.
 * <p>
 * Obtain an instance via {@link LadybugDBTemplate#vectorIndexes()} or construct
 * it directly with a template. All operations participate in the surrounding
 * Spring transaction, if any.
 */
public class VectorIndexOperations {

    /**
     * Name of the LadybugDB vector extension loaded before every operation.
     */
    public static final String VECTOR_EXTENSION = "vector";

    /**
     * Index type reported by {@code SHOW_INDEXES()} for vector indexes.
     */
    public static final String HNSW_INDEX_TYPE = "HNSW";

    private static final String[] EXTENSIONS = {VECTOR_EXTENSION};
    private static final String SHOW_INDEXES = "CALL SHOW_INDEXES() RETURN *";

    private static final RowMapper<VectorIndexInfo> INDEX_MAPPER = row -> new VectorIndexInfo(
            ValueMappers.asString(row.getValue("table_name")),
            ValueMappers.asString(row.getValue("index_name")),
            ValueMappers.asString(row.getValue("index_type")),
            ValueMappers.asStringList(row.getValue("property_names")),
            Boolean.TRUE.equals(ValueMappers.asBoolean(row.getValue("extension_loaded"))),
            ValueMappers.asString(row.getValue("index_definition")));

    private final LadybugDBTemplate template;

    /**
     * @param template the template used to execute the underlying CALL statements
     */
    public VectorIndexOperations(LadybugDBTemplate template) {
        this.template = Objects.requireNonNull(template, "template must not be null");
    }

    /**
     * Creates an HNSW index with the default options.
     *
     * @param tableName    name of the node table containing the vector property
     * @param indexName    name of the index to create
     * @param propertyName name of the FLOAT[] property to index
     * @return {@code true} if the index was created, {@code false} if an index
     *         with that name already existed on the table (idempotent no-op)
     */
    public boolean create(String tableName, String indexName, String propertyName) {
        return create(tableName, indexName, propertyName, HnswOptions.defaults());
    }

    /**
     * Creates an HNSW index. If an index with the same name already exists on
     * the table, nothing is created and {@code false} is returned; the existing
     * index is never modified.
     *
     * @param tableName    name of the node table containing the vector property
     * @param indexName    name of the index to create
     * @param propertyName name of the FLOAT[] property to index
     * @param options      HNSW tuning options
     * @return {@code true} if the index was created, {@code false} if an index
     *         with that name already existed on the table (idempotent no-op)
     * @throws VectorIndexException     if the engine rejects the creation (e.g.
     *                                  unknown table or non-vector property)
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in an index name
     */
    public boolean create(String tableName, String indexName, String propertyName, HnswOptions options) {
        CypherSupport.requireName(tableName, "tableName");
        CypherSupport.requireName(indexName, "indexName");
        CypherSupport.requireName(propertyName, "propertyName");
        Objects.requireNonNull(options, "options must not be null");

        if (exists(tableName, indexName)) {
            return false;
        }

        String cypher = "CALL CREATE_VECTOR_INDEX('" + tableName + "', '" + indexName + "', '" + propertyName + "'"
                + options.toCypherOptions() + ")";
        try {
            execute(cypher);
            return true;
        } catch (RuntimeException e) {
            if (exists(tableName, indexName)) {
                // A concurrent creation won the race — same idempotent outcome.
                return false;
            }
            throw new VectorIndexException("Failed to create vector index '" + indexName
                    + "' on table '" + tableName + "'", e);
        }
    }

    /**
     * Drops an HNSW index.
     *
     * @param tableName name of the node table the index belongs to
     * @param indexName name of the index to drop
     * @return {@code true} if the index was dropped, {@code false} if no index
     *         with that name existed on the table (tolerant no-op)
     * @throws VectorIndexException     if the engine rejects the drop
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in an index name
     */
    public boolean drop(String tableName, String indexName) {
        CypherSupport.requireName(tableName, "tableName");
        CypherSupport.requireName(indexName, "indexName");

        if (!exists(tableName, indexName)) {
            return false;
        }

        try {
            execute("CALL DROP_VECTOR_INDEX('" + tableName + "', '" + indexName + "')");
            return true;
        } catch (RuntimeException e) {
            throw new VectorIndexException("Failed to drop vector index '" + indexName
                    + "' on table '" + tableName + "'", e);
        }
    }

    /**
     * Rebuilds an HNSW index by dropping and recreating it in one call. This is
     * the documented maintenance operation for write-heavy workloads, where dead
     * HNSW edges accumulate after updates and deletes and degrade search
     * performance over time.
     * <p>
     * Also works when the index does not exist yet, acting as {@link #create}.
     * The drop and the create are two separate engine statements, so the
     * operation is not atomic: if the index is recreated concurrently between
     * the two, the existing index is left in place.
     *
     * @param tableName    name of the node table containing the vector property
     * @param indexName    name of the index to rebuild
     * @param propertyName name of the FLOAT[] property to index
     * @param options      HNSW tuning options for the recreated index
     * @throws VectorIndexException     if the engine rejects an operation
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in an index name
     */
    public void rebuild(String tableName, String indexName, String propertyName, HnswOptions options) {
        drop(tableName, indexName);
        create(tableName, indexName, propertyName, options);
    }

    /**
     * Rebuilds an HNSW index with the default options.
     *
     * @param tableName    name of the node table containing the vector property
     * @param indexName    name of the index to rebuild
     * @param propertyName name of the FLOAT[] property to index
     * @throws VectorIndexException     if the engine rejects an operation
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in an index name
     */
    public void rebuild(String tableName, String indexName, String propertyName) {
        rebuild(tableName, indexName, propertyName, HnswOptions.defaults());
    }

    /**
     * Lists all indexes known to the engine, including core indexes such as the
     * primary key HASH index.
     *
     * @return all index rows of {@code CALL SHOW_INDEXES() RETURN *}
     */
    public List<VectorIndexInfo> list() {
        return template.query(EXTENSIONS, SHOW_INDEXES, Map.of(), INDEX_MAPPER);
    }

    /**
     * Lists only the vector (HNSW) indexes.
     *
     * @return the HNSW index rows of {@code CALL SHOW_INDEXES() RETURN *}
     */
    public List<VectorIndexInfo> listVectorIndexes() {
        return list().stream()
                .filter(VectorIndexInfo::isVectorIndex)
                .toList();
    }

    /**
     * Finds an index by table and index name.
     *
     * @param tableName name of the node table
     * @param indexName name of the index
     * @return the index info, or empty if no such index exists
     */
    public Optional<VectorIndexInfo> find(String tableName, String indexName) {
        return list().stream()
                .filter(index -> index.tableName().equals(tableName) && index.indexName().equals(indexName))
                .findFirst();
    }

    /**
     * Checks whether an index exists on a table.
     *
     * @param tableName name of the node table
     * @param indexName name of the index
     * @return {@code true} if the index exists
     */
    public boolean exists(String tableName, String indexName) {
        return find(tableName, indexName).isPresent();
    }

    /**
     * The CALL statements of the vector extension expand into multiple internal
     * statements, so they cannot go through {@code PreparedStatement}; they are
     * executed directly on the connection instead. The extension is loaded
     * through the template's extension machinery before the callback runs.
     */
    private void execute(String cypher) {
        template.execute(EXTENSIONS, connection -> {
            try (var result = connection.query(cypher)) {
                if (!result.isSuccess()) {
                    throw new VectorIndexException("Query failed: " + cypher + " — " + result.getErrorMessage());
                }
            }
            return null;
        });
    }
}
