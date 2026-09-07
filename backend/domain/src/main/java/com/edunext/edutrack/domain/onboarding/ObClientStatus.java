package com.edunext.edutrack.domain.onboarding;

/**
 * B-102 · {@code ob_clients.overall_status} — the four values
 * {@code ck_ob_clients_status} admits, and the one of them nobody may type.
 *
 * <p>{@link #LIVE} is <b>earned, never set</b>. It is the go-live flip that
 * fires when every one of a client's journeys completes with its sign-offs
 * (onboarding plan §5.9), and {@code PATCH /onboarding/clients/{obClientId}}
 * answers {@code 422 ob-client-live-not-earned} to a request for it. The
 * migration's own header says the schema cannot enforce that and names it here
 * "so a later writer does not add a setter and assume the schema was
 * indifferent" — {@link #settableByHand()} is that note made executable.
 *
 * <p>The other three are judgements a person records, and two of them —
 * {@link #ON_HOLD} and {@link #DROPPED} — carry a mandatory
 * {@code status_reason}: a client that stopped moving is a fact somebody will
 * have to explain months later, and the explanation is worth nothing if it was
 * optional at the moment it was known.
 */
public enum ObClientStatus {

    /** The ordinary state of a boarded client: journeys running, nothing finished. */
    ONBOARDING,

    /**
     * Every journey complete with its sign-offs. Derived by the go-live flip
     * (plan §5.9, C's), never accepted from a request.
     */
    LIVE,

    /** Paused by agreement. Reason mandatory. */
    ON_HOLD,

    /** Abandoned. Reason mandatory, and history is kept rather than deleted. */
    DROPPED;

    /** True for the three a person may record. {@link #LIVE} is the one that is not. */
    public boolean settableByHand() {
        return this != LIVE;
    }

    /** {@link #ON_HOLD} and {@link #DROPPED} — the two that cannot be recorded silently. */
    public boolean requiresReason() {
        return this == ON_HOLD || this == DROPPED;
    }
}
