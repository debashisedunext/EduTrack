package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * The caller holds no standing in the onboarding module at all.
 *
 * <p><b>404, not 403</b>, on {@code NotAnOnboardingClientWriterException}'s own
 * reasoning: a caller whose scope shows them nothing cannot be told that the
 * create endpoint exists and is merely closed to them, because the same caller
 * reading the list gets an empty page. Answering 403 to the write while
 * answering an empty 200 to the read would make the write endpoint an oracle
 * for a module the caller has no part in.
 */
class NotAnOnboardingProjectWriterException extends RuntimeException {

    NotAnOnboardingProjectWriterException() {
        super("no onboarding project");
    }
}
