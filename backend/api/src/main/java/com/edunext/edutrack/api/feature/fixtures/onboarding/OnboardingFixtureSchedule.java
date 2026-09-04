package com.edunext.edutrack.api.feature.fixtures.onboarding;

import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.JourneySpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.StepSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * B-101 · the prototype's {@code mkStepsFrom} walk, in Java.
 *
 * <p>The prototype does not store a status or a date per step; it derives both
 * from one number — how far the journey has run — and a small deterministic
 * rule about which completed steps closed early, on time or late. Reproducing
 * that rule rather than transcribing its output is what keeps the corpus
 * <em>coherent</em>: the eight clients differ only in where they are along the
 * same journey, exactly as the design intends, and a step's start, finish and
 * status can never disagree because one function produces all three.
 *
 * <p>Split out of {@link OnboardingFixture} so it can be tested without a
 * database. The walk is the one piece of real logic in this package, and it is
 * the piece that decides whether the corpus has an overdue step in it at all.
 *
 * <h2>The rule, from the prototype</h2>
 *
 * <pre>
 * closed = i%4===1 ? "early" : (i%5===3 ? "late" : "ontime")
 * span   = max(1, tat + (late ? +2 : early ? -ceil(tat/3) : 0))
 * </pre>
 *
 * <p>Each completed step then occupies {@code [cur, cur+span]} and the next one
 * starts a day later. The in-flight step starts wherever the walk had reached,
 * or today if the walk has already run past it.
 *
 * <p><b>Serials are calendar days, not working days</b> — the prototype's own
 * comment says "working days approximated as calendar days" and this walk keeps
 * that approximation. It decides where a step <em>started</em>, which is a fact
 * about the past. Where it is <em>due</em> is a different question, is not the
 * prototype's to answer, and {@link OnboardingFixture} computes it from
 * {@code tat_days} through the working calendar.
 */
final class OnboardingFixtureSchedule {

    private OnboardingFixtureSchedule() {
    }

    /**
     * One step's place in the journey.
     *
     * @param status        an {@code ob_journey_steps.status} value
     * @param startSerial   {@code null} for a step that has not begun
     * @param finishSerial  {@code null} unless the step is {@code DONE}
     * @param inFlight      true for the one step the journey is sitting on —
     *                      the prototype's {@code CURRENT}, whatever status the
     *                      journey overrode it to
     */
    record StepSchedule(String status, Integer startSerial, Integer finishSerial, boolean inFlight) {

        boolean done() {
            return "DONE".equals(status);
        }
    }

    /** The whole journey: one entry per step, plus the journey's own dates. */
    record JourneySchedule(List<StepSchedule> steps, Integer startSerial, Integer completedSerial) {
    }

    /**
     * Walk one journey.
     *
     * <p>A journey whose gate is locked, or which is held behind another
     * service, has not started: every step is {@code PENDING} and nothing has a
     * date. The prototype does this in two places — the {@code CLIENTS[6]} block
     * that locks Little Scholars' gate, and {@code heldJourney} — by building
     * the walk and then flattening step 0 back to {@code PENDING}. Flattening
     * the whole journey up front says the same thing and cannot leave a step
     * dated before the journey it belongs to.
     */
    static JourneySchedule walk(List<StepSpec> steps, JourneySpec journey) {
        if (!journey.gateOpen() || journey.heldByTemplateKey() != null) {
            List<StepSchedule> pending = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                pending.add(new StepSchedule("PENDING", null, null, false));
            }
            return new JourneySchedule(List.copyOf(pending), null, null);
        }

        List<StepSchedule> walked = new ArrayList<>();
        int cursor = journey.startSerial();
        Integer journeyStart = null;
        Integer completed = null;

        for (int i = 0; i < steps.size(); i++) {
            StepSpec step = steps.get(i);

            if (i < journey.currentIndex()) {
                int span = span(i, step.tatDays());
                // Clamped at today, which the prototype does not do and needs to.
                // Its walk approximates working days as calendar days, so a long
                // journey can run the cursor past TODAY_SER and hand a *completed*
                // step a finish date in the future — Horizon Academy's "Admin &
                // user training" lands two days out. On a display string nobody
                // notices; in a database it is a step that finished tomorrow, in
                // front of an in-flight step that started today, and every
                // "completed in the last N days" read over the corpus is wrong.
                int start = Math.min(cursor, OnboardingFixtureData.TODAY_SERIAL);
                int finish = Math.min(cursor + span, OnboardingFixtureData.TODAY_SERIAL);
                walked.add(new StepSchedule("DONE", start, finish, false));
                if (journeyStart == null) {
                    journeyStart = start;
                }
                completed = finish;
                cursor = finish + 1;
            } else if (i == journey.currentIndex()) {
                int start = Math.min(cursor, OnboardingFixtureData.TODAY_SERIAL);
                if (journeyStart == null) {
                    journeyStart = start;
                }
                completed = null;
                walked.add(new StepSchedule(inFlightStatus(journey), start, null, true));
            } else {
                walked.add(new StepSchedule("PENDING", null, null, false));
            }
        }

        return new JourneySchedule(List.copyOf(walked), journeyStart, completed);
    }

    /**
     * The prototype's own {@code closed}/{@code span} rule.
     *
     * <p>The arithmetic is the point: it is what puts genuinely varied durations
     * in the corpus without anyone hand-picking them, so the TAT compliance
     * report has early, on-time and late rows to distinguish rather than one
     * shape repeated eight times.
     */
    private static int span(int index, int tatDays) {
        int adjustment;
        if (index % 4 == 1) {
            adjustment = -Math.ceilDiv(tatDays, 3);      // early
        } else if (index % 5 == 3) {
            adjustment = 2;                              // late
        } else {
            adjustment = 0;                              // on time
        }
        return Math.max(1, tatDays + adjustment);
    }

    /**
     * The in-flight step's status.
     *
     * <p>The prototype's {@code CURRENT} is not a status in A-104's vocabulary —
     * it is "the step the journey is sitting on", which the schema expresses as
     * {@code IN_PROGRESS} unless something more specific is true of it. The
     * journey's own override carries those: {@code WAITING_ON_CLIENT} for
     * Bluebell, {@code BLOCKED} for Trinity.
     */
    private static String inFlightStatus(JourneySpec journey) {
        return journey.currentStatus() == null ? "IN_PROGRESS" : journey.currentStatus();
    }
}
