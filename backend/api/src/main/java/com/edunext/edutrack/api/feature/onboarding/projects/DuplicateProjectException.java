package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * This client already has a project for this product.
 *
 * <p>{@code uq_ob_projects_client_product} is what makes it true under a race;
 * this check exists so the answer is a sentence naming the existing project
 * rather than a constraint violation naming a MySQL index.
 *
 * <p>It carries the existing project's id so the screen can offer to open it,
 * which is what somebody who hit this almost always wanted. Naming it is safe:
 * the caller has just demonstrated they can see the client, and the scope rule
 * makes a project visible exactly when its client is.
 */
class DuplicateProjectException extends RuntimeException {

    private final long existingProjectId;

    DuplicateProjectException(long existingProjectId, String existingName) {
        super("this client already has the project \"" + existingName + "\" for that product");
        this.existingProjectId = existingProjectId;
    }

    long existingProjectId() {
        return existingProjectId;
    }
}
