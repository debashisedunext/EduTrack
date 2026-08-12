package com.edunext.edutrack.api.feature.tickets;

/**
 * C-012 · a level that is not a code in the priority master (S-12).
 *
 * <p><b>400, not a silent "no SLA".</b> The alternative was to let an
 * unrecognised level fall off the end of the resolution ladder and answer with
 * no planned close date, which is indistinguishable on screen from a correctly
 * spelled level that no policy covers — and the two need opposite fixes. One is
 * a typo in a caller; the other is an unconfigured SLA matrix.
 *
 * <p>Validated against the master rather than an enum because S-12 lets an
 * administrator add a level without a release, so the set of valid codes is data
 * (blueprint §9, S-12). {@code Level} is a fixed four-value enum in the contract
 * and the generated client can only send one of those, which is exactly why this
 * has to be checked server-side: the day a fifth level is added, the contract is
 * the thing that will be behind.
 *
 * <p>Unlike {@link UnknownProjectException}, the offending value <b>is</b> safe
 * to echo — a level code is not a row id and confirming that "URGENT" is not a
 * level leaks nothing about what exists.
 */
class UnknownLevelException extends RuntimeException {

    private final String level;

    UnknownLevelException(String level) {
        super("No priority with code " + level);
        this.level = level;
    }

    String level() {
        return level;
    }
}
