package com.thecookiezen.ladybugdb.spring.vector;

import java.util.List;

/**
 * A single row of the {@code CALL SHOW_INDEXES() RETURN *} result.
 * <p>
 * Reports both vector (HNSW) indexes and core indexes such as the primary key
 * HASH index, which LadybugDB always includes in the result.
 *
 * @param tableName       name of the table the index belongs to
 * @param indexName       name of the index
 * @param indexType       index type as reported by the engine (e.g. {@code HNSW} or {@code HASH})
 * @param propertyNames   names of the indexed properties
 * @param extensionLoaded whether the extension owning this index is loaded on the current connection
 * @param indexDefinition the Cypher statement that recreates the index, or an empty string for core indexes
 */
public record VectorIndexInfo(
        String tableName,
        String indexName,
        String indexType,
        List<String> propertyNames,
        boolean extensionLoaded,
        String indexDefinition) {

    /**
     * @return whether this row describes a vector (HNSW) index
     */
    public boolean isVectorIndex() {
        return VectorIndexOperations.HNSW_INDEX_TYPE.equalsIgnoreCase(indexType);
    }
}
