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

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    AccessToken issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
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
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(token, (int) properties.accessTokenTtl().toSeconds());
    }
}
