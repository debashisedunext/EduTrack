package com.edunext.edutrack.api.feature.onboarding.projects;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field-keyed refusals, so each message lands on the input that caused it
 * rather than in a banner above the form.
 *
 * <p>{@code ObClientValidationException}'s shape, kept identical: the handler
 * maps {@code errors} to string <em>arrays</em>, which is what
 * {@code ValidationProblem} declares and what {@code ApiError.fieldErrors} on
 * the frontend reads. A bare string deserialises into a shape the form's
 * {@code messages[0]} silently indexes character by character.
 */
class ObProjectValidationException extends RuntimeException {

    private final Map<String, String> errors;

    ObProjectValidationException(Map<String, String> errors) {
        super("the project was not saved");
        this.errors = new LinkedHashMap<>(errors);
    }

    Map<String, String> errors() {
        return errors;
    }
}
