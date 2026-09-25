package com.thecookiezen.ladybugdb.spring.vector;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.thecookiezen.ladybugdb.spring.annotation.NodeEntity;
import com.thecookiezen.ladybugdb.spring.annotation.RelationshipEntity;
import com.thecookiezen.ladybugdb.spring.connection.SimpleConnectionFactory;
import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
import com.thecookiezen.ladybugdb.spring.mapper.EntityWriter;
import com.thecookiezen.ladybugdb.spring.mapper.RowMapper;
import com.thecookiezen.ladybugdb.spring.mapper.ValueMappers;
import com.thecookiezen.ladybugdb.spring.repository.support.EntityRegistry;
import com.thecookiezen.ladybugdb.spring.repository.support.SimpleNodeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.annotation.Id;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for save-time updates of indexed vector properties.
 * <p>
 * An entity with a {@code float[]} property is stored in a table with a
 * live HNSW index ({@code FLOAT[4]} column). Saving an existing entity must
 * emit a {@code SET} update — supported by LadybugDB >= 0.18.0 (update as
 * delete+insert inside the index with MVCC visibility) — and the new vector
 * must immediately be visible to KNN search, survive checkpoint/reopen, and
 * never surface stale rows after repeated updates.
 * <p>
 * The tests are skipped when the vector extension cannot be installed
 * (e.g. in an offline environment).
 */
class VectorPropertyUpdateTest {

    private static final int K = 3;

    @TempDir
    static Path tempDir;

    private static Database db;
    private static SimpleConnectionFactory connectionFactory;
    private static EntityRegistry registry;
    private static LadybugDBTemplate template;
    private static SimpleNodeRepository<Doc, Rel, String> repository;
    private static SimpleNodeRepository<ListDoc, Rel, String> listRepository;
    private static SimpleNodeRepository<BoxedDoc, Rel, String> boxedRepository;

