package com.edunext.edutrack.api.feature.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A-020 · {@code Session} in {@code contracts/openapi.yaml}.
 *
 * <p><b>Scope note — read before assuming this is finished.</b> A-020 is the
 * login endpoint: credential verification with Argon2id, uniform timing and a
 * generic failure. Token issuance is <b>A-022</b> and is not in this branch, so
 * {@code accessToken} and {@code expiresIn} are absent from the response today
 * rather than present and null — {@link JsonInclude.Include#NON_NULL} keeps a
 * field the client cannot use out of the payload entirely.
 *
 * <p>That makes today's response a strict subset of the contract's, so A-022
 * only adds fields. The contract's own rule (§1 of {@code CONVENTIONS.md}) is
 * that adding an optional field is not a breaking change, which is why this
 * sequencing is safe. It also means <b>the frontend cannot complete a login
 * against this endpoint until A-022 lands</b>; S-01 (A-030) should not be
 * started expecting a token.
 *
 * @param expiresIn access-token lifetime in seconds — 900, per §10.1. Set by A-022.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An authenticated session.")
record Session(

        @Schema(description = "15-minute JWT. Issued by A-022; absent until then.")
        String accessToken,

        Integer expiresIn,

        @Schema(description = "True when the user must set a new password before continuing (A-026).")
        boolean mustChangePassword,

        Me user
) {

    /**
     * The A-020 shape: identity established, no token yet. A-022 replaces this
     * factory with one that takes the minted token and its lifetime.
     */
    static Session withoutToken(AuthenticatedUser user) {
        return new Session(null, null, user.mustChangePassword(), Me.from(user));
    }
}
