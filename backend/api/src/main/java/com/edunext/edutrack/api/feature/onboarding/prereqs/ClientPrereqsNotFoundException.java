package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-125 · this client has no prerequisite checklist.
 *
 * <p>404. A client boarded before the master existed is the realistic case,
 * and it is a gap in their record rather than a bad request: OB-05 shows the
 * accordion empty, and B-109 creates the checklist for every client boarded
 * from now on.
 */
class ClientPrereqsNotFoundException extends RuntimeException {

    ClientPrereqsNotFoundException(long obClientId) {
        super("client " + obClientId + " has no prerequisite checklist");
    }
}
