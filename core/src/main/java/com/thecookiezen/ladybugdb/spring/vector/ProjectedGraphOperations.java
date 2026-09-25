package com.thecookiezen.ladybugdb.spring.vector;

import com.ladybugdb.Connection;
import com.ladybugdb.LbugList;
import com.ladybugdb.PreparedStatement;
import com.ladybugdb.QueryResult;
import com.ladybugdb.Value;
import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
import com.thecookiezen.ladybugdb.spring.mapper.DefaultQueryRow;
import com.thecookiezen.ladybugdb.spring.mapper.RowMapper;
import com.thecookiezen.ladybugdb.spring.mapper.ValueMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Managed API for projected graphs, exposed by LadybugDB's core engine through
 * {@code PROJECT_GRAPH}, {@code PROJECT_GRAPH_CYPHER}, {@code DROP_PROJECTED_GRAPH}
 * and {@code SHOW_PROJECTED_GRAPHS}, and consumed by the vector extension's
 * {@code QUERY_VECTOR_INDEX} for server-side filtered KNN search.
 * <p>
 * A projection materializes a subgraph (Arrow CSR) selected by per-table Cypher
 * predicates; querying a vector index against it returns only the neighbours
 * that survive the filter, instead of post-filtering results client-side.
 * <p>
 * <strong>Projected graphs live on the connection.</strong> A graph projected
 * outside a Spring transaction is dropped as soon as the template releases the
 * borrowed connection, so the lifecycle methods are only useful across calls
 * inside a transaction. The {@link #search} methods are the safe one-shot
 * alternative: they project, query and clean up on a single connection and work
 * with or without a transaction.
 * <p>
 * Obtain an instance via {@link LadybugDBTemplate#projectedGraphs()} or
 * construct it directly with a template. All operations participate in the
 * surrounding Spring transaction, if any.
 */
public class ProjectedGraphOperations {

    private static final Logger logger = LoggerFactory.getLogger(ProjectedGraphOperations.class);

    /**
     * Name of the LadybugDB vector extension loaded before KNN queries. The
     * projection statements themselves are core engine functions and need no
     * extension.
     */
    public static final String VECTOR_EXTENSION = VectorIndexOperations.VECTOR_EXTENSION;

    /**
     * Name of the ephemeral graph projected by {@link #search} for the duration
     * of a single call.
     */
    public static final String SEARCH_GRAPH_NAME = "sdl_filtered_search";

    private static final String[] EXTENSIONS = {VECTOR_EXTENSION};
    private static final String SHOW_PROJECTED_GRAPHS = "CALL SHOW_PROJECTED_GRAPHS() RETURN *";

    private static final RowMapper<ProjectedGraphInfo> GRAPH_MAPPER = row -> new ProjectedGraphInfo(
            ValueMappers.asString(row.getValue("name")),
            ValueMappers.asString(row.getValue("type")));

    private final LadybugDBTemplate template;

    /**
     * @param template the template used to execute the underlying statements
     */
    public ProjectedGraphOperations(LadybugDBTemplate template) {
        this.template = Objects.requireNonNull(template, "template must not be null");
    }

    /**
     * Projects a subgraph under the given name. If a graph with that name is
     * already projected, nothing is changed and {@code false} is returned —
     * drop the graph first to replace it with a different filter.
     *
     * @param name    name to project the graph under
     * @param filters node and relationship table filters
     * @return {@code true} if the graph was projected, {@code false} if a graph
     *         with that name already existed (idempotent no-op)
     * @throws ProjectedGraphException  if the engine rejects the projection
     *                                  (e.g. unknown table or invalid predicate)
     * @throws IllegalArgumentException if the name is null, blank, or contains
     *                                  characters that cannot appear in a graph name
     */
    public boolean create(String name, ProjectedGraphFilters filters) {
        CypherSupport.requireName(name, "name");
        Objects.requireNonNull(filters, "filters must not be null");

        if (exists(name)) {
            return false;
        }

        String cypher = "CALL PROJECT_GRAPH(" + CypherSupport.literal(name) + ", "
                + filters.toCypherNodeFilters() + ", " + filters.toCypherRelFilters() + ")";
        try {
            execute(cypher);
            return true;
        } catch (RuntimeException e) {
            if (exists(name)) {
                // A concurrent projection won the race — same idempotent outcome.
                return false;
            }
            throw new ProjectedGraphException("Failed to project graph '" + name + "'", e);
        }
    }

    /**
     * Projects a subgraph from an arbitrary Cypher pattern under the given
     * name, mirroring {@code CALL PROJECT_GRAPH_CYPHER(name, cypher)}. If a
     * graph with that name is already projected, nothing is changed and
     * {@code false} is returned.
     *
     * @param name  name to project the graph under
     * @param cypher the {@code MATCH ... RETURN ...} pattern selecting the nodes
     *               and relationships of the projection
     * @return {@code true} if the graph was projected, {@code false} if a graph
     *         with that name already existed (idempotent no-op)
     * @throws ProjectedGraphException  if the engine rejects the projection
     * @throws IllegalArgumentException if the name is null, blank, or contains
     *                                  characters that cannot appear in a graph name,
     *                                  or the Cypher pattern is blank
     */
    public boolean createFromCypher(String name, String cypher) {
        CypherSupport.requireName(name, "name");
        if (cypher == null || cypher.isBlank()) {
            throw new IllegalArgumentException("cypher must not be null or blank");
        }

        if (exists(name)) {
            return false;
        }

        String statement = "CALL PROJECT_GRAPH_CYPHER(" + CypherSupport.literal(name)
                + ", " + CypherSupport.literal(cypher) + ")";
        try {
            execute(statement);
            return true;
        } catch (RuntimeException e) {
            if (exists(name)) {
                return false;
            }
            throw new ProjectedGraphException("Failed to project graph '" + name + "' from Cypher", e);
        }
    }

    /**
     * Drops a projected graph.
     *
     * @param name name of the graph to drop
     * @return {@code true} if the graph was dropped, {@code false} if no graph
     *         with that name existed (tolerant no-op)
     * @throws ProjectedGraphException  if the engine rejects the drop
     * @throws IllegalArgumentException if the name is null, blank, or contains
     *                                  characters that cannot appear in a graph name
     */
    public boolean drop(String name) {
        CypherSupport.requireName(name, "name");

        if (!exists(name)) {
            return false;
        }

        try {
            execute("CALL DROP_PROJECTED_GRAPH(" + CypherSupport.literal(name) + ")");
            return true;
        } catch (RuntimeException e) {
            throw new ProjectedGraphException("Failed to drop projected graph '" + name + "'", e);
        }
    }

    /**
     * Lists all projected graphs of the current connection.
     *
     * @return all rows of {@code CALL SHOW_PROJECTED_GRAPHS() RETURN *}
     */
    public List<ProjectedGraphInfo> list() {
        return template.query(SHOW_PROJECTED_GRAPHS, Map.of(), GRAPH_MAPPER);
    }

    /**
     * Finds a projected graph by name.
     *
     * @param name name of the graph
     * @return the graph info, or empty if no such graph is projected
     */
    public Optional<ProjectedGraphInfo> find(String name) {
        CypherSupport.requireName(name, "name");
        return list().stream()
                .filter(graph -> graph.name().equals(name))
                .findFirst();
    }

    /**
     * Checks whether a graph with the given name is projected.
     *
     * @param name name of the graph
     * @return {@code true} if the graph is projected
     */
    public boolean exists(String name) {
        return find(name).isPresent();
    }

    /**
     * Runs KNN vector search against a projected graph, mirroring
     * {@code CALL QUERY_VECTOR_INDEX(graphName, indexName, queryVector, k)}.
     * Each result row exposes the matched node under the {@code node} column
     * and its similarity as a double under {@code distance}.
     * <p>
     * The graph must exist on the connection the query runs on — outside a
     * transaction it therefore cannot have been created by an earlier call,
     * whose connection is already released; use {@link #search} to project and
     * query in one call instead. Distance-threshold filtering is not supported
     * by the engine; post-filter by score client-side.
     *
     * @param graphName   name of the projected graph to search
     * @param indexName   name of the HNSW vector index (created on the underlying table)
     * @param queryVector the query vector
     * @param k           maximum number of neighbours to return
     * @param rowMapper   mapper receiving the {@code node} and {@code distance} columns
     * @param <T>         the result type
     * @return the nearest neighbours, nearest first
     * @throws ProjectedGraphException  if the graph or index does not exist, or
     *                                  the vector dimension does not match the index
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in a name, the
     *                                  vector is null or empty, or {@code k} is not positive
     */
    public <T> List<T> queryVectorIndex(String graphName, String indexName, float[] queryVector,
            int k, RowMapper<T> rowMapper) {
        return queryVectorIndex(graphName, indexName, queryVector, k, VectorQueryOptions.defaults(),
                rowMapper);
    }

    /**
     * Runs KNN vector search against a projected graph with search tuning, mirroring
     * {@code CALL QUERY_VECTOR_INDEX(graphName, indexName, queryVector, k, ...)}.
     * Each result row exposes the matched node under the {@code node} column
     * and its similarity as a double under {@code distance}.
     *
     * @param graphName   name of the projected graph to search
     * @param indexName   name of the HNSW vector index (created on the underlying table)
     * @param queryVector the query vector
     * @param k           maximum number of neighbours to return
     * @param options     search tuning ({@code efs}, {@code search_type}); use
     *                    {@link VectorQueryOptions#defaults()} for engine defaults
     * @param rowMapper   mapper receiving the {@code node} and {@code distance} columns
     * @param <T>         the result type
     * @return the nearest neighbours, nearest first
     * @throws ProjectedGraphException  if the graph or index does not exist, or
     *                                  the vector dimension does not match the index
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in a name, the
     *                                  vector is null or empty, or {@code k} is not positive
     */
    public <T> List<T> queryVectorIndex(String graphName, String indexName, float[] queryVector,
            int k, VectorQueryOptions options, RowMapper<T> rowMapper) {
        CypherSupport.requireName(graphName, "graphName");
        CypherSupport.requireName(indexName, "indexName");
        requireVector(queryVector);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got: " + k);
        }
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(rowMapper, "rowMapper must not be null");

        String cypher = knnStatement(graphName, indexName, options);
        try {
            return template.query(EXTENSIONS, cypher,
                    Map.of("queryVector", queryVector.clone(), "k", (long) k), rowMapper);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProjectedGraphException("Failed to query vector index '" + indexName
                    + "' on graph '" + graphName + "'", e);
        }
    }

    /**
     * One-shot filtered KNN search: projects a subgraph with the given filters,
     * queries the vector index against it, and drops the projection again —
     * all on a single connection, so it works with or without a surrounding
     * transaction. Repeated searches against the same filter are cheaper when
     * the projection is reused: create the graph and query it from inside a
     * transaction instead.
     * <p>
     * The engine's filtered search supports projections of exactly one node
     * table; {@code filters} must select at least one node table (optionally
     * with additional relationship tables, which must cover every table
     * connected to the filtered node table). Blank predicates select the whole
     * table and are passed to the engine as {@code TRUE}, which the filtered
     * search requires because it cannot compile empty predicates.
     * <p>
     * Distance-threshold filtering is not supported by the engine; post-filter
     * by score client-side.
     *
     * @param indexName   name of the HNSW vector index (created on the underlying table)
     * @param queryVector the query vector
     * @param k           maximum number of neighbours to return
     * @param filters     node and relationship table filters selecting the subgraph
     * @param rowMapper   mapper receiving the {@code node} and {@code distance} columns
     * @param <T>         the result type
     * @return the nearest neighbours of the filtered subgraph, nearest first
     * @throws ProjectedGraphException  if the engine rejects the projection or
     *                                  the index does not exist
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in a name, the
     *                                  vector is null or empty, or {@code k} is not positive
     */
    public <T> List<T> search(String indexName, float[] queryVector, int k,
            ProjectedGraphFilters filters, RowMapper<T> rowMapper) {
        return search(indexName, queryVector, k, filters, VectorQueryOptions.defaults(), rowMapper);
    }

    /**
     * One-shot filtered KNN search with search tuning ({@code efs},
     * {@code search_type}); see {@link #search(String, float[], int,
     * ProjectedGraphFilters, RowMapper)}.
     *
     * @param indexName   name of the HNSW vector index (created on the underlying table)
     * @param queryVector the query vector
     * @param k           maximum number of neighbours to return
     * @param filters     node and relationship table filters selecting the subgraph
     * @param options     search tuning; use {@link VectorQueryOptions#defaults()}
     *                    for engine defaults
     * @param rowMapper   mapper receiving the {@code node} and {@code distance} columns
     * @param <T>         the result type
     * @return the nearest neighbours of the filtered subgraph, nearest first
     * @throws ProjectedGraphException  if the engine rejects the projection or
     *                                  the index does not exist
     * @throws IllegalArgumentException if a name is null, blank, or contains
     *                                  characters that cannot appear in a name, the
     *                                  vector is null or empty, or {@code k} is not positive
     */
    public <T> List<T> search(String indexName, float[] queryVector, int k,
            ProjectedGraphFilters filters, VectorQueryOptions options, RowMapper<T> rowMapper) {
        CypherSupport.requireName(indexName, "indexName");
        requireVector(queryVector);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, got: " + k);
        }
        Objects.requireNonNull(filters, "filters must not be null");
        if (filters.nodeFilters().isEmpty()) {
            // The engine's filtered search rejects projections with zero node
            // tables; surface that before touching the connection.
            throw new IllegalArgumentException(
                    "filters must select at least one node table: QUERY_VECTOR_INDEX over a "
                            + "projected graph supports exactly one node table");
        }
        ProjectedGraphFilters searchable = searchableFilters(filters);
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(rowMapper, "rowMapper must not be null");

        return template.execute(EXTENSIONS, connection -> {
            // Recreate the search graph on this connection to guard against a
            // leftover of the same name, e.g. from a failed earlier search.
            dropOnConnection(connection, SEARCH_GRAPH_NAME);
            createOnConnection(connection, SEARCH_GRAPH_NAME, searchable);
            try {
                return queryVectorIndexOnConnection(connection, SEARCH_GRAPH_NAME, indexName,
                        queryVector, k, options, rowMapper);
            } finally {
                dropQuietlyOnConnection(connection, SEARCH_GRAPH_NAME);
            }
        });
    }

    /**
     * Rewrites blank predicates to {@code TRUE} for the ephemeral search graph:
     * the engine's filtered search compiles each predicate into a
     * {@code MATCH ... WHERE <predicate> RETURN ...} filter query and cannot
     * parse the empty predicate that a blank entry projects.
     */
    private static ProjectedGraphFilters searchableFilters(ProjectedGraphFilters filters) {
        return new ProjectedGraphFilters(
                withMatchAllPredicates(filters.nodeFilters()),
                withMatchAllPredicates(filters.relFilters()));
    }

    private static Map<String, String> withMatchAllPredicates(Map<String, String> filters) {
        Map<String, String> rewritten = new LinkedHashMap<>(filters.size());
        filters.forEach((table, predicate) -> rewritten.put(table,
                predicate == null || predicate.isBlank() ? "TRUE" : predicate));
        return rewritten;
    }

    /**
     * The projection CALL statements expand into multiple internal statements,
     * so they cannot go through {@code PreparedStatement}; they are executed
     * directly on the connection instead. No extension is needed: projections
     * are a core engine feature.
     */
    private void execute(String cypher) {
        template.execute(connection -> {
            executeOnConnection(connection, cypher);
            return null;
        });
    }

    private static void executeOnConnection(Connection connection, String cypher) {
        try (QueryResult result = connection.query(cypher)) {
            if (!result.isSuccess()) {
                throw new ProjectedGraphException("Query failed: " + cypher
                        + " — " + result.getErrorMessage());
            }
        }
    }

    /**
     * Lists the projected graphs of the given connection. Everything inside
     * {@link #search} must use the connection variants of the lifecycle
     * operations: projected graphs live on the connection, and template-level
     * calls outside a transaction would land on a different one.
     */
    private static List<ProjectedGraphInfo> listOnConnection(Connection connection) {
        try (QueryResult result = connection.query(SHOW_PROJECTED_GRAPHS)) {
            if (!result.isSuccess()) {
                throw new ProjectedGraphException("Query failed: " + SHOW_PROJECTED_GRAPHS
                        + " — " + result.getErrorMessage());
            }
            return mapRows(result, GRAPH_MAPPER);
        }
    }

    private static boolean existsOnConnection(Connection connection, String name) {
        return listOnConnection(connection).stream()
                .anyMatch(graph -> graph.name().equals(name));
    }

    private static void dropOnConnection(Connection connection, String name) {
        if (existsOnConnection(connection, name)) {
            executeOnConnection(connection,
                    "CALL DROP_PROJECTED_GRAPH(" + CypherSupport.literal(name) + ")");
        }
    }

    /**
     * Cleanup variant of {@link #dropOnConnection} used after a successful
     * search: a failed drop must not discard the query results.
     */
    private static void dropQuietlyOnConnection(Connection connection, String name) {
        try {
            dropOnConnection(connection, name);
        } catch (RuntimeException e) {
            logger.warn("Failed to drop ephemeral projected graph '" + name + "'", e);
        }
    }

    private static void createOnConnection(Connection connection, String name,
            ProjectedGraphFilters filters) {
        executeOnConnection(connection, "CALL PROJECT_GRAPH("
                + CypherSupport.literal(name) + ", "
                + filters.toCypherNodeFilters() + ", " + filters.toCypherRelFilters() + ")");
    }

    /**
     * Builds the shared shape of both KNN entry points:
     * {@code CALL QUERY_VECTOR_INDEX(graph, index, $queryVector, $k ...)},
     * returning the {@code node} and {@code distance} columns of each row.
     */
    private static String knnStatement(String graphName, String indexName,
            VectorQueryOptions options) {
        return "CALL QUERY_VECTOR_INDEX(" + CypherSupport.literal(graphName) + ", "
                + CypherSupport.literal(indexName) + ", $queryVector, $k"
                + options.toCypherOptions() + ") RETURN node, distance";
    }

    /**
     * Runs the KNN query on the given connection and maps the {@code node} and
     * {@code distance} columns of each row. The vector extension must already
     * be loaded on the connection.
     */
    @SuppressWarnings("resource")
    private static <T> List<T> queryVectorIndexOnConnection(Connection connection, String graphName,
            String indexName, float[] queryVector, int k, VectorQueryOptions options,
            RowMapper<T> rowMapper) {
        String cypher = knnStatement(graphName, indexName, options);

        Map<String, Value> parameters = new HashMap<>(2);
        parameters.put("queryVector", toVectorValue(queryVector));
        parameters.put("k", new Value((long) k));
        try (PreparedStatement statement = connection.prepare(cypher);
                QueryResult result = connection.execute(statement, parameters)) {
            if (!result.isSuccess()) {
                throw new ProjectedGraphException("Query failed: " + cypher
                        + " — " + result.getErrorMessage());
            }
            return mapRows(result, rowMapper);
        } finally {
            parameters.values().forEach(ProjectedGraphOperations::closeQuietly);
        }
    }

    private static <T> List<T> mapRows(QueryResult result, RowMapper<T> rowMapper) {
        int numColumns = (int) result.getNumColumns();
        Map<String, Integer> columnToIndex = new HashMap<>(numColumns);
        for (int i = 0; i < numColumns; i++) {
            columnToIndex.put(result.getColumnName(i), i);
        }

        DefaultQueryRow row = new DefaultQueryRow(columnToIndex);
        List<T> results = new ArrayList<>();
        while (result.hasNext()) {
            try (row) {
                row.bind(result.getNext());
                results.add(rowMapper.mapRow(row));
            } catch (Exception e) {
                throw new LadybugDBTemplate.CypherMappingException("Error mapping row", e);
            }
        }
        return results;
    }

    /**
     * Converts a query vector into a LIST value of FLOAT elements, matching the
     * {@code FLOAT[n]} type the engine expects for the {@code queryVector}
     * parameter.
     */
    private static Value toVectorValue(float[] vector) {
        Value[] values = new Value[vector.length];
        try {
            for (int i = 0; i < vector.length; i++) {
                values[i] = new Value(vector[i]);
            }
            return new LbugList(values).getValue();
        } finally {
            for (Value value : values) {
                if (value != null) {
                    closeQuietly(value);
                }
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.debug("Error closing resource", e);
            }
        }
    }

    private static void requireVector(float[] queryVector) {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be null or empty");
        }
    }
}
