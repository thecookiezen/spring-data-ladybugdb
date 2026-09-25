package com.thecookiezen.ladybugdb.spring.vector;

/**
 * Shared Cypher text helpers for the vector package: validation of engine
 * identifiers interpolated into {@code CALL} statements, and rendering of
 * string literals the engine's parser accepts.
 */
final class CypherSupport {

    private CypherSupport() {
        // Utility class
    }

    /**
     * Validates a table, index, property, graph or column name that is
     * interpolated into a Cypher statement: it must not be null, blank, or
     * contain quotes, backslashes, semicolons or control characters, so a bad
     * name fails fast with an {@link IllegalArgumentException} instead of
     * letting the engine fail with a confusing parser error.
     *
     * @param name the name to validate
     * @param what what the name identifies, used in the error message
     */
    static void requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(what + " must not be null or blank");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\'' || c == '"' || c == '\\' || c == '`' || c == ';' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        what + " contains unsupported character '" + c + "': '" + name + "'");
            }
        }
    }

    /**
     * Renders a string as a double-quoted Cypher literal. The engine's parser
     * does not accept doubled quotes inside single-quoted literals, so the
     * literal is delimited with double quotes and inner double quotes are
     * backslash-escaped; single quotes then pass through untouched.
     *
     * @param raw the raw string
     * @return the quoted literal
     */
    static String literal(String raw) {
        return '"' + raw.replace("\"", "\\\"") + '"';
    }
}
