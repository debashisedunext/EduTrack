package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * Issues a client's portal login on behalf of the add-client dialog.
 *
 * <h2>Why this interface exists at all</h2>
 *
 * <p>The portal account lives in {@code feature.portal} and the dependency
 * between the two packages runs one way: {@code portal} imports
 * {@link ObClientScope} and reads {@code ob_clients}, and nothing in
 * {@code onboarding.clients} has ever imported {@code portal}. Having
 * {@code ObClientWriteService} call {@code ClientAccountAdminService} directly
 * would make that a cycle, on the first day anybody wanted the two to happen in
 * one request.
 *
 * <p>So the direction is preserved by inverting the call: the interface belongs
 * to the package that needs the work done, and {@code portal} supplies the
 * implementation. {@code ClientAccountAdminService} stays the only thing that
 * knows how a portal account is built, which is the point — the wizard flag is
 * a second caller of one credential path, not a second copy of it.
 *
 * <h2>Deliberately narrower than the panel's API</h2>
 *
 * <p>Only create is here. Reset, disable and re-enable are decisions an
 * operator makes later, on the account panel, with the client in front of them;
 * none of them has any meaning at the moment a company is first recorded.
 * Exposing them through this seam would invite a caller that has no business
 * making them.
 */
public interface ObClientPortalLoginIssuer {

    /**
     * Creates the login for a client that already has an active primary SPOC.
     *
     * <p>Runs inside the caller's transaction, which is what makes the whole
     * add — company, SPOC and login — one unit. A failure here takes the client
     * with it rather than leaving a company whose operator was told a login was
     * coming.
     *
     * @param scope       the caller's row scope, applied by the implementation
     *                    exactly as it is on the panel's own route.
     * @param obClientId  the client, which must exist within {@code scope}.
     * @param actorUserId the staff user to stamp the credential against.
     * @return the username, and the temporary password the client signs in with
     *         once before being made to change it.
     */
    IssuedLogin issueFor(ObClientScope scope, long obClientId, Long actorUserId);

    /**
     * What the dialog needs to show, and nothing more.
     *
     * <p>{@code password} is the temporary one just set on the account. The
     * operator hands it to the client, the client signs in with it, and the
     * portal then refuses them everything but the change-password form until
     * they have chosen their own - see {@code PortalPasswordChangeGate}. So it
     * is a credential on a staff screen for exactly as long as it takes to be
     * used once, which is the bargain
     * {@code PortalTemporaryPasswordProperties} sets out.
     *
     * <p><b>It can still be null</b>, where a deployment has switched the flow
     * off and gone back to the link-only behaviour. A screen showing this has
     * to treat null as "we mailed them a link" rather than as a missing value,
     * because on such a deployment that is exactly what happened.
     */
    record IssuedLogin(String username, String password) {
    }
}
