package com.edunext.edutrack.api.feature.portal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * A-130 · turns the portal's authentication refusals into RFC 9457 problem
 * documents, per {@code CONVENTIONS.md} §3.
 *
 * <p>Scoped to {@link PortalAuthController} rather than declared globally, for
 * {@code AuthExceptionHandler}'s reason: a repository-wide advice is shared
 * surface that four streams would edit, and it is not Stream A's to introduce
 * unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. The URIs below must not
 * change once the portal frontend switches on them.
 */
@RestControllerAdvice(assignableTypes = PortalAuthController.class)
class PortalAuthExceptionHandler {

    /**
     * <b>The same URI the staff refusal uses.</b> It is genuinely the same class
     * of failure — "these credentials are not right" — and
     * {@code CONVENTIONS.md} §3 makes {@code type} what clients branch on, so a
     * second URI meaning the same thing would be one more branch for the portal
     * shell to get wrong.
     */
    private static final URI INVALID_CREDENTIALS = URI.create("https://edutrack/errors/invalid-credentials");
    private static final URI ACCOUNT_LOCKED = URI.create("https://edutrack/errors/account-locked");

    /**
     * Its own URI, distinct from A-027's {@code invalid-reset-token}. Both are
     * spent-or-expired links, but the page that renders this one has to offer
     * "ask your onboarding manager for a new link", whereas the reset page
     * offers a self-service retry — different recoveries, so the client must be
     * able to tell them apart.
     */
    private static final URI INVALID_CREDENTIAL_LINK =
            URI.create("https://edutrack/errors/invalid-credential-link");

    private static final URI WEAK_PASSWORD = URI.create("https://edutrack/errors/weak-password");

    /**
     * One handler, one status, one body — unknown account, wrong password and
     * deactivated account alike. The {@code detail} is a fixed string rather
     * than one derived from the exception, so no future change can leak the
     * distinction {@link PortalAuthExceptions.InvalidPortalCredentials} was
     * designed not to carry.
     */
    @ExceptionHandler(PortalAuthExceptions.InvalidPortalCredentials.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(
            PortalAuthExceptions.InvalidPortalCredentials ignored) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_CREDENTIALS);
        problem.setTitle("Invalid credentials");
        problem.setDetail("The username or password is incorrect.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * {@code 423 Locked} — the only login response that says anything specific,
     * and reachable only once the password has verified.
     *
     * <p>{@code lockedUntil} is an extension member rather than prose, so a
     * client rendering "try again in twelve minutes" reads a timestamp instead
     * of parsing an English sentence somebody may reword.
     */
    @ExceptionHandler(PortalAuthExceptions.PortalAccountLocked.class)
    ResponseEntity<ProblemDetail> handleLocked(PortalAuthExceptions.PortalAccountLocked exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.LOCKED);
        problem.setType(ACCOUNT_LOCKED);
        problem.setTitle("Account locked");
        problem.setDetail("Too many failed sign-in attempts. Try again later, "
                + "or ask your onboarding manager to reset your login.");
        if (exception.lockedUntil() != null) {
            problem.setProperty("lockedUntil", exception.lockedUntil().toString());
        }
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem);
    }

    /**
     * {@code 410 Gone} — expired, already used, or never issued, with one body
     * for all three. The surface is unauthenticated, so telling them apart
     * would let anybody holding a random string learn whether it was ever real,
     * and whether the account behind it has already been activated.
     */
    @ExceptionHandler(PortalAuthExceptions.InvalidCredentialLink.class)
    ResponseEntity<ProblemDetail> handleInvalidLink(PortalAuthExceptions.InvalidCredentialLink ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setType(INVALID_CREDENTIAL_LINK);
        problem.setTitle("This link is no longer valid");
        problem.setDetail("This sign-in link has expired or has already been used. "
                + "Ask your onboarding manager to send a new one.");
        return ResponseEntity.status(HttpStatus.GONE).body(problem);
    }

    /**
     * 400, and the one refusal on this surface that names its reason.
     *
     * <p>Safe because the caller is redeeming a link only they could have
     * received, and necessary because a rule somebody has to satisfy is a rule
     * they have to be told. The {@code detail} comes from the exception here,
     * unlike every handler above — the message names one failed rule so the
     * form can say what to fix instead of restating the whole policy.
     */
    @ExceptionHandler(PortalAuthExceptions.WeakPortalPassword.class)
    ResponseEntity<ProblemDetail> handleWeakPassword(PortalAuthExceptions.WeakPortalPassword exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(WEAK_PASSWORD);
        problem.setTitle("Password is not strong enough");
        problem.setDetail(exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
