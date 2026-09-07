package com.edunext.edutrack.domain.onboarding;

import java.util.Collection;

/**
 * C-114 · RAG computation — Green through the configured threshold, Amber
 * from there, Red on breach, and Red early for a step that is
 * {@link ObJourneyStepStatus#BLOCKED} past the threshold. Journey and
 * client roll-up is the worst of their members, computed by the same
 * {@link #worstOf} the schema's own "worst-wins upward" describes.
 *
 * <p><b>What this class deliberately does not do.</b> It does not compute
 * {@code tatConsumedPercent} itself — {@link ObJourneyStepRagService} turns
 * a step's C-105 fields ({@code due_at}, {@code tatDays}, the clock-event
 * trail) into that number and calls here; keeping the arithmetic out of
 * this class is what makes {@link #forStep}'s state machine testable with
 * plain doubles rather than a working calendar in every test. C-120's
 * journey-level utilized-hours roll-up (a display concern, "used / total"
 * on the accordion strip) is a separate, still-unbuilt reader of the same
 * clock events — this class does not wait on it, since a single step's
 * percentage never needed the journey-level sum. It does not read the
 * amber threshold from anywhere configurable — there is no settings table
 * for it in any migration, so {@link #DEFAULT_AMBER_THRESHOLD_PERCENT}
 * (module plan §1 decision 3's "default 75%") is the only value in use
 * today, passed as an explicit parameter so a future config read is a
 * caller change, not a signature change. It does not persist, scan or
 * notify — that is C-113 (the TAT scanner sweeps steps and calls this to
 * decide what flips) and C-115 (the escalation matrix acts on what it
 * flips to). Every one of those callers can depend on this today: it lives
 * in {@code domain}, not {@code api}, because {@code worker} — where
 * C-113's scanner package lands — depends on {@code domain} and not on
 * {@code api}, the same reason {@link ObGateStatus} and {@link
 * ObJourneyStepStatus} sit here rather than beside the DTOs that render
 * them.
 *
 * <p><b>Why {@code BLOCKED} gets its own branch.</b> Plan §5.7: {@code
 * WAITING_ON_CLIENT} pauses the clock, internal {@code BLOCKED} does not.
 * A blocked step therefore keeps accruing {@code tatConsumedPercent} like
 * any running step, which would eventually carry it to Red on breach
 * regardless — but a step nobody is actively working, already past the
 * warn threshold, is a worse signal than a step still being worked that
 * merely crossed the same threshold. Promoting it to Red at the threshold
 * rather than waiting for the 100% breach is what "blocked-past-threshold"
 * in the task's own line means; it is a one-branch difference from an
 * ordinary running step, not a separate state machine.
 *
 * <p><b>Which statuses have no colour at all.</b> {@link
 * ObJourneyStepStatus#PENDING} (never activated — gate still locked, or a
 * dependency not yet met: "nothing running to colour", the same reasoning
 * the contract gives a {@code LOCKED} journey) and {@link
 * ObJourneyStepStatus#SKIPPED} (an Admin override bypasses judgment, it
 * does not earn Green) both return {@code null}. Every other status —
 * including {@code DONE} — is coloured from its (possibly final, frozen)
 * {@code tatConsumedPercent}: a service that finished having breached
 * stays Red, on the same precedent OB-05's own emoji design pairs "closed
 * delayed" with "running breached" under one marker rather than treating
 * completion as a fresh start.
 */
public final class ObRagCalculator {

    /** Module plan §1 decision 3: "Amber before Red — default 75%." No config table exists to read this from yet. */
    public static final int DEFAULT_AMBER_THRESHOLD_PERCENT = 75;

    private ObRagCalculator() {
    }

    /** {@link #forStep(double, ObJourneyStepStatus, int)} at {@link #DEFAULT_AMBER_THRESHOLD_PERCENT}. */
    public static ObRag forStep(double tatConsumedPercent, ObJourneyStepStatus status) {
        return forStep(tatConsumedPercent, status, DEFAULT_AMBER_THRESHOLD_PERCENT);
    }

    /**
     * One step's health. {@code tatConsumedPercent} is working-calendar
     * time consumed against the step's TAT, as a percentage — 100 is
     * exactly on time, above 100 is breached. Never negative, never
     * validated here: the caller (C-105/C-120's percentage maths) owns
     * that guarantee.
     */
    public static ObRag forStep(double tatConsumedPercent, ObJourneyStepStatus status, int amberThresholdPercent) {
        return switch (status) {
            case PENDING, SKIPPED -> null;
            case IN_PROGRESS, WAITING_ON_CLIENT, BLOCKED, DONE -> {
                if (tatConsumedPercent >= 100) {
                    yield ObRag.RED;
                }
                if (status == ObJourneyStepStatus.BLOCKED && tatConsumedPercent >= amberThresholdPercent) {
                    yield ObRag.RED;
                }
                yield tatConsumedPercent >= amberThresholdPercent ? ObRag.AMBER : ObRag.GREEN;
            }
        };
    }

    /**
     * Journey-from-steps or client-from-journeys roll-up: worst of the
     * members, ignoring {@code null}s (a step never started, a journey
     * still {@code LOCKED}), {@code null} itself if nothing in the
     * collection has a colour — which is exactly "every journey is
     * LOCKED" read at the client level, the contract's own "Prerequisites
     * pending" case, with no separate filtering for gate status needed:
     * a locked journey's {@code rag} is already {@code null} before it
     * ever reaches this method.
     */
    public static ObRag worstOf(Collection<ObRag> values) {
        boolean amber = false;
        boolean green = false;
        for (ObRag value : values) {
            if (value == ObRag.RED) {
                return ObRag.RED;
            }
            if (value == ObRag.AMBER) {
                amber = true;
            } else if (value == ObRag.GREEN) {
                green = true;
            }
        }
        if (amber) {
            return ObRag.AMBER;
        }
        return green ? ObRag.GREEN : null;
    }
}
