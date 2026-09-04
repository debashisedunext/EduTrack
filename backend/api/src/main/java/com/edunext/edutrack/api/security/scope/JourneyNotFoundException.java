package com.edunext.edutrack.api.security.scope;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * A-112 · the one answer for "no such journey" and "not one of yours".
 *
 * <p>{@link TicketNotFoundException}'s reasoning, applied to the second module,
 * and deliberately a separate class rather than a shared "NotFound" with a
 * noun passed in. Read that class for the argument in full; the parts that
 * matter here are that the two cases are <b>the same exception thrown from the
 * same line</b>, that there is no reason field to leak which one applied, and
 * that {@code detail} is a fixed string never derived from the id or caller.
 *
 * <p>The onboarding plan §3 states the rule in its own words — "Enforcement is
 * server-side: {@code OnboardingScopeResolver} for staff,
 * {@code ClientScopeResolver} for the client principal; out-of-scope → 404" —
 * so this is the same no-existence-leak contract A-111's ModuleGuard keeps when
 * it refuses an unentitled caller, and for the same reason: a 403 anywhere in
 * this module would confirm the journey exists.
 *
 * <p>A distinct {@code type} URI because the two are genuinely different
 * resources and a client should be able to tell "journey not found" from
 * "ticket not found" — that discloses which endpoint was called, which the
 * caller already knows, and nothing about which of the two cases applied.
 */
public class JourneyNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/journey-not-found");

    public JourneyNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Journey not found");
        problem.setDetail("No onboarding journey with that identifier is available to you.");
        return problem;
    }
}
