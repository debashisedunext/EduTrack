package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 404 — a sign-off that does not exist, or one that exists and belongs to
 * somebody else.
 *
 * <p>One exception for both, so both render the same problem document. The
 * row-scoping rule this codebase follows everywhere is that an out-of-scope
 * row answers 404 rather than 403, because a 403 confirms the row is there;
 * a separate "not yours" exception would rebuild that leak from the other
 * direction, by existing.
 *
 * <p>Distinct from {@link InvalidSignoffTokenException} on purpose. That one
 * is the public surface's single generic 401 for every way a link can fail,
 * and it is 401 because an unauthenticated caller holding a bad token has not
 * identified themselves at all. A portal caller has — this is about a row,
 * not a credential.
 *
 * <p>Public because {@code PortalSignoffDecisionService} is called from
 * {@code feature/portal/onboarding}, whose exception handler renders it.
 */
public class ObSignoffNotForClientException extends RuntimeException {

    public ObSignoffNotForClientException() {
        super("No sign-off was found at this path");
    }
}
