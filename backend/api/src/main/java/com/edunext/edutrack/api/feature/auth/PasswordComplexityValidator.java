package com.edunext.edutrack.api.feature.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A-028 · the four character classes of blueprint §10.3, checked one at a time
 * so the refusal can say which one is missing.
 *
 * <p>Stateless and thread-safe; Hibernate Validator holds a single instance.
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

        List<String> missing = new ArrayList<>(4);
        if (!hasUpper(password)) missing.add("an upper-case letter");
        if (!hasLower(password)) missing.add("a lower-case letter");
        if (!hasDigit(password)) missing.add("a digit");
        if (!hasSymbol(password)) missing.add("a symbol");

        if (missing.isEmpty()) {
            return true;
        }

        // The default message names all four rules; this replaces it with the
        // ones actually missing. Note what is NOT interpolated: the password.
        // A validation message is echoed in the response body and written to
        // every log the response passes, so quoting the rejected value would
        // scatter a near-miss password across the estate.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("Password must contain " + String.join(", ", missing) + ".")
                .addConstraintViolation();
        return false;
    }

    /**
     * Deliberately {@code Character.isUpperCase} rather than {@code [A-Z]}.
     * Passwords are {@code utf8mb4} end to end, and a rule that only recognises
     * ASCII tells a user whose name supplies their memorable phrase that their
     * alphabet does not count as letters.
     */
    private static boolean hasUpper(String password) {
        return password.chars().anyMatch(Character::isUpperCase);
    }

    private static boolean hasLower(String password) {
        return password.chars().anyMatch(Character::isLowerCase);
    }

    private static boolean hasDigit(String password) {
        return password.chars().anyMatch(Character::isDigit);
    }

    /**
     * "Symbol" as everything that is not a letter, a digit or whitespace —
     * rather than a fixed list like {@code !@#$%^&*}.
     *
     * <p>An allow-list is the common implementation and it quietly refuses
     * perfectly good passwords: {@code £}, {@code €}, {@code —} and every
     * currency or punctuation mark outside someone's chosen eight are rejected
     * with a message insisting the user add a symbol they have just added.
     * Defining the class by exclusion has no such gap.
     *
     * <p>Whitespace is excluded from counting as a symbol — a trailing space is
     * the most common accidental "symbol" there is, and letting it satisfy the
     * rule would mean {@code "Password1 "} passes while teaching nothing.
     * Whitespace is still <i>permitted</i> in a password; it just does not
     * discharge this requirement.
     */
    private static boolean hasSymbol(String password) {
        return password.chars().anyMatch(c ->
                !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }
}
