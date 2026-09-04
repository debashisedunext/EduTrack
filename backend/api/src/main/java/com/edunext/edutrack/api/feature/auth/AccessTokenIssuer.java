package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.api.security.jwt.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * A-022 · turns a verified {@link AuthenticatedUser} into the §10.1 access
 * token — 15 minutes, claims {@code sub}, {@code role}, {@code permissions[]},
 * {@code projects[]}, {@code reportees[]}, {@code iat}, {@code exp},
 * {@code jti}.
 *
 * <p>Deliberately separate from {@link AuthenticationService}: that class's
 * whole reason to exist is credential verification under tight timing and
 * failure-shape constraints, and minting a signed token is a different
 * concern with a different failure mode (a misconfigured secret, not a wrong
 * password). {@link AuthController} composes the two.
 *
 * <p>{@code sub} is the numeric user id, not the username — every other claim
 * here (project and reportee ids) is already id-based, and A-034's
 * {@code ScopeResolver} wants an id to compare against {@code assigned_to},
 * not a name to look up.
 */
@Component
class AccessTokenIssuer {

    /**
     * A-026 · marks a token belonging to an account that has not yet replaced its
     * temporary password. Read by {@link PasswordChangeGate}.
     *
     * <p><b>Emitted only when true.</b> A claim present on every token to say
     * "false" would be 26 bytes on every request to describe the ordinary case;
     * more importantly, absence is the reading that keeps every token minted
     * before this task — and every token for the majority of users — working.
     * The gate documents why fail-open is the correct direction for a claim that
     * lives inside a signature we control.
     */
    static final String MUST_CHANGE_PASSWORD_CLAIM = "mustChangePassword";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    AccessToken issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                // JwtClaimsSet maps id() to the "jti" claim. A fresh one per call —
                // A-025's logout blacklist and A-024's reuse detection both key on it.
                .id(UUID.randomUUID().toString())
                .claim("role", user.roleCode())
                .claim("permissions", user.permissions())
                .claim("projects", user.projectIds())
                .claim("reportees", user.reporteeIds())
                // A-110 · which modules this caller may reach. Read by
                // ModuleAccessGuard (A-111) before RolesGuard on every
                // /api/v1/onboarding/** route.
                //
                // Always stamped, even when empty, unlike mustChangePassword
                // below. That flag is fail-open by design — an absent claim
                // means "no forced change", which keeps older tokens working.
                // This one must be fail-CLOSED: an absent modules claim has to
                // mean "no modules", or a token minted before this task grants
                // access to a module that did not exist when it was signed.
                // Writing it unconditionally means the two readings — absent
                // and empty — never have to be told apart.
                .claim("modules", user.modules());

        // A-026. The flag has to travel in the token, not only in the response
        // body: the body is a suggestion the client may ignore, and this is the
        // copy the server itself will refuse requests on.
        if (user.mustChangePassword()) {
            claims.claim(MUST_CHANGE_PASSWORD_CLAIM, true);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();

        return new AccessToken(token, (int) properties.accessTokenTtl().toSeconds());
    }
}
