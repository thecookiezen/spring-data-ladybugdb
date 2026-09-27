package com.thecookiezen.ladybugdb.spring.vector;

import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.thecookiezen.ladybugdb.spring.annotation.NodeEntity;
import com.thecookiezen.ladybugdb.spring.annotation.Query;
import com.thecookiezen.ladybugdb.spring.annotation.RelationshipEntity;
import com.thecookiezen.ladybugdb.spring.connection.SimpleConnectionFactory;
import com.thecookiezen.ladybugdb.spring.core.LadybugDBTemplate;
import com.thecookiezen.ladybugdb.spring.mapper.EntityWriter;
import com.thecookiezen.ladybugdb.spring.mapper.RowMapper;
import com.thecookiezen.ladybugdb.spring.mapper.ValueMappers;
import com.thecookiezen.ladybugdb.spring.repository.NodeRepository;
import com.thecookiezen.ladybugdb.spring.repository.support.EntityRegistry;
import com.thecookiezen.ladybugdb.spring.repository.support.LadybugDBRepositoryFactory;
import com.thecookiezen.ladybugdb.spring.transaction.LadybugDBTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for filtered KNN vector search: {@code PROJECT_GRAPH}
 * projections queried through repository {@code @Query} methods running
 * {@code QUERY_VECTOR_INDEX} against the projected graph.
 * <p>
 * Projected graphs live on the connection, so every scenario runs inside a
 * transaction to keep the projection and the KNN query on the same connection.
 * The tests are skipped when the vector extension cannot be installed
 * (e.g. in an offline environment).
 */
class FilteredVectorSearchTest {

