package com.edunext.edutrack.api.feature.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A-020/A-022 · {@code Session} in {@code contracts/openapi.yaml}.
 *
 * <p>{@code mustChangePassword} is still meaningful even though A-026 (the
 * forced-change redirect) has not landed: the token is issued regardless, and
 * the frontend uses this flag to route to the reset screen instead of letting
 * a must-change account act on a token it should not be trusted with yet.
 *
 * @param accessToken  the 15-minute JWT minted by {@code AccessTokenIssuer}.
 * @param expiresIn    access-token lifetime in seconds — 900, per §10.1.
 * @param landingRoute A-031 · where this role belongs after signing in.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An authenticated session.")
record Session(

        @Schema(description = "15-minute JWT. Claims: sub, role, permissions[], projects[], reportees[], iat, exp, jti.")
        String accessToken,

        Integer expiresIn,

        @Schema(description = "True when the user must set a new password before continuing (A-026).")
        boolean mustChangePassword,

        @Schema(description = "Role-based post-login destination, decided server-side (A-031). "
                + "Admin and PM to the dashboard, Developer to My Tasks, Support to the ticket "
                + "queue, QA and Deployment to the stage queue.")
        String landingRoute,

        Me user
) {

    /**
     * A-022 · the authenticated identity plus the token minted for it.
     *
     * <p><b>The landing route is resolved even when {@code mustChangePassword}
     * is set.</b> S-03 sends the user onward once they have set a new password,
     * and it reads this field to know where — so omitting it here would land
     * every first-time sign-in on the frontend's fallback, which is the one
     * journey where the role-based destination is most useful and the one place
     * a missing value is hardest to notice.
     */
    static Session issue(AuthenticatedUser user, AccessToken token) {
        return new Session(
                token.value(),
                token.expiresInSeconds(),
                user.mustChangePassword(),
                LandingRoutes.forRole(user.roleCode()),
                Me.from(user));
    }
}
