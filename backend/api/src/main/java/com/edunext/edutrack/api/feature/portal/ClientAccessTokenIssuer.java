package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.security.PrincipalType;
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
 * A-130 · mints the access token for a portal caller.
 *
 * <h2>A second issuer, not a branch in the first one</h2>
 *
 * <p>{@code AccessTokenIssuer} takes an {@code AuthenticatedUser} and stamps
 * {@code role}, {@code permissions}, {@code projects}, {@code reportees},
 * {@code modules} and {@code moduleRoles}. A client has none of those, so a
 * shared issuer would need every one of them behind a null check — and the
 * failure mode of getting one wrong is not a broken build, it is a portal token
 * carrying a staff claim that some guard downstream is willing to read.
 *
 * <p>Two issuers, each stamping only what its own principal has, means the
 * claim sets cannot drift into each other by accident. It is the same argument
 * {@link com.edunext.edutrack.api.feature.portal.ClientPrincipal} makes for not
 * being a subtype of {@code CallerIdentity}.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><b>No {@code role} claim.</b> Not empty — absent.
 * {@code CallerIdentity.fromToken} refuses a token whose {@code principal_type}
 * is {@code CLIENT} before it looks at anything else, so the absence is not
 * what protects the staff surface. It matters for a different reason: a role
 * claim on a client token is a value some future reader will treat as an
 * authority, and the safest value for a field nobody should read is one that is
 * not there.
 *
 * <p><b>No {@code permissions}.</b> A client's authorisation is entirely
 * {@code client_id} and {@code ob_client_id} — see {@code ClientPrincipal},
 * where the same point is made about fields that are always empty.
 *
 * <h2>What is stamped, and why each is required</h2>
 *
 * <ul>
 *   <li>{@code principal_type: CLIENT} — the type boundary itself. Without it
 *       {@code PrincipalType.of} reads absent as {@code STAFF} (safely, for
 *       tokens minted before A-125) and this token would be a staff one.</li>
 *   <li>{@code sub} — {@code client_accounts.id}, never a {@code users} id.
 *       The sequences overlap.</li>
 *   <li>{@code client_id} / {@code ob_client_id} — the whole authorisation
 *       story. At least one is non-null; the schema's
 *       {@code ck_client_accounts_has_a_master} guarantees it, and
 *       {@code ClientPrincipal.of} refuses a token holding neither.</li>
 *   <li>{@code jti} — fresh per call, so the same logout blacklist and reuse
 *       detection that key on it work here too.</li>
 * </ul>
 *
 * <p>A null id is omitted rather than written as null: {@code ClientPrincipal}
 * reads an absent claim and an unparseable one identically, and a written null
 * is a third spelling of the same fact.
 */
@Component
class ClientAccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    ClientAccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    Minted issue(ClientAccountRow account) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(account.id()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(PrincipalType.CLAIM, PrincipalType.CLIENT.name());

        if (account.clientId() != null) {
            claims.claim(ClientPrincipal.CLIENT_ID_CLAIM, account.clientId());
        }
        if (account.obClientId() != null) {
            claims.claim(ClientPrincipal.OB_CLIENT_ID_CLAIM, account.obClientId());
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();

        return new Minted(token, (int) properties.accessTokenTtl().toSeconds());
    }

    record Minted(String value, int expiresInSeconds) {
    }
}
