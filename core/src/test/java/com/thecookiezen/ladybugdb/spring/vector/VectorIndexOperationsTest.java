package com.thecookiezen.ladybugdb.spring.vector;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.thecookiezen.ladybugdb.spring.connection.SimpleConnectionFactory;
import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link VectorIndexOperations} running against the
 * embedded LadybugDB 0.20.4 engine with the vector extension installed.
 * <p>
 * The tests are skipped when the vector extension cannot be installed
 * (e.g. in an offline environment).
 */
class VectorIndexOperationsTest {

    private static Database db;
    private static SimpleConnectionFactory connectionFactory;
    private static LadybugDBTemplate template;
    private static VectorIndexOperations vectorIndexes;

    private final List<String> createdTables = new ArrayList<>();

    @BeforeAll
    static void setupAll() {
        db = new Database(":memory:");
        connectionFactory = new SimpleConnectionFactory(db);
        template = new LadybugDBTemplate(connectionFactory, new EntityRegistry());
        vectorIndexes = new VectorIndexOperations(template);

        try (Connection conn = new Connection(db)) {
            try (var result = conn.query("INSTALL vector")) {
                assumeTrue(result.isSuccess(),
                        "vector extension could not be installed (offline environment?): " + result.getErrorMessage());
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
        createTable("VI_DOCS");
    }

    @AfterEach
    void tearDown() {
        // LadybugDB refuses to drop a table that is still referenced by an
        // index, so drop any leftover vector indexes first.
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
                + "(id STRING PRIMARY KEY, embedding FLOAT[4])");
        createdTables.add(tableName);
    }

    @Test
    void create_shouldCreateIndexWithDefaults() {
        assertTrue(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding"));

        Optional<VectorIndexInfo> index = vectorIndexes.find("VI_DOCS", "docs_emb");
        assertTrue(index.isPresent());
        assertEquals("VI_DOCS", index.get().tableName());
        assertEquals("docs_emb", index.get().indexName());
        assertEquals("HNSW", index.get().indexType());
        assertEquals(List.of("embedding"), index.get().propertyNames());
        assertTrue(index.get().extensionLoaded());
        assertTrue(index.get().indexDefinition().contains("metric := 'cosine'"));
    }

    @Test
    void create_shouldBeIdempotent() {
        assertTrue(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding"));
        assertFalse(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding"));

        assertEquals(1, vectorIndexes.listVectorIndexes().size());
    }

    @Test
    void create_shouldReturnFalseWhenIndexExistsWithDifferentOptions() {
        assertTrue(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding",
                HnswOptions.builder().mu(30).build()));
        // Existing index is left untouched, no exception is thrown.
        assertFalse(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding",
                HnswOptions.builder().mu(44).build()));

        assertTrue(vectorIndexes.find("VI_DOCS", "docs_emb").orElseThrow()
                .indexDefinition().contains("mu := 30"));
    }

    @Test
    void create_shouldApplyCustomOptions() {
        assertTrue(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding",
                HnswOptions.builder()
                        .metric(HnswOptions.Metric.L2)
                        .mu(25)
                        .ml(50)
                        .pu(0.1)
                        .efc(150)
                        .cacheEmbeddings(false)
                        .build()));

        String definition = vectorIndexes.find("VI_DOCS", "docs_emb").orElseThrow().indexDefinition();
        assertTrue(definition.contains("mu := 25"));
        assertTrue(definition.contains("ml := 50"));
        assertTrue(definition.contains("pu := 0.1"));
        assertTrue(definition.contains("metric := 'l2'"));
        assertTrue(definition.contains("efc := 150"));
        assertTrue(definition.contains("cache_embeddings := false"));
    }

    @Test
    void create_shouldRejectInvalidNames() {
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("VI_DOCS", "bad'name", "embedding"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("VI_DOCS", "bad;name", "embedding"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("VI_DOCS", "bad\\name", "embedding"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("bad'table", "idx", "embedding"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("VI_DOCS", "idx", "bad'prop"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create(" ", "idx", "embedding"));
        assertThrows(IllegalArgumentException.class,
                () -> vectorIndexes.create("VI_DOCS", null, "embedding"));
    }

    @Test
    void drop_shouldDropExistingIndex() {
        vectorIndexes.create("VI_DOCS", "docs_emb", "embedding");

        assertTrue(vectorIndexes.drop("VI_DOCS", "docs_emb"));
        assertTrue(vectorIndexes.find("VI_DOCS", "docs_emb").isEmpty());
    }

    @Test
    void drop_shouldReturnFalseForMissingIndex() {
        assertFalse(vectorIndexes.drop("VI_DOCS", "never_created"));
    }

    @Test
    void rebuild_shouldDropAndRecreateIndex() {
        vectorIndexes.create("VI_DOCS", "docs_emb", "embedding",
                HnswOptions.builder().efc(150).build());

        vectorIndexes.rebuild("VI_DOCS", "docs_emb", "embedding",
                HnswOptions.builder().efc(180).metric(HnswOptions.Metric.L2).build());

        Optional<VectorIndexInfo> index = vectorIndexes.find("VI_DOCS", "docs_emb");
        assertTrue(index.isPresent());
        assertTrue(index.get().indexDefinition().contains("efc := 180"));
        assertTrue(index.get().indexDefinition().contains("metric := 'l2'"));
    }

    @Test
    void rebuild_shouldWorkWhenIndexDoesNotExist() {
        vectorIndexes.rebuild("VI_DOCS", "docs_emb", "embedding");

        assertTrue(vectorIndexes.exists("VI_DOCS", "docs_emb"));
    }

    @Test
    void list_shouldReturnAllIndexesIncludingPrimaryKey() {
        vectorIndexes.create("VI_DOCS", "docs_emb", "embedding");

        List<VectorIndexInfo> all = vectorIndexes.list();

        // The primary key HASH index is always reported by SHOW_INDEXES().
        Optional<VectorIndexInfo> pk = all.stream()
                .filter(i -> "_PK".equals(i.indexName()))
                .findFirst();
        assertTrue(pk.isPresent());
        assertEquals("HASH", pk.get().indexType());
        assertEquals(List.of("id"), pk.get().propertyNames());
        assertTrue(all.stream().anyMatch(i -> "docs_emb".equals(i.indexName())));
    }

    @Test
    void listVectorIndexes_shouldReturnOnlyHnswIndexes() {
        createTable("VI_OTHER");
        vectorIndexes.create("VI_DOCS", "docs_emb", "embedding");
        vectorIndexes.create("VI_OTHER", "other_emb", "embedding");

        List<VectorIndexInfo> vectorOnly = vectorIndexes.listVectorIndexes();

        assertEquals(2, vectorOnly.size());
        assertTrue(vectorOnly.stream().allMatch(VectorIndexInfo::isVectorIndex));
        assertTrue(vectorOnly.stream().noneMatch(i -> "_PK".equals(i.indexName())));
    }

    @Test
    void exists_shouldReflectIndexPresence() {
        assertFalse(vectorIndexes.exists("VI_DOCS", "docs_emb"));

        vectorIndexes.create("VI_DOCS", "docs_emb", "embedding");

        assertTrue(vectorIndexes.exists("VI_DOCS", "docs_emb"));
        assertFalse(vectorIndexes.exists("VI_OTHER_TABLE", "docs_emb"));
    }

    @Test
    void template_shouldExposeVectorIndexOperations() {
        assertTrue(template.vectorIndexes().create("VI_DOCS", "docs_emb", "embedding"));
        assertTrue(template.vectorIndexes().exists("VI_DOCS", "docs_emb"));
        assertTrue(template.vectorIndexes().drop("VI_DOCS", "docs_emb"));

        assertSame(template.vectorIndexes(), template.vectorIndexes());
    }

    @Test
    void operations_shouldSupportTransactions() {
        // All operations must run on the transaction-bound connection without
        // leaving it open after completion.
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new LadybugDBTransactionManager(connectionFactory));

        transactionTemplate.executeWithoutResult(status ->
                assertTrue(vectorIndexes.create("VI_DOCS", "docs_emb", "embedding")));

        assertTrue(vectorIndexes.exists("VI_DOCS", "docs_emb"));
    }
}
