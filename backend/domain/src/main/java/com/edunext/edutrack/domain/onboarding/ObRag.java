package com.edunext.edutrack.domain.onboarding;

/**
 * C-114 · the health colour, per the contract's own {@code ObRag} schema —
 * computed identically at step, journey and client level, worst-wins
 * upward.
 *
 * <p>Deliberately three values, not six. The prototype's client chip folds
 * "on track / at risk / breached / waiting / prerequisites pending / live"
 * into one label; three of those are not health at all — {@code LIVE} is
 * {@link ObClient}'s overall status, "prerequisites pending" is
 * {@link ObGateStatus#LOCKED}, and "waiting on client" is the clock state
 * derived in the API layer. This enum carries health and nothing else, on
 * the schema's own reasoning. Callers that have nothing running to colour
 * (a step that never started, a journey still {@code LOCKED}, a client
 * whose journeys are all locked) use {@code null}, not a fourth member —
 * see {@link ObRagCalculator}.
 */
public enum ObRag {
    GREEN, AMBER, RED
}
