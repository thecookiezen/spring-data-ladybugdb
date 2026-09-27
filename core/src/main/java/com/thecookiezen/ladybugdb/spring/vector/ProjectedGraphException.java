package com.thecookiezen.ladybugdb.spring.vector;

/**
 * Thrown when a projected graph lifecycle or query operation fails.
 * <p>
 * Expected conditions are reported through return values instead ({@code false}
 * from idempotent {@code create}/{@code drop} calls); this exception signals
 * actual failures such as an unknown table, an invalid filter predicate, or a
 * KNN query against a graph that does not exist on the connection.
 */
public class ProjectedGraphException extends RuntimeException {

    /**
     * @param message the detail message
     */
    public ProjectedGraphException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public ProjectedGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
