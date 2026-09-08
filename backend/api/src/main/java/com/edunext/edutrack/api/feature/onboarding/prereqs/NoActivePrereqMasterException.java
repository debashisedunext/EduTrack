package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-125 · nothing has been published to snapshot from.
 *
 * <p>409. Boarding a client against no checklist would give them a gate with
 * nothing in it, which clears itself immediately — every journey would open
 * at boarding and the gate would look present while doing nothing. That is the
 * same failure B-124 refuses at publish time for an all-optional checklist,
 * met from the other side.
 */
class NoActivePrereqMasterException extends RuntimeException {

    NoActivePrereqMasterException() {
        super("no version of the prerequisites master is active; publish one in OB-14 "
                + "before boarding a client");
    }
}
