package com.edunext.edutrack.api.feature.onboarding.clients;

import java.util.List;

/**
 * B-102 · {@code ob-client-name-similar} — 409, and <b>forceable</b>.
 *
 * <p>The contract's reasoning, which is why this is a refusal the caller can
 * override rather than a silent create or a hard stop: <em>"'Acme Pvt Ltd' and
 * 'Acme Private Limited' are frequently two real clients. Modelling it as a
 * forceable conflict rather than a silent create keeps the decision with the
 * person who can tell the two apart, and keeps it out of a query parameter
 * nobody reads twice."</em>
 *
 * <p>Resubmitting with {@code acknowledgeSimilarNames: true} proceeds. Only the
 * name check is forceable; a duplicate PAN never is.
 */
class SimilarClientNameException extends RuntimeException {

    /** One existing client the boarder should look at before proceeding. */
    record Candidate(long id, String name) {
    }

    private final transient List<Candidate> candidates;
    private final int hidden;

    /**
     * @param candidates the similar clients this caller can see, named so the
     *                   wizard can link straight to them
     * @param hidden     how many further matches exist outside the caller's
     *                   scope. <b>Counted rather than named</b>: the boarder
     *                   needs to know somebody has already recorded something
     *                   like this — enough to go and ask — without the
     *                   duplicate guard becoming a way to read another
     *                   salesperson's client list one probe at a time.
     */
    SimilarClientNameException(List<Candidate> candidates, int hidden) {
        super(message(candidates, hidden));
        this.candidates = List.copyOf(candidates);
        this.hidden = hidden;
    }

    List<Candidate> candidates() {
        return candidates;
    }

    int hidden() {
        return hidden;
    }

    private static String message(List<Candidate> candidates, int hidden) {
        if (candidates.isEmpty()) {
            return hidden + " existing client" + (hidden == 1 ? " has" : "s have")
                    + " a similar name but " + (hidden == 1 ? "is" : "are")
                    + " outside your visible clients. Check with an onboarding admin before boarding "
                    + "this one, or resubmit with acknowledgeSimilarNames to proceed.";
        }
        String named = candidates.stream().map(Candidate::name).reduce((a, b) -> a + ", " + b).orElse("");
        String tail = hidden == 0 ? "" : " (and " + hidden + " more outside your visible clients)";
        return "A client with a similar name already exists: " + named + tail
                + ". Resubmit with acknowledgeSimilarNames to proceed.";
    }
}
