package com.thecookiezen.ladybugdb.spring.vector;

/**
 * Thrown when a vector index lifecycle operation fails.
 * <p>
 * Expected conditions are reported through return values instead ({@code false}
 * from idempotent {@code create}/{@code drop} calls); this exception signals
 * actual failures such as a missing table or an unknown property.
 */
public class VectorIndexException extends RuntimeException {

    /**
     * @param message the detail message
     */
    public VectorIndexException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public VectorIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
