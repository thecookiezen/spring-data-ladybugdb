package com.thecookiezen.ladybugdb.spring.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HnswOptionsTest {

    @Test
    void defaults_shouldMatchDocumentedLadybugValues() {
        HnswOptions options = HnswOptions.defaults();

        assertEquals(30, options.mu());
        assertEquals(60, options.ml());
        assertEquals(0.05, options.pu());
        assertEquals(HnswOptions.Metric.COSINE, options.metric());
        assertEquals(200, options.efc());
        assertTrue(options.cacheEmbeddings());
    }

    @Test
    void builder_shouldOverrideSelectedValuesAndKeepDefaults() {
        HnswOptions options = HnswOptions.builder()
                .metric(HnswOptions.Metric.L2)
                .efc(150)
                .build();

        assertEquals(30, options.mu());
        assertEquals(60, options.ml());
        assertEquals(0.05, options.pu());
        assertEquals(HnswOptions.Metric.L2, options.metric());
        assertEquals(150, options.efc());
        assertTrue(options.cacheEmbeddings());
    }

    @Test
    void builder_shouldSupportAllValues() {
        HnswOptions options = HnswOptions.builder()
                .mu(20)
                .ml(40)
                .pu(0.1)
                .metric(HnswOptions.Metric.DOTPRODUCT)
                .efc(300)
                .cacheEmbeddings(false)
                .build();

        assertEquals(new HnswOptions(20, 40, 0.1, HnswOptions.Metric.DOTPRODUCT, 300, false), options);
    }

    @Test
    void metric_shouldParseCaseInsensitive() {
        assertEquals(HnswOptions.Metric.COSINE, HnswOptions.Metric.from("cosine"));
        assertEquals(HnswOptions.Metric.COSINE, HnswOptions.Metric.from("COSINE"));
        assertEquals(HnswOptions.Metric.L2, HnswOptions.Metric.from("l2"));
        assertEquals(HnswOptions.Metric.L2SQ, HnswOptions.Metric.from("L2sq"));
        assertEquals(HnswOptions.Metric.DOTPRODUCT, HnswOptions.Metric.from("dotproduct"));
        assertEquals(HnswOptions.Metric.IP, HnswOptions.Metric.from("ip"));
    }

    @Test
    void metric_shouldRejectUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> HnswOptions.Metric.from("nope"));
        assertThrows(IllegalArgumentException.class, () -> HnswOptions.Metric.from(null));
    }

    @Test
    void constructor_shouldRejectNonPositiveMu() {
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(0, 60, 0.05, HnswOptions.Metric.COSINE, 200, true));
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(-1, 60, 0.05, HnswOptions.Metric.COSINE, 200, true));
    }

    @Test
    void constructor_shouldRejectNonPositiveMl() {
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(30, 0, 0.05, HnswOptions.Metric.COSINE, 200, true));
    }

    @Test
    void constructor_shouldRejectNonPositiveEfc() {
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(30, 60, 0.05, HnswOptions.Metric.COSINE, 0, true));
    }

    @Test
    void constructor_shouldRejectPuOutsideOfZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(30, 60, 0, HnswOptions.Metric.COSINE, 200, true));
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(30, 60, 1.5, HnswOptions.Metric.COSINE, 200, true));
    }

    @Test
    void constructor_shouldRejectNullMetric() {
        assertThrows(IllegalArgumentException.class, () -> new HnswOptions(30, 60, 0.05, null, 200, true));
    }

    @Test
    void toCypherOptions_shouldRenderAllOptionsInLadybugOrder() {
        String rendered = HnswOptions.defaults().toCypherOptions();

        assertEquals(", mu := 30, ml := 60, pu := 0.05, metric := 'cosine', efc := 200, cache_embeddings := true",
                rendered);
    }

    @Test
    void toCypherOptions_shouldRenderCustomOptions() {
        String rendered = HnswOptions.builder()
                .mu(25)
                .ml(50)
                .pu(0.1)
                .metric(HnswOptions.Metric.L2SQ)
                .efc(120)
                .cacheEmbeddings(false)
                .build()
                .toCypherOptions();

        assertEquals(", mu := 25, ml := 50, pu := 0.1, metric := 'l2sq', efc := 120, cache_embeddings := false",
                rendered);
    }
}
