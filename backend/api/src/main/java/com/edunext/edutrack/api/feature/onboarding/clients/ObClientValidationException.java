package com.edunext.edutrack.api.feature.onboarding.clients;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B-102 · every field-level failure of one write, collected rather than thrown
 * at the first.
 *
 * <p>OB-04 is a four-step wizard and its fields are spread across all four
 * steps. Refusing one at a time means a boarder who picked a retired product on
 * step 2 and forgot the primary SPOC on step 3 submits twice to learn twice —
 * {@code ClientWriteService} (B-026) made exactly this argument for S-33 and it
 * has not changed for a wider form.
 *
 * <p>The map is field-keyed so each message lands on its own input, which is
 * also what tells the wizard which step to reopen. The handler renders it as
 * {@code errors} with <b>string arrays</b>, which is what
 * {@code ValidationProblem} declares and what {@code ApiError.fieldErrors} on
 * the frontend reads — a bare string deserialises into a shape the form's
 * {@code messages[0]} silently indexes character by character.
 */
class ObClientValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    ObClientValidationException(Map<String, String> errors) {
        super(errors.values().stream().findFirst().orElse("The client was not saved"));
        this.errors = new LinkedHashMap<>(errors);
    }

    Map<String, String> errors() {
        return errors;
    }
}
