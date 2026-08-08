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
 * @param accessToken the 15-minute JWT minted by {@code AccessTokenIssuer}.
 * @param expiresIn   access-token lifetime in seconds — 900, per §10.1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An authenticated session.")
record Session(

        @Schema(description = "15-minute JWT. Claims: sub, role, permissions[], projects[], reportees[], iat, exp, jti.")
        String accessToken,

        Integer expiresIn,

        @Schema(description = "True when the user must set a new password before continuing (A-026).")
        boolean mustChangePassword,

        Me user
) {

    /** A-022 · the authenticated identity plus the token minted for it. */
    static Session issue(AuthenticatedUser user, AccessToken token) {
        return new Session(token.value(), token.expiresInSeconds(), user.mustChangePassword(), Me.from(user));
    }
}
