package com.edunext.edutrack.api.feature.imports;

import java.util.Set;

/**
 * B-030 · no schema is registered under that key.
 *
 * <p>Answered as 404 rather than 400 once the endpoints land: {@code schema} is
 * a path segment, so an unregistered value makes the whole resource
 * non-existent, not the request malformed.
 *
 * <p>The handler arrives with the first endpoint (B-032). Adding a
 * {@code @RestControllerAdvice} now would be advice on a controller that does
 * not exist — untestable, and dead until it silently is not.
 */
public class UnknownImportSchemaException extends RuntimeException {

    private final String key;

    UnknownImportSchemaException(String key, Set<String> registered) {
        super("No import schema registered under '" + key + "'. Registered: " + registered);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
