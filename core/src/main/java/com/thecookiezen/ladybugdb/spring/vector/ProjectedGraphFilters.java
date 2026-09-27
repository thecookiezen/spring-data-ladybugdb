package com.thecookiezen.ladybugdb.spring.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Filters for a projected graph, mirroring the table arguments of
 * {@code CALL PROJECT_GRAPH(name, nodeFilters, relFilters)} in LadybugDB.
 * <p>
 * Each filter maps a table name to an optional Cypher predicate: entries with
 * a non-blank predicate render as a {@code {'Table': 'predicate'}} map, entries
 * without one render as a plain {@code ['Table']} list — mixing both is
 * expressed with empty predicates, which the engine treats as unfiltered.
 * <p>
 * Use {@link #none()} for a full projection, {@link #nodes(Map)} to filter
 * nodes, or {@link #nodesAndRels(Map, Map)} to filter both.
 *
 * @param nodeFilters node table filters; a blank predicate means the whole table
 * @param relFilters  relationship table filters; a blank predicate means the whole table
 */
public record ProjectedGraphFilters(Map<String, String> nodeFilters, Map<String, String> relFilters) {

    public ProjectedGraphFilters {
        nodeFilters = validatedCopy(nodeFilters, "nodeFilters");
        relFilters = validatedCopy(relFilters, "relFilters");
    }

    /**
     * Returns filters that project every table without predicates.
     *
     * @return empty filters
     */
    public static ProjectedGraphFilters none() {
        return new ProjectedGraphFilters(Map.of(), Map.of());
    }

    /**
     * Returns filters for node tables only.
     *
     * @param nodeFilters node table filters; a blank predicate means the whole table
     * @return the filters
     */
    public static ProjectedGraphFilters nodes(Map<String, String> nodeFilters) {
        return new ProjectedGraphFilters(nodeFilters, Map.of());
    }

    /**
     * Returns filters for node and relationship tables.
     *
     * @param nodeFilters node table filters; a blank predicate means the whole table
     * @param relFilters  relationship table filters; a blank predicate means the whole table
     * @return the filters
     */
    public static ProjectedGraphFilters nodesAndRels(Map<String, String> nodeFilters,
            Map<String, String> relFilters) {
        return new ProjectedGraphFilters(nodeFilters, relFilters);
    }

    /**
     * Renders the node filters as the second argument of a
     * {@code CALL PROJECT_GRAPH(...)} statement. Package-private: rendering
     * details are an implementation concern of {@link ProjectedGraphOperations}.
     *
     * @return the rendered filters, e.g. {@code {'Book': 'n.year > 2010'}}
     */
    String toCypherNodeFilters() {
        return toCypher(nodeFilters);
    }

    /**
     * Renders the relationship filters as the third argument of a
     * {@code CALL PROJECT_GRAPH(...)} statement. Package-private: rendering
     * details are an implementation concern of {@link ProjectedGraphOperations}.
     *
     * @return the rendered filters, e.g. {@code ['PublishedBy']}
     */
    String toCypherRelFilters() {
        return toCypher(relFilters);
    }

    private static String toCypher(Map<String, String> filters) {
        if (filters.isEmpty()) {
            return "[]";
        }
        boolean hasPredicates = filters.values().stream()
                .anyMatch(predicate -> predicate != null && !predicate.isBlank());

        StringBuilder cypher = new StringBuilder();
        if (hasPredicates) {
            // The engine accepts a map only; tables without a predicate are
            // carried as empty-string predicates, which it treats as unfiltered.
            cypher.append('{');
            filters.forEach((table, predicate) -> {
                if (cypher.length() > 1) {
                    cypher.append(", ");
                }
                cypher.append(CypherSupport.literal(table)).append(": ")
                        .append(CypherSupport.literal(predicate == null ? "" : predicate));
            });
            cypher.append('}');
        } else {
            cypher.append('[');
            for (String table : filters.keySet()) {
                if (cypher.length() > 1) {
                    cypher.append(", ");
                }
                cypher.append(CypherSupport.literal(table));
            }
            cypher.append(']');
        }
        return cypher.toString();
    }

    private static Map<String, String> validatedCopy(Map<String, String> filters, String what) {
        if (filters == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        Map<String, String> copy = new LinkedHashMap<>(filters);
        if (copy.containsKey(null)) {
            throw new IllegalArgumentException(what + " must not contain a null table name");
        }
        return Collections.unmodifiableMap(copy);
    }
}