    private static Database db;
    private static SimpleConnectionFactory connectionFactory;
    private static LadybugDBTemplate template;
    private static ProjectedGraphOperations projectedGraphs;
    private static BookRepository bookRepository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void setupAll() {
        db = new Database(":memory:");
        connectionFactory = new SimpleConnectionFactory(db);

        EntityRegistry registry = new EntityRegistry();
        registry.registerDescriptor(Book.class, bookReader, bookWriter);
        registry.registerDescriptor(BookDistance.class, bookDistanceReader, distanceWriter());
        registry.registerDescriptor(Tagged.class, taggedReader, taggedWriter);

        template = new LadybugDBTemplate(connectionFactory, registry);
        projectedGraphs = template.projectedGraphs();

        LadybugDBRepositoryFactory factory = new LadybugDBRepositoryFactory(template, registry);
        bookRepository = factory.getRepository(BookRepository.class);
        transactions = new TransactionTemplate(new LadybugDBTransactionManager(connectionFactory));

        try (Connection conn = new Connection(db)) {
            try (var result = conn.query("INSTALL vector")) {
                assumeTrue(result.isSuccess(),
                        "vector extension could not be installed (offline environment?): "
                                + result.getErrorMessage());
            }
            try (var loaded = conn.query("LOAD vector")) {
                assumeTrue(loaded.isSuccess(), "vector extension could not be loaded: "
                        + loaded.getErrorMessage());
            }
            conn.query("CREATE NODE TABLE Book(id STRING PRIMARY KEY, title STRING, "
                    + "published_year INT64, embedding FLOAT[4])");
            conn.query("CREATE (:Book {id: 'b1', title: 'Old Classic', published_year: 1995, "
                    + "embedding: [0.1, 0.1, 0.1, 0.1]})");
            conn.query("CREATE (:Book {id: 'b2', title: 'Recent A', published_year: 2020, "
                    + "embedding: [0.9, 0.9, 0.1, 0.1]})");
            conn.query("CREATE (:Book {id: 'b3', title: 'Recent B', published_year: 2021, "
                    + "embedding: [0.8, 0.85, 0.12, 0.1]})");
            try (var indexed = conn.query("CALL CREATE_VECTOR_INDEX('Book', 'book_emb', 'embedding')")) {
                assumeTrue(indexed.isSuccess(), "vector index could not be created: "
                        + indexed.getErrorMessage());
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
        projectedGraphs.drop("recent_books");
        projectedGraphs.drop("old_books");
    }

    @AfterEach
    void tearDown() {
        projectedGraphs.drop("recent_books");
        projectedGraphs.drop("old_books");
    }

    interface BookRepository extends NodeRepository<Book, String, Tagged, Book> {

        @Query(value = "CALL QUERY_VECTOR_INDEX('recent_books', 'book_emb', $queryVector, $k) RETURN node",
                loadExtensions = {"vector"})
        List<Book> findSimilarRecent(@Param("queryVector") float[] queryVector, @Param("k") int k);

        @Query(value = "CALL QUERY_VECTOR_INDEX('recent_books', 'book_emb', $queryVector, $k) "
                + "RETURN node.id AS id, distance AS distance",
                loadExtensions = {"vector"})
        List<BookDistance> findSimilarRecentWithDistance(
                @Param("queryVector") float[] queryVector, @Param("k") int k);

        @Query(value = "CALL QUERY_VECTOR_INDEX('old_books', 'book_emb', $queryVector, $k) RETURN node",
                loadExtensions = {"vector"})
        List<Book> findSimilarOld(@Param("queryVector") float[] queryVector, @Param("k") int k);
    }

    @Test
    void knnOverProjectedGraph_shouldReturnOnlyFilteredNodes() {
        // The unfiltered nearest neighbour of this vector is the 1995 book;
        // server-side filtering must exclude it.
        List<Book> similar = transactions.execute(status -> {
            projectedGraphs.create("recent_books",
                    ProjectedGraphFilters.nodes(Map.of("Book", "n.published_year > 2010")));
            return bookRepository.findSimilarRecent(new float[] {0.15f, 0.15f, 0.1f, 0.1f}, 1);
        });

        assertEquals(1, similar.size());
        assertNotEquals("b1", similar.get(0).getId());
        assertTrue(similar.get(0).getPublishedYear() > 2010);
    }

    @Test
    void knnOverProjectedGraph_shouldReturnDistances() {
        List<BookDistance> hits = transactions.execute(status -> {
            projectedGraphs.create("recent_books",
                    ProjectedGraphFilters.nodes(Map.of("Book", "n.published_year > 2010")));
            return bookRepository.findSimilarRecentWithDistance(
                    new float[] {0.85f, 0.9f, 0.1f, 0.1f}, 2);
        });

        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(h -> h.distance() >= 0.0));
        assertTrue(hits.stream().noneMatch(h -> h.id().equals("b1")));
    }

    @Test
    void knnOverProjectedGraph_shouldProjectInsideTransaction() {
        // The projection and the repository query share the transaction-bound
        // connection in a single transactional block.
        List<Book> similar = transactions.execute(status -> {
            projectedGraphs.create("recent_books",
                    ProjectedGraphFilters.nodes(Map.of("Book", "n.published_year > 2010")));
            return bookRepository.findSimilarRecent(new float[] {0.85f, 0.9f, 0.1f, 0.1f}, 2);
        });

        assertEquals(2, similar.size());
        assertTrue(similar.stream().allMatch(b -> b.getPublishedYear() > 2010));
    }

    @Test
    void knnOverCypherProjection_shouldReturnOnlyMatchingNodes() {
        List<Book> similar = transactions.execute(status -> {
            projectedGraphs.createFromCypher("old_books",
                    "MATCH (b:Book) WHERE b.published_year < 2000 RETURN b");
            return bookRepository.findSimilarOld(new float[] {0.1f, 0.1f, 0.1f, 0.1f}, 3);
        });

        assertEquals(1, similar.size());
        assertEquals("b1", similar.get(0).getId());
    }

    @NodeEntity(label = "Book")
    static class Book {

        @Id
        private final String id;
        private final String title;
        private final long publishedYear;
        private final float[] embedding;

        Book(String id, String title, long publishedYear, float[] embedding) {
            this.id = id;
            this.title = title;
            this.publishedYear = publishedYear;
            this.embedding = embedding;
        }

        String getId() {
            return id;
        }

        String getTitle() {
            return title;
        }

        long getPublishedYear() {
            return publishedYear;
        }

        float[] getEmbedding() {
            return embedding;
        }
    }

    static class BookDistance {

        private final String id;
        private final Double distance;

        BookDistance(String id, Double distance) {
            this.id = id;
            this.distance = distance;
        }

        String id() {
            return id;
        }

        Double distance() {
            return distance;
        }
    }

    static RowMapper<Book> bookReader = row -> {
        var node = row.getNode("node");
        return new Book(
                ValueMappers.asString(node.get("id")),
                ValueMappers.asString(node.get("title")),
                ValueMappers.asLong(node.get("published_year")),
                ValueMappers.asFloatArray(node.get("embedding")));
    };

    static EntityWriter<Book> bookWriter = entity -> Map.of(
            "title", entity.getTitle(),
            "published_year", entity.getPublishedYear(),
            "embedding", entity.getEmbedding());

    static RowMapper<BookDistance> bookDistanceReader = row -> new BookDistance(
            ValueMappers.asString(row.getValue("id")),
            ValueMappers.asDouble(row.getValue("distance")));

    static EntityWriter<BookDistance> distanceWriter() {
        return entity -> Map.of();
    }

    @RelationshipEntity(type = "TAGGED", nodeType = Book.class, sourceField = "from", targetField = "to")
    static class Tagged {

        @Id
        String id;
        Book from;
        Book to;

        Tagged() {
        }
    }

    static RowMapper<Tagged> taggedReader = row -> new Tagged();

    static EntityWriter<Tagged> taggedWriter = entity -> Map.of();
}