    @BeforeAll
    static void setupAll() {
        openDatabase();

        try (Connection conn = new Connection(db)) {
            try (var result = conn.query("INSTALL vector")) {
                assumeTrue(result.isSuccess(),
                        "vector extension could not be installed (offline environment?): "
                                + result.getErrorMessage());
            }
            conn.query("CREATE NODE TABLE DOC(id STRING PRIMARY KEY, embedding FLOAT[4])");
            conn.query("CREATE REL TABLE DOC_REL(FROM DOC TO DOC, id STRING)");
        }

        template.vectorIndexes().create("DOC", "doc_emb", "embedding");
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

    @AfterEach
    void tearDown() {
        template.execute("MATCH (n:DOC) DETACH DELETE n");
    }

    /**
     * (Re)opens the database and rebuilds the factory, template and
     * repositories on top of it. Used by {@code setupAll} and by the
     * checkpoint/reopen test.
     */
    private static void openDatabase() {
        db = new Database(tempDir.resolve("db").toString());
        connectionFactory = new SimpleConnectionFactory(db);

        registry = new EntityRegistry();
        registry.registerDescriptor(Doc.class, docReader, docWriter);
        registry.registerDescriptor(ListDoc.class, listDocReader, listDocWriter);
        registry.registerDescriptor(BoxedDoc.class, boxedDocReader, boxedDocWriter);
        registry.registerDescriptor(Rel.class, relReader, relWriter);

        template = new LadybugDBTemplate(connectionFactory, registry);
        repository = new SimpleNodeRepository<>(template, Doc.class, Rel.class,
                registry.getDescriptor(Doc.class), registry.getDescriptor(Rel.class));
        listRepository = new SimpleNodeRepository<>(template, ListDoc.class, Rel.class,
                registry.getDescriptor(ListDoc.class), registry.getDescriptor(Rel.class));
        boxedRepository = new SimpleNodeRepository<>(template, BoxedDoc.class, Rel.class,
                registry.getDescriptor(BoxedDoc.class), registry.getDescriptor(Rel.class));
    }

    @Test
    void save_shouldRoundTripFloatArrayProperty() {
        Doc doc = new Doc("a", new float[] {1.0f, 0.0f, 0.0f, 0.0f});

        Doc saved = repository.save(doc);

        assertNotNull(saved);
        assertEquals("a", saved.id);
        assertArrayEquals(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, saved.embedding, 0.0001f);

        Optional<Doc> found = repository.findById("a");
        assertTrue(found.isPresent());
        assertArrayEquals(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, found.get().embedding, 0.0001f);
    }

    @Test
    void save_shouldRoundTripListAndBoxedFloatProperties() {
        listRepository.save(new ListDoc("list-1", List.of(0.1f, 0.2f, 0.3f, 0.4f)));

        Optional<ListDoc> foundList = listRepository.findById("list-1");
        assertTrue(foundList.isPresent());
        assertEquals(List.of(0.1f, 0.2f, 0.3f, 0.4f), foundList.get().embedding);

        boxedRepository.save(new BoxedDoc("boxed-1", new Float[] {0.5f, 0.6f, 0.7f, 0.8f}));

        Optional<BoxedDoc> foundBoxed = boxedRepository.findById("boxed-1");
        assertTrue(foundBoxed.isPresent());
        assertArrayEquals(new Float[] {0.5f, 0.6f, 0.7f, 0.8f}, foundBoxed.get().embedding);
    }

    @Test
    void save_shouldRejectVectorNotMatchingSchemaDimension() {
        // The FLOAT[4] column enforces the dimension; the mapping layer adds
        // no dimension validation of its own.
        Doc doc = new Doc("dim-mismatch", new float[] {0.1f, 0.2f, 0.3f});

        RuntimeException e = assertThrows(RuntimeException.class, () -> repository.save(doc));

        assertTrue(errorMessages(e).contains("Expected: 4, Actual: 3"),
                "dimension mismatch should be rejected by the engine: " + errorMessages(e));
    }

    @Test
    void save_shouldUpdateIndexedVectorViaSet() {
        repository.save(new Doc("a", new float[] {1.0f, 0.0f, 0.0f, 0.0f}));
        repository.save(new Doc("b", new float[] {0.0f, 1.0f, 0.0f, 0.0f}));

        // The update must SET the vector on the existing node, not
        // delete+recreate it, so the node keeps its internal identity.
        String internalIdBefore = knn(new float[] {0.0f, 1.0f, 0.0f, 0.0f}, 2).stream()
                .filter(h -> "b".equals(h.id()))
                .findFirst()
                .orElseThrow()
                .internalId();

        Doc b = repository.findById("b").orElseThrow();
        b.embedding = new float[] {0.9f, 0.1f, 0.0f, 0.0f};
        repository.save(b);

        // KNN around a's position: b must now sit right next to a. At its old
        // position the cosine distance would be 1.0, so a small distance proves
        // the index saw the update.
        List<Hit> hits = knn(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, 2);

        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals("b", hits.get(1).id());
        assertTrue(hits.get(1).distance() < 0.1f,
                "b should be near a after the update but had distance " + hits.get(1).distance());
        assertEquals(internalIdBefore, hits.get(1).internalId(),
                "the updated node must keep its internal identity (SET in place, not recreated)");
    }

    @Test
    void save_shouldSupportRepeatedUpdatesWithoutStaleRows() {
        repository.save(new Doc("a", new float[] {1.0f, 0.0f, 0.0f, 0.0f}));
        repository.save(new Doc("b", new float[] {0.0f, 1.0f, 0.0f, 0.0f}));
        repository.save(new Doc("c", new float[] {0.0f, 0.0f, 1.0f, 0.0f}));

        Doc b = repository.findById("b").orElseThrow();
        b.embedding = new float[] {0.9f, 0.1f, 0.0f, 0.0f};
        repository.save(b);
        b.embedding = new float[] {0.5f, 0.5f, 0.0f, 0.0f};
        repository.save(b);
        b.embedding = new float[] {0.0f, 0.3f, 0.7f, 0.0f};
        repository.save(b);

        // With three nodes and k=3 every row is returned exactly once. A stale
        // row from an old version of b would show up as a duplicate id.
        List<Hit> hits = knn(new float[] {0.0f, 0.3f, 0.7f, 0.0f}, K);

        assertEquals(K, hits.size());
        assertEquals(K, hits.stream().map(Hit::id).distinct().count());
        assertTrue(hits.stream().allMatch(h -> List.of("a", "b", "c").contains(h.id())));
        assertEquals("b", hits.get(0).id());
        assertTrue(hits.get(0).distance() < 0.01f,
                "b should be found at its final position but had distance " + hits.get(0).distance());
    }

    @Test
    void save_shouldUpdateIndexedVectorAcrossCheckpointAndReopen() {
        repository.save(new Doc("a", new float[] {1.0f, 0.0f, 0.0f, 0.0f}));
        repository.save(new Doc("b", new float[] {0.0f, 1.0f, 0.0f, 0.0f}));

        Doc b = repository.findById("b").orElseThrow();
        b.embedding = new float[] {0.9f, 0.1f, 0.0f, 0.0f};
        repository.save(b);

        template.execute("CHECKPOINT");
        connectionFactory.close();
        db.close();

        openDatabase();
        List<Hit> hits = knn(new float[] {1.0f, 0.0f, 0.0f, 0.0f}, 2);

        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals("b", hits.get(1).id());
        assertTrue(hits.get(1).distance() < 0.1f,
                "b's update should survive checkpoint/reopen but had distance " + hits.get(1).distance());

        Optional<Doc> found = repository.findById("b");
        assertTrue(found.isPresent());
        assertArrayEquals(new float[] {0.9f, 0.1f, 0.0f, 0.0f}, found.get().embedding, 0.0001f);
    }

    /**
     * A KNN hit: the node's business id, its internal id (stable across
     * in-place updates, changed by delete+recreate) and the distance.
     */
    private record Hit(String id, String internalId, float distance) {
    }

    /**
     * Runs a KNN query against the vector index. Loads the vector extension on
     * the connection and binds the query vector as a parameter.
     * <p>
     * The engine returns the top-k rows in HNSW traversal order rather than by
     * distance, so the hits are sorted by distance here.
     */
    private static List<Hit> knn(float[] query, int k) {
        List<Hit> hits = template.query(
                new String[] {"vector"},
                "CALL QUERY_VECTOR_INDEX('DOC', 'doc_emb', $q, " + k + ") RETURN node, distance",
                Map.of("q", query),
                row -> {
                    var node = row.getNode("node");
                    return new Hit(
                            ValueMappers.asString(node.get("id")),
                            node.get("_ID").toString(),
                            ValueMappers.asDouble(row.getValue("distance")).floatValue());
                });
        return hits.stream().sorted(Comparator.comparingDouble(Hit::distance)).toList();
    }

    /**
     * Collects the messages of the full exception chain so engine errors can
     * be asserted without depending on where the library wraps them.
     */
    private static String errorMessages(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append(' ');
        }
        return messages.toString();
    }

