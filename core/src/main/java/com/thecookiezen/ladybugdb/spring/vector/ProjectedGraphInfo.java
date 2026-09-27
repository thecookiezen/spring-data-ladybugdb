package com.thecookiezen.ladybugdb.spring.vector;

/**
 * A projected graph reported by {@code CALL SHOW_PROJECTED_GRAPHS() RETURN *}.
 *
 * @param name name under which the graph was projected
 * @param type projection type as reported by the engine: {@code NATIVE} for
 *             {@code PROJECT_GRAPH} projections, {@code CYPHER} for
 *             {@code PROJECT_GRAPH_CYPHER} projections
 */
public record ProjectedGraphInfo(String name, String type) {

    /**
     * @return {@code true} if the graph was projected with {@code PROJECT_GRAPH}
     */
    public boolean isNative() {
        return "NATIVE".equals(type);
    }

    /**
     * @return {@code true} if the graph was projected with {@code PROJECT_GRAPH_CYPHER}
     */
    public boolean isCypher() {
        return "CYPHER".equals(type);
    }
}
