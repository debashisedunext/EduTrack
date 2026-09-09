package com.edunext.edutrack.api.feature.portal;

import org.springframework.stereotype.Component;

/**
 * A-130 · what a portal password has to be.
 *
 * <h2>Its own rules rather than staff's {@code PasswordPolicy}</h2>
 *
 * <p>The obvious move is to reuse the class that already encodes §10.3. It does
 * not fit, and not merely because it is package-private in {@code feature.auth}:
 * every method on it is keyed on a {@code users} id. {@code enforceNotReused}
 * reads {@code password_history} by user, {@code recordRetired} writes it, and
 * {@code isExpired} judges {@code users.password_changed_at}. A client account
 * is deliberately not a user — {@code client_credential_tokens} exists as its
 * own table for exactly that reason — so reusing it would mean either widening
 * those queries to accept two kinds of id, or handing this path a user id it
 * does not have.
 *
 * <p>So: the same <em>strength</em> rule, none of the history. Reuse and expiry
 * are not implemented for portal accounts and are not silently pretended at —
 * there is no {@code client_password_history} table, and inventing one inside a
 * login task would be schema by side effect.
 *
 * <h2>Length before composition</h2>
 *
 * <p>Twelve rather than staff's eight. A staff password is chosen by somebody
 * inside an organisation with a lockout policy, a directory and a helpdesk; a
 * portal password is chosen once, by a client contact, on a form reachable from
 * the internet, and is then rarely thought about again. The cheapest defence
 * available at that moment is length.
 */
@Component
class PortalPasswordRules {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 200;

    /**
     * @throws PortalAuthExceptions.WeakPortalPassword naming the one rule that
     *         failed, so the form can say what to fix rather than restating the
     *         whole policy after every attempt
     */
    void enforce(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new PortalAuthExceptions.WeakPortalPassword("Choose a password.");
        }
        if (candidate.length() < MIN_LENGTH) {
            throw new PortalAuthExceptions.WeakPortalPassword(
                    "Use at least " + MIN_LENGTH + " characters.");
        }
        /*
          Bounded before hashing, not after. Argon2id's cost is a function of
          what it is given, so an unbounded field is a way to spend the server's
          memory and CPU from an unauthenticated route — the same reason
          `LoginRequest` bounds its own.
        */
        if (candidate.length() > MAX_LENGTH) {
            throw new PortalAuthExceptions.WeakPortalPassword(
                    "That password is longer than " + MAX_LENGTH + " characters.");
        }
        if (candidate.chars().noneMatch(Character::isUpperCase)
                || candidate.chars().noneMatch(Character::isLowerCase)) {
            throw new PortalAuthExceptions.WeakPortalPassword(
                    "Use both upper and lower case letters.");
        }
        if (candidate.chars().noneMatch(Character::isDigit)) {
            throw new PortalAuthExceptions.WeakPortalPassword("Include a digit.");
        }
        if (candidate.chars().allMatch(Character::isLetterOrDigit)) {
            throw new PortalAuthExceptions.WeakPortalPassword(
                    "Include a symbol, such as ! ? # or -.");
        }
    }
}
