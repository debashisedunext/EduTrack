package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 400 — {@code kind} and {@code stepId} do not agree.
 *
 * <p>{@code ck_ob_signoffs_step_matches_kind} is the database's version of this
 * rule and would refuse the insert anyway. The contract asks for the refusal
 * earlier and by name — "this answers 400 before it gets there" — because a
 * constraint violation surfacing from a route nobody is debugging says nothing
 * about which of the two fields was wrong.
 *
 * <p><b>A {@code GO_LIVE} request carrying a {@code stepId} is refused, not
 * ignored.</b> {@code DevSignoffSimulationService} ignores it on the grounds
 * that the caller is describing a journey-wide acceptance while naming the step
 * they happened to be looking at — fair for a demo helper driven by one button.
 * It is the wrong call on the real route: this one is reached by a generated
 * client, and silently dropping a field somebody deliberately sent is how a
 * caller comes to believe they requested a step sign-off and got one.
 */
class ObSignoffKindMismatchException extends RuntimeException {

    private final String field;

    private ObSignoffKindMismatchException(String field, String message) {
        super(message);
        this.field = field;
    }

    static ObSignoffKindMismatchException stepRequired() {
        return new ObSignoffKindMismatchException("stepId",
                "A STEP sign-off names the service it is about; stepId is required.");
    }

    static ObSignoffKindMismatchException stepForbidden() {
        return new ObSignoffKindMismatchException("stepId",
                "A GO_LIVE sign-off is about the whole journey and must not name a stepId.");
    }

    /** Field-keyed, because the contract's 400 body is {@code ValidationProblem}. */
    String field() {
        return field;
    }
}
