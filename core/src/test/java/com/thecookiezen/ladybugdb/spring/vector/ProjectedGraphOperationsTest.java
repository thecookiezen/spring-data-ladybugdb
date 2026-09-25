package com.thecookiezen.ladybugdb.spring.vector;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.thecookiezen.ladybugdb.spring.connection.SimpleConnectionFactory;
import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
import com.thecookiezen.ladybugdb.spring.mapper.RowMapper;
import com.thecookiezen.ladybugdb.spring.mapper.ValueMappers;
import com.thecookiezen.ladybugdb.spring.repository.support.EntityRegistry;
import com.thecookiezen.ladybugdb.spring.transaction.LadybugDBTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link ProjectedGraphOperations} running against the
 * embedded LadybugDB 0.20.4 engine with the vector extension installed.
 * <p>
 * Projected graphs live on the connection, so lifecycle scenarios run inside a
 * transaction to keep every call on the transaction-bound connection. The
 * {@code search} scenarios cover the path without a transaction.
 * <p>
 * The tests are skipped when the vector extension cannot be installed
 * (e.g. in an offline environment).
 */
class ProjectedGraphOperationsTest {

    private static Database db;
    private static SimpleConnectionFactory connectionFactory;
    private static LadybugDBTemplate template;
    private static ProjectedGraphOperations projectedGraphs;
    private static VectorIndexOperations vectorIndexes;
    private static TransactionTemplate transactions;

    private final List<String> createdTables = new ArrayList<>();

