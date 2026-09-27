package com.thecookiezen.ladybugdb.spring.vector;

import java.util.Arrays;
import java.util.Locale;

/**
 * Search tuning options for {@code CALL QUERY_VECTOR_INDEX(...)}, mirroring
 * the named arguments introduced by the LadybugDB vector extension 0.20.0.
 * <p>
 * Use {@link #defaults()} to pass no options (engine defaults) or
 * {@link #builder()} to override individual values.
 * <p>
 * Note that {@code QUERY_VECTOR_INDEX} does not support distance-threshold
 * filtering: results are always the {@code k} nearest neighbours, and clients
 * that need a similarity cut-off post-filter by score.
 *
 * @param efs        size of the candidate list used during the search
 *                   ({@code efs}); {@code null} uses the engine default
 * @param searchType search strategy ({@code search_type}); {@code null} lets
 *                   the engine choose
 */
public record VectorQueryOptions(Integer efs, SearchType searchType) {

    /**
     * The engine defaults: no {@code efs} or {@code search_type} overrides are
     * passed to {@code QUERY_VECTOR_INDEX}.
     */
    public static final VectorQueryOptions DEFAULTS = new VectorQueryOptions(null, null);

    public VectorQueryOptions {
        if (efs != null && efs <= 0) {
            throw new IllegalArgumentException("efs must be positive, got: " + efs);
        }
    }

    /**
     * Returns the engine default options.
     *
     * @return the default options
     */
    public static VectorQueryOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder starting from the engine defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Renders the options as the named-argument suffix of a
     * {@code CALL QUERY_VECTOR_INDEX(graph, index, vector, k ...)} statement,
     * including the leading comma. Package-private: rendering details are an
     * implementation concern of {@link ProjectedGraphOperations}.
     *
     * @return the rendered options, e.g. {@code ", efs := 100, search_type := 'navix'"}
     */
    String toCypherOptions() {
        StringBuilder options = new StringBuilder();
        if (efs != null) {
            options.append(", efs := ").append(efs);
        }
        if (searchType != null) {
            options.append(", search_type := '").append(searchType.cypherLiteral()).append('\'');
        }
        return options.toString();
    }

    /**
     * Search strategies accepted by the {@code search_type} option of
     * {@code QUERY_VECTOR_INDEX}.
     */
    public enum SearchType {

        /** Let the engine pick a strategy based on the query selectivity. */
        AUTO,
        /** NaVix: navigable small world search with verifier. */
        NAVIX,
        /** Adaptive search with an L-shaped fallback. */
        ADAPTIVE_L,
        /** Adaptive search with a G-shaped fallback. */
        ADAPTIVE_G,
        /** Blind search scanning the graph without navigation guarantees. */
        BLIND,
        /** Directed search. */
        DIRECTED,
        /** One-hop filtered search. */
        ONE_HOP,
        /** Naive brute-force search. */
        NAIVE,
        /** Random search. */
        RANDOM;

        /**
         * Parses a search type, case-insensitively.
         *
         * @param value the search type as used in Cypher (e.g. {@code "navix"})
         * @return the matching search type
         * @throws IllegalArgumentException if the value does not name a known search type
         */
        public static SearchType from(String value) {
            if (value != null) {
                for (SearchType type : values()) {
                    if (type.name().equalsIgnoreCase(value)) {
                        return type;
                    }
                }
            }
            throw new IllegalArgumentException(
                    "Unknown vector search_type '" + value + "'. Expected one of "
                            + Arrays.toString(values()));
        }

        /**
         * Returns the lowercase literal used in {@code QUERY_VECTOR_INDEX} calls.
         *
         * @return the Cypher literal for this search type
         */
        public String cypherLiteral() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Mutable builder for {@link VectorQueryOptions}, starting from the engine
     * defaults.
     */
    public static final class Builder {

        private Integer efs = DEFAULTS.efs;
        private SearchType searchType = DEFAULTS.searchType;

        private Builder() {
        }

        /**
         * @param efs size of the candidate list used during the search
         * @return this builder
         */
        public Builder efs(int efs) {
            this.efs = efs;
            return this;
        }

        /**
         * @param searchType search strategy
         * @return this builder
         */
        public Builder searchType(SearchType searchType) {
            this.searchType = searchType;
            return this;
        }

        /**
         * Builds the options, validating all values.
         *
         * @return the options
         * @throws IllegalArgumentException if any value is out of range
         */
        public VectorQueryOptions build() {
            return new VectorQueryOptions(efs, searchType);
        }
    }
}
