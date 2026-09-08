package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124 · refused at publish time, and deliberately not left to surface
 * later.
 *
 * <p>A checklist with nothing mandatory clears its own gate the moment it
 * is instantiated: every journey would open at boarding, and the gate would
 * look present while doing nothing. Plan §5.3 calls the prerequisite gate a
 * hard one, and this is where that is enforced — 422 rather than 409,
 * because the draft is a real, addressable thing and what is wrong is the
 * content the caller is asking to make live.
 */
class PrereqTemplateHasNoMandatoryTaskException extends RuntimeException {

    PrereqTemplateHasNoMandatoryTaskException(int version) {
        super("version " + version + " of the prerequisites master has no mandatory task; "
                + "publishing it would leave every journey's gate open at boarding");
    }
}