    @NodeEntity(label = "DOC")
    static class Doc {
        @Id
        String id;
        float[] embedding;

        Doc() {
        }

        Doc(String id, float[] embedding) {
            this.id = id;
            this.embedding = embedding;
        }
    }

    @NodeEntity(label = "DOC")
    static class ListDoc {
        @Id
        String id;
        List<Float> embedding;

        ListDoc() {
        }

        ListDoc(String id, List<Float> embedding) {
            this.id = id;
            this.embedding = embedding;
        }
    }

    @NodeEntity(label = "DOC")
    static class BoxedDoc {
        @Id
        String id;
        Float[] embedding;

        BoxedDoc() {
        }

        BoxedDoc(String id, Float[] embedding) {
            this.id = id;
            this.embedding = embedding;
        }
    }

    @RelationshipEntity(type = "DOC_REL", nodeType = Doc.class, sourceField = "from", targetField = "to")
    static class Rel {
        @Id
        String id;

        Doc from;

        Doc to;

        Rel() {
        }
    }

    static RowMapper<Doc> docReader = (row) -> {
        var node = row.getNode("n");
        return new Doc(
                ValueMappers.asString(node.get("id")),
                ValueMappers.asFloatArray(node.get("embedding")));
    };

    static EntityWriter<Doc> docWriter = (entity) -> Map.of("embedding", entity.embedding);

    static RowMapper<ListDoc> listDocReader = (row) -> {
        var node = row.getNode("n");
        return new ListDoc(
                ValueMappers.asString(node.get("id")),
                ValueMappers.asFloatList(node.get("embedding")));
    };

    static EntityWriter<ListDoc> listDocWriter = (entity) -> Map.of("embedding", entity.embedding);

    static RowMapper<BoxedDoc> boxedDocReader = (row) -> {
        var node = row.getNode("n");
        List<Float> embedding = ValueMappers.asFloatList(node.get("embedding"));
        return new BoxedDoc(
                ValueMappers.asString(node.get("id")),
                embedding.toArray(new Float[0]));
    };

    static EntityWriter<BoxedDoc> boxedDocWriter = (entity) -> Map.of("embedding", entity.embedding);

    static RowMapper<Rel> relReader = (row) -> {
        var rel = row.getRelationship("rel");
        Rel r = new Rel();
        r.id = ValueMappers.asString(rel.properties().get("id"));
        return r;
    };

    static EntityWriter<Rel> relWriter = (entity) -> Map.of("id", entity.id);
}