    @BeforeAll
    static void setupAll() {
        db = new Database(":memory:");
        connectionFactory = new SimpleConnectionFactory(db);
        template = new LadybugDBTemplate(connectionFactory, new EntityRegistry());
        projectedGraphs = new ProjectedGraphOperations(template);
        vectorIndexes = new VectorIndexOperations(template);
        transactions = new TransactionTemplate(new LadybugDBTransactionManager(connectionFactory));

        try (Connection conn = new Connection(db)) {
            try (var result = conn.query("INSTALL vector")) {
                assumeTrue(result.isSuccess(),
                        "vector extension could not be installed (offline environment?): "
                                + result.getErrorMessage());
            }
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (connectionFactory != null) {
            connectionFactory.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setup() {
        createTable("PG_BOOKS");
        template.execute("CREATE (:PG_BOOKS {id: 'b1', published_year: 1995, embedding: [0.1, 0.1, 0.1, 0.1]})");
        template.execute("CREATE (:PG_BOOKS {id: 'b2', published_year: 2020, embedding: [0.9, 0.9, 0.1, 0.1]})");
        template.execute("CREATE (:PG_BOOKS {id: 'b3', published_year: 2021, embedding: [0.8, 0.85, 0.12, 0.1]})");
        vectorIndexes.create("PG_BOOKS", "books_emb", "embedding");
    }

    @AfterEach
    void tearDown() {
        for (VectorIndexInfo index : vectorIndexes.listVectorIndexes()) {
            if (createdTables.contains(index.tableName())) {
                vectorIndexes.drop(index.tableName(), index.indexName());
            }
        }
        for (String table : createdTables) {
            template.execute("DROP TABLE " + table);
        }
        createdTables.clear();
    }

    private void createTable(String tableName) {
        template.execute("CREATE NODE TABLE " + tableName
                + "(id STRING PRIMARY KEY, published_year INT64, embedding FLOAT[4])");
        createdTables.add(tableName);
    }

    private <T> T inTx(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private static RowMapper<KnnHit> hitMapper = row -> new KnnHit(
            ValueMappers.asString(row.getNode("node").get("id")),
            ValueMappers.asDouble(row.getValue("distance")));

    record KnnHit(String id, Double distance) {
    }

    @Test
    void create_shouldProjectGraphWithNodeFilter() {
        inTx(() -> {
            assertTrue(projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010"))));

            Optional<ProjectedGraphInfo> graph = projectedGraphs.find("recent");
            assertTrue(graph.isPresent());
            assertEquals("recent", graph.get().name());
            assertEquals("NATIVE", graph.get().type());
            assertTrue(graph.get().isNative());
            return null;
        });
    }

    @Test
    void create_shouldProjectGraphFromTableNames() {
        inTx(() -> {
            assertTrue(projectedGraphs.create("all_books", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", ""))));

            assertTrue(projectedGraphs.exists("all_books"));
            return null;
        });
    }

    @Test
    void create_shouldRejectInvalidNames() {
        assertThrows(IllegalArgumentException.class,
                () -> projectedGraphs.create("bad'name", ProjectedGraphFilters.none()));
        assertThrows(IllegalArgumentException.class,
                () -> projectedGraphs.create("bad;name", ProjectedGraphFilters.none()));
        assertThrows(IllegalArgumentException.class,
                () -> projectedGraphs.create(" ", ProjectedGraphFilters.none()));
        assertThrows(IllegalArgumentException.class,
                () -> projectedGraphs.create(null, ProjectedGraphFilters.none()));
    }

    @Test
    void create_shouldBeIdempotent() {
        inTx(() -> {
            assertTrue(projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010"))));
            // Existing graph is left in place; a differently filtered
            // re-projection is rejected rather than silently ignored.
            assertFalse(projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year < 2000"))));

            assertEquals(1, projectedGraphs.list().size());
            return null;
        });
    }

    @Test
    void createFromCypher_shouldProjectGraphFromMatchQuery() {
        inTx(() -> {
            assertTrue(projectedGraphs.createFromCypher("pearson",
                    "MATCH (n:PG_BOOKS) WHERE n.published_year > 2010 RETURN n"));

            Optional<ProjectedGraphInfo> graph = projectedGraphs.find("pearson");
            assertTrue(graph.isPresent());
            assertEquals("CYPHER", graph.get().type());
            assertTrue(graph.get().isCypher());
            return null;
        });
    }

    @Test
    void createFromCypher_shouldAcceptQueriesWithQuotedLiterals() {
        inTx(() -> {
            assertTrue(projectedGraphs.createFromCypher("titled",
                    "MATCH (n:PG_BOOKS {id: 'b2'}) RETURN n"));

            assertTrue(projectedGraphs.exists("titled"));
            return null;
        });
    }

    @Test
    void createFromCypher_shouldBeIdempotent() {
        inTx(() -> {
            assertTrue(projectedGraphs.createFromCypher("pearson",
                    "MATCH (n:PG_BOOKS) RETURN n"));
            assertFalse(projectedGraphs.createFromCypher("pearson",
                    "MATCH (n:PG_BOOKS) WHERE n.published_year > 2010 RETURN n"));

            assertEquals(1, projectedGraphs.list().size());
            return null;
        });
    }

    @Test
    void drop_shouldDropExistingGraph() {
        inTx(() -> {
            projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010")));

            assertTrue(projectedGraphs.drop("recent"));
            assertTrue(projectedGraphs.find("recent").isEmpty());
            return null;
        });
    }

    @Test
    void drop_shouldReturnFalseForMissingGraph() {
        assertFalse(projectedGraphs.drop("never_projected"));
    }

    @Test
    void list_shouldReportProjectedGraphs() {
        inTx(() -> {
            projectedGraphs.create("native_g", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010")));
            projectedGraphs.createFromCypher("cypher_g", "MATCH (n:PG_BOOKS) RETURN n");

            List<ProjectedGraphInfo> graphs = projectedGraphs.list();

            assertEquals(2, graphs.size());
            assertTrue(graphs.stream().anyMatch(g -> "native_g".equals(g.name()) && g.isNative()));
            assertTrue(graphs.stream().anyMatch(g -> "cypher_g".equals(g.name()) && g.isCypher()));
            return null;
        });
    }

    @Test
    void exists_shouldReflectGraphPresence() {
        inTx(() -> {
            assertFalse(projectedGraphs.exists("recent"));

            projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010")));

            assertTrue(projectedGraphs.exists("recent"));
            assertFalse(projectedGraphs.exists("other"));
            return null;
        });
    }

    @Test
    void queryVectorIndex_shouldReturnOnlyNodesMatchingFilter() {
        List<KnnHit> hits = inTx(() -> {
            projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010")));

            return projectedGraphs.queryVectorIndex("recent", "books_emb",
                    new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 1, hitMapper);
        });

        // Unfiltered KNN with k=1 would return b1 (an exact match); the
        // projection excludes it, so the nearest remaining node is returned.
        assertEquals(1, hits.size());
        assertNotEquals("b1", hits.get(0).id());
        assertTrue(hits.get(0).distance() >= 0.0);
    }

    @Test
    void queryVectorIndex_shouldPassThroughSearchOptions() {
        List<KnnHit> hits = inTx(() -> {
            projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010")));

            return projectedGraphs.queryVectorIndex("recent", "books_emb",
                    new float[] {0.85f, 0.9f, 0.1f, 0.1f}, 2,
                    VectorQueryOptions.builder()
                            .efs(100)
                            .searchType(VectorQueryOptions.SearchType.NAVIX)
                            .build(),
                    hitMapper);
        });

        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(h -> !h.id().equals("b1")));
    }

    @Test
    void queryVectorIndex_shouldFailForMissingGraph() {
        assertThrows(ProjectedGraphException.class,
                () -> projectedGraphs.queryVectorIndex("no_such_graph", "books_emb",
                        new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 1, hitMapper));
    }

    @Test
    void search_shouldProjectAndQueryInOneShot() {
        List<KnnHit> hits = projectedGraphs.search("books_emb",
                new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 1,
                ProjectedGraphFilters.nodes(Map.of("PG_BOOKS", "n.published_year > 2010")),
                hitMapper);

        assertEquals(1, hits.size());
        assertNotEquals("b1", hits.get(0).id());
        // The ephemeral search graph is dropped afterwards.
        assertTrue(projectedGraphs.list().isEmpty());
    }

    @Test
    void search_shouldAcceptSearchOptions() {
        List<KnnHit> hits = projectedGraphs.search("books_emb",
                new float[] {0.85f, 0.9f, 0.1f, 0.1f}, 2,
                ProjectedGraphFilters.nodes(Map.of("PG_BOOKS", "n.published_year > 2010")),
                VectorQueryOptions.builder().efs(100).build(),
                hitMapper);

        assertEquals(2, hits.size());
        assertTrue(projectedGraphs.list().isEmpty());
    }

    @Test
    void search_shouldWorkWithoutPredicates() {
        // Filtering by table membership only: the whole table is projected.
        List<KnnHit> hits = projectedGraphs.search("books_emb",
                new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 1,
                ProjectedGraphFilters.nodes(Map.of("PG_BOOKS", "")), hitMapper);

        // Without predicates the exact match wins.
        assertEquals(1, hits.size());
        assertEquals("b1", hits.get(0).id());
    }

    @Test
    void search_shouldRejectFiltersWithoutNodeTables() {
        // The engine's filtered KNN needs exactly one node table in the projection.
        assertThrows(IllegalArgumentException.class,
                () -> projectedGraphs.search("books_emb", new float[] {0.1f, 0.1f, 0.1f, 0.1f},
                        1, ProjectedGraphFilters.none(), hitMapper));
    }

    @Test
    void search_shouldSupportTransactions() {
        // Inside a transaction the search participates on the transaction-bound
        // connection and cleans up its ephemeral graph.
        List<KnnHit> hits = inTx(() -> {
            List<KnnHit> result = projectedGraphs.search("books_emb",
                    new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 1,
                    ProjectedGraphFilters.nodes(Map.of("PG_BOOKS", "n.published_year > 2010")),
                    hitMapper);

            // The ephemeral search graph was dropped on the same connection.
            assertFalse(projectedGraphs.exists(ProjectedGraphOperations.SEARCH_GRAPH_NAME));
            return result;
        });

        assertEquals(1, hits.size());
        assertNotEquals("b1", hits.get(0).id());
    }

    @Test
    void operations_shouldSupportTransactions() {
        // Projected graphs live on the connection, so the lifecycle and the
        // KNN query must run on the transaction-bound connection.
        List<KnnHit> hits = transactions.execute(status -> {
            assertTrue(projectedGraphs.create("recent", ProjectedGraphFilters.nodes(
                    Map.of("PG_BOOKS", "n.published_year > 2010"))));
            return projectedGraphs.queryVectorIndex("recent", "books_emb",
                    new float[] {0.85f, 0.9f, 0.1f, 0.1f}, 1, hitMapper);
        });

        assertEquals(1, hits.size());
        assertNotEquals("b1", hits.get(0).id());
    }

    @Test
    void template_shouldExposeProjectedGraphOperations() {
        assertSame(template.projectedGraphs(), template.projectedGraphs());
    }
}
