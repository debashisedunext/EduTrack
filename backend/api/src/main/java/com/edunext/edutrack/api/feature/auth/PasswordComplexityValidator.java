package com.edunext.edutrack.api.feature.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A-028 · the four character classes of blueprint §10.3, checked one at a time
 * so the refusal can say which one is missing.
 *
 * <p>Stateless and thread-safe; Hibernate Validator holds a single instance.
 *
 * <p><b>B-013 moved the predicates to {@link PasswordComplexity}</b> and left
 * this class the part that is actually about Bean Validation: abstaining on the
 * cases another annotation owns, and turning a set of missing classes into a
 * message. The rule was extracted rather than copied because Stream B's Resource
 * Master generates temporary passwords and needs to produce strings this would
 * accept — see that class for why a second statement of the policy would drift
 * without anything failing.
 */
class PasswordComplexityValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // Null and blank are @NotBlank's job, and length is @Size's. Reporting
        // "needs a symbol" for a field the user left empty is noise on top of
        // the message they actually need, so this abstains and lets the
        // annotation that owns the case speak.
        if (password == null || password.isEmpty()) {
            return true;
        }

        // Composition only — deliberately not PasswordComplexity.isCompliant,
        // which folds in the length bounds. Those belong to @Size on the field,
        // and answering for them here would refuse a short password with a
        // message about character classes it already has.
        List<PasswordComplexity.CharacterClass> missing = PasswordComplexity.missingClasses(password);
        if (missing.isEmpty()) {
            return true;
        }

        // The default message names all four rules; this replaces it with the
        // ones actually missing. Note what is NOT interpolated: the password.
        // A validation message is echoed in the response body and written to
        // every log the response passes, so quoting the rejected value would
        // scatter a near-miss password across the estate.
        String named = missing.stream()
                .map(PasswordComplexity.CharacterClass::description)
                .collect(Collectors.joining(", "));

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("Password must contain " + named + ".")
                .addConstraintViolation();
        return false;
    }
}
