package com.thecookiezen.ladybugdb.spring.vector;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectedGraphFiltersTest {

    @Test
    void none_shouldProduceEmptyFilters() {
        ProjectedGraphFilters filters = ProjectedGraphFilters.none();

        assertEquals("[]", filters.toCypherNodeFilters());
        assertEquals("[]", filters.toCypherRelFilters());
    }

    @Test
    void nodes_shouldFilterSingleTable() {
        ProjectedGraphFilters filters = ProjectedGraphFilters.nodes(
                Map.of("Book", "n.published_year > 2010"));

        assertEquals("[]", filters.toCypherRelFilters());
        assertEquals("{\"Book\": \"n.published_year > 2010\"}", filters.toCypherNodeFilters());
    }

    @Test
    void filtersWithoutPredicates_shouldRenderAsTableList() {
        Map<String, String> noPredicates = new LinkedHashMap<>();
        noPredicates.put("Book", "");
        noPredicates.put("Tag", null);

        ProjectedGraphFilters filters = new ProjectedGraphFilters(noPredicates, Map.of());

        assertEquals("[\"Book\", \"Tag\"]", filters.toCypherNodeFilters());
    }

    @Test
    void filtersWithMixedPredicates_shouldRenderAsMapWithEmptyPredicates() {
        Map<String, String> nodeFilters = new LinkedHashMap<>();
        nodeFilters.put("Book", "n.published_year > 2010");
        nodeFilters.put("Tag", "");

        ProjectedGraphFilters filters = new ProjectedGraphFilters(nodeFilters, Map.of());

        assertEquals("{\"Book\": \"n.published_year > 2010\", \"Tag\": \"\"}",
                filters.toCypherNodeFilters());
    }

    @Test
    void filters_shouldRenderRelationshipFilters() {
        ProjectedGraphFilters filters = ProjectedGraphFilters.nodesAndRels(
                Map.of("Book", "n.published_year > 2010"),
                Map.of("PublishedBy", "r.since > 2000"));

        assertEquals("{\"Book\": \"n.published_year > 2010\"}", filters.toCypherNodeFilters());
        assertEquals("{\"PublishedBy\": \"r.since > 2000\"}", filters.toCypherRelFilters());
    }

    @Test
    void filters_shouldEscapeDoubleQuotesInPredicates() {
        ProjectedGraphFilters filters = ProjectedGraphFilters.nodes(
                Map.of("Book", "n.title = 'Say \"hi\"'"));

        assertEquals("{\"Book\": \"n.title = 'Say \\\"hi\\\"'\"}", filters.toCypherNodeFilters());
    }

    @Test
    void filters_shouldAllowSingleQuotesInPredicates() {
        ProjectedGraphFilters filters = ProjectedGraphFilters.nodes(
                Map.of("Book", "n.title = 'It''s fine'"));

        assertEquals("{\"Book\": \"n.title = 'It''s fine'\"}", filters.toCypherNodeFilters());
    }

    @Test
    void constructor_shouldDefensivelyCopyFilters() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("Book", "n.id > 1");

        ProjectedGraphFilters filters = new ProjectedGraphFilters(mutable, mutable);
        mutable.put("Evil", "1=1");

        assertEquals(Map.of("Book", "n.id > 1"), filters.nodeFilters());
        assertEquals(Map.of("Book", "n.id > 1"), filters.relFilters());
    }

    @Test
    void constructor_shouldRejectNullKeys() {
        Map<String, String> withNullKey = new HashMap<>();
        withNullKey.put(null, "n.id > 1");

        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedGraphFilters(withNullKey, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedGraphFilters(Map.of(), withNullKey));
    }

    @Test
    void constructor_shouldRejectNullFilterMaps() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedGraphFilters(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedGraphFilters(Map.of(), null));
    }
}
