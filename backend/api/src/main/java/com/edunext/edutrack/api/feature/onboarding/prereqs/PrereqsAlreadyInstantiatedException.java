package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-125 · this client already has a checklist.
 *
 * <p>409, and refused rather than answered with the existing header. A second
 * instantiation is a bug in the caller — boarding runs once — and returning
 * the existing row would hide it while leaving the caller believing it had
 * snapshotted the version it just read.
 */
class PrereqsAlreadyInstantiatedException extends RuntimeException {

    PrereqsAlreadyInstantiatedException(long obClientId) {
        super("client " + obClientId + " already has a prerequisite checklist; "
                + "a client is boarded onto the master once");
    }
}
