package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · this caller may not board clients — 404, not 403.
 *
 * <p>{@code ModuleAccessGuard}'s rule, applied where the guard itself is not
 * yet wired into {@code SecurityConfig}: a caller with no standing in the
 * onboarding module must not be able to tell the module apart from a typo,
 * because a 403 on {@code /api/v1/onboarding/clients} discloses that the module
 * is deployed — a fact about what the organisation bought, told to somebody the
 * organisation decided should not have it.
 *
 * <p>None of blueprint §2's six ticketing roles carries any onboarding module
 * role, so today this is the answer every one of them receives. That is not a
 * placeholder: it is what "no grant" means, and it stays true after the gate is
 * wired.
 *
 * <p>OB Viewer and OB Step Owner reach it too, and for them 404 is blunter than
 * they deserve — they have standing, just not this one. It is the answer
 * {@code ObJourneyStepLifecycleService} already gives a non-moderator, and
 * splitting the two would mean a create route whose refusal tells an
 * unentitled caller which kind of nothing they hold.
 */
class NotAnOnboardingClientWriterException extends RuntimeException {

    NotAnOnboardingClientWriterException() {
        super("Boarding a client is an OB Admin, Onboarding Manager or Sales action.");
    }
}
