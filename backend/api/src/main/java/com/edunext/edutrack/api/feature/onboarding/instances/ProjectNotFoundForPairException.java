package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * No {@code ob_projects} row for this client and product — there is nothing for
 * the journeys to belong to.
 *
 * <p>Since {@code V20260911_1800} a journey has a project, and the project is
 * the row that carries the name, the start date and the implementor. This
 * service <b>resolves</b> one and never creates one: a project invented here
 * would be named by a formula, started on a date nobody chose and owned by
 * nobody, and it would appear on the Projects grid looking exactly like one
 * somebody filled in. Creating it is the caller's job because the caller is
 * where those answers are.
 *
 * <p>Reaching this is a programming error rather than a user's mistake — the
 * migration backfilled a project for every pair that existed, and every path
 * that creates a purchase from here on creates a project in the same
 * transaction. It is unchecked and uncaught for that reason: a 500 naming the
 * pair is the right outcome, because the fix is in code.
 */
public class ProjectNotFoundForPairException extends RuntimeException {

    ProjectNotFoundForPairException(long obClientId, long productId) {
        super("client " + obClientId + " has no project for product " + productId);
    }
}
