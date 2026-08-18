package com.edunext.edutrack.api.feature.tickets.effort;

/**
 * C-035 · 400 — {@code isCorrection: true} with no {@code correctsEntryId}.
 *
 * <p>Bean Validation cannot express this: {@code correctsEntryId} is legitimately
 * null on an ordinary entry, so a blanket {@code @NotNull} would refuse the
 * common case to catch the rare one. The condition is a relationship between two
 * sibling fields, which is exactly what {@link EffortLogService} exists to check
 * before anything reaches the append-only journal.
 */
class EffortCorrectionTargetRequiredException extends RuntimeException {

    EffortCorrectionTargetRequiredException() {
        super("isCorrection is true but correctsEntryId is missing — a correction has to name the "
                + "entry it reverses.");
    }
}
