package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * No prerequisite master is published, and this client has no checklist yet.
 *
 * <p>Journeys instantiate {@code LOCKED} and the only thing that opens the gate
 * is the checklist clearing — plan §5.3 is explicit that there is no "open gate
 * anyway" override. A project created in this state would hold journeys nothing
 * can ever start: visible, owned, with dead clocks, forever.
 *
 * <p>The clients package refuses the identical thing under the identical name;
 * this is a second copy rather than a widened one because the two carry
 * different {@code type} URIs to different screens and an exception shared
 * between two features is a dependency neither of them needs.
 *
 * <p>422 rather than 400: the request is well formed and the refusal is about
 * the state of the module, which is not something the caller can fix by editing
 * the form. The message says where to go instead.
 */
class NoPublishedPrerequisitesException extends RuntimeException {

    NoPublishedPrerequisitesException() {
        super("no prerequisite master is published, so this project's journeys could never start. "
                + "Publish one on the Prerequisites master first.");
    }
}
