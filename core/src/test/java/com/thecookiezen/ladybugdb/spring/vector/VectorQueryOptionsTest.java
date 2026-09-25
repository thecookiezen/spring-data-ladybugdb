package com.thecookiezen.ladybugdb.spring.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorQueryOptionsTest {

    @Test
    void defaults_shouldRenderNoOptions() {
        assertEquals("", VectorQueryOptions.defaults().toCypherOptions());
    }

    @Test
    void builder_shouldRenderEfs() {
        VectorQueryOptions options = VectorQueryOptions.builder().efs(150).build();

        assertEquals(", efs := 150", options.toCypherOptions());
    }

    @Test
    void builder_shouldRenderSearchType() {
        VectorQueryOptions options = VectorQueryOptions.builder()
                .searchType(VectorQueryOptions.SearchType.NAVIX)
                .build();

        assertEquals(", search_type := 'navix'", options.toCypherOptions());
    }

    @Test
    void builder_shouldRenderBothOptions() {
        VectorQueryOptions options = VectorQueryOptions.builder()
                .efs(200)
                .searchType(VectorQueryOptions.SearchType.ADAPTIVE_L)
                .build();

        assertEquals(", efs := 200, search_type := 'adaptive_l'", options.toCypherOptions());
    }

    @Test
    void searchType_shouldRenderLowercaseLiterals() {
        assertEquals("auto", VectorQueryOptions.SearchType.AUTO.cypherLiteral());
        assertEquals("navix", VectorQueryOptions.SearchType.NAVIX.cypherLiteral());
        assertEquals("adaptive_l", VectorQueryOptions.SearchType.ADAPTIVE_L.cypherLiteral());
        assertEquals("adaptive_g", VectorQueryOptions.SearchType.ADAPTIVE_G.cypherLiteral());
        assertEquals("blind", VectorQueryOptions.SearchType.BLIND.cypherLiteral());
        assertEquals("directed", VectorQueryOptions.SearchType.DIRECTED.cypherLiteral());
        assertEquals("one_hop", VectorQueryOptions.SearchType.ONE_HOP.cypherLiteral());
        assertEquals("naive", VectorQueryOptions.SearchType.NAIVE.cypherLiteral());
        assertEquals("random", VectorQueryOptions.SearchType.RANDOM.cypherLiteral());
    }

    @Test
    void searchType_from_shouldParseCaseInsensitively() {
        assertEquals(VectorQueryOptions.SearchType.NAVIX,
                VectorQueryOptions.SearchType.from("navix"));
        assertEquals(VectorQueryOptions.SearchType.ADAPTIVE_L,
                VectorQueryOptions.SearchType.from("ADAPTIVE_L"));
        assertEquals(VectorQueryOptions.SearchType.ADAPTIVE_L,
                VectorQueryOptions.SearchType.from("adaptive_l"));
    }

    @Test
    void searchType_from_shouldRejectUnknownValues() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorQueryOptions.SearchType.from("bogus"));
        assertThrows(IllegalArgumentException.class,
                () -> VectorQueryOptions.SearchType.from(null));
    }

    @Test
    void builder_shouldRejectNonPositiveEfs() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorQueryOptions.builder().efs(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> VectorQueryOptions.builder().efs(-1).build());
    }
}
