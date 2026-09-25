package com.thecookiezen.ladybugdb.spring.vector;

import java.util.Arrays;
import java.util.Locale;

/**
 * Options for an HNSW vector index, mirroring the named arguments of
 * {@code CALL CREATE_VECTOR_INDEX(...)} in LadybugDB 0.20.4.
 * <p>
 * Use {@link #defaults()} for the engine defaults or {@link #builder()} to
 * override individual values.
 *
 * @param mu              maximum number of connections per node in the HNSW graph
 * @param ml              level multiplier determining the number of levels in the HNSW graph
 * @param pu              fraction of the graph recomputed during incremental updates
 * @param metric          distance metric used to compare vectors
 * @param efc             size of the candidate list used while constructing the index (ef_construction)
 * @param cacheEmbeddings whether embeddings are cached in memory for faster searches
 */
public record HnswOptions(int mu, int ml, double pu, Metric metric, int efc, boolean cacheEmbeddings) {

    /**
     * The engine defaults as documented for {@code CREATE_VECTOR_INDEX}:
     * mu=30, ml=60, pu=0.05, metric=cosine, efc=200, cache_embeddings=true.
     */
    public static final HnswOptions DEFAULTS = new HnswOptions(30, 60, 0.05, Metric.COSINE, 200, true);

    public HnswOptions {
        if (mu <= 0) {
            throw new IllegalArgumentException("mu must be positive, got: " + mu);
        }
        if (ml <= 0) {
            throw new IllegalArgumentException("ml must be positive, got: " + ml);
        }
        if (pu <= 0 || pu > 1) {
            throw new IllegalArgumentException("pu must be in (0, 1], got: " + pu);
        }
        if (metric == null) {
            throw new IllegalArgumentException("metric must not be null");
        }
        if (efc <= 0) {
            throw new IllegalArgumentException("efc must be positive, got: " + efc);
        }
    }

    /**
     * Returns the engine default options.
     *
     * @return the default options
     */
    public static HnswOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with the engine defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Distance metrics supported by the LadybugDB vector extension.
     */
    public enum Metric {

        /** Cosine similarity. */
        COSINE,
        /** Euclidean distance. */
        L2,
        /** Squared Euclidean distance. */
        L2SQ,
        /** Dot product similarity. */
        DOTPRODUCT,
        /** Inner product similarity. */
        IP;

        /**
         * Parses a metric name, case-insensitively.
         *
         * @param value the metric name as used in Cypher (e.g. {@code "cosine"})
         * @return the matching metric
         * @throws IllegalArgumentException if the value does not name a known metric
         */
        public static Metric from(String value) {
            if (value != null) {
                for (Metric metric : values()) {
                    if (metric.name().equalsIgnoreCase(value)) {
                        return metric;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "Unknown HNSW metric '" + value + "'. Expected one of " + Arrays.toString(values()));
        }

        /**
         * Returns the lowercase literal used in {@code CREATE_VECTOR_INDEX} calls.
         *
         * @return the Cypher literal for this metric
         */
        public String cypherLiteral() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Mutable builder for {@link HnswOptions}, starting from the engine defaults.
     */
    public static final class Builder {

        private int mu = DEFAULTS.mu;
        private int ml = DEFAULTS.ml;
        private double pu = DEFAULTS.pu;
        private Metric metric = DEFAULTS.metric;
        private int efc = DEFAULTS.efc;
        private boolean cacheEmbeddings = DEFAULTS.cacheEmbeddings;

        private Builder() {
        }

        /**
         * @param mu maximum number of connections per node
         * @return this builder
         */
        public Builder mu(int mu) {
            this.mu = mu;
            return this;
        }

        /**
         * @param ml level multiplier for the HNSW graph
         * @return this builder
         */
        public Builder ml(int ml) {
            this.ml = ml;
            return this;
        }

        /**
         * @param pu fraction of the graph recomputed during incremental updates
         * @return this builder
         */
        public Builder pu(double pu) {
            this.pu = pu;
            return this;
        }

        /**
         * @param metric distance metric
         * @return this builder
         */
        public Builder metric(Metric metric) {
            this.metric = metric;
            return this;
        }

        /**
         * @param efc candidate list size used while constructing the index
         * @return this builder
         */
        public Builder efc(int efc) {
            this.efc = efc;
            return this;
        }

        /**
         * @param cacheEmbeddings whether to cache embeddings in memory
         * @return this builder
         */
        public Builder cacheEmbeddings(boolean cacheEmbeddings) {
            this.cacheEmbeddings = cacheEmbeddings;
            return this;
        }

        /**
         * Builds the options, validating all values.
         *
         * @return the options
         * @throws IllegalArgumentException if any value is out of range
         */
        public HnswOptions build() {
            return new HnswOptions(mu, ml, pu, metric, efc, cacheEmbeddings);
        }
    }

    /**
     * Renders the options as the named-argument suffix of a
     * {@code CALL CREATE_VECTOR_INDEX(table, index, property ...)} statement,
     * including the leading comma. Package-private: rendering details are an
     * implementation concern of {@link VectorIndexOperations}.
     *
     * @return the rendered options, e.g. {@code ", mu := 30, ... , cache_embeddings := true"}
     */
    String toCypherOptions() {
        return ", mu := " + mu
                + ", ml := " + ml
                + ", pu := " + pu
                + ", metric := '" + metric.cypherLiteral() + "'"
                + ", efc := " + efc
                + ", cache_embeddings := " + cacheEmbeddings;
    }
}
