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
 * C-121 · mints the CLIENT-typed access token {@link ClientPrincipal} reads
 * back.
 *
 * <p>{@code AccessTokenIssuer}'s shape, one principal type over — same {@code
 * JwtEncoder}/{@code JwtProperties} beans (both public, both already
 * Spring-provisioned; injecting them is consuming a bean, not editing the
 * class that declares it), same HS256 header, same issuer. What differs is
 * the claim set, and every difference is because {@link ClientPrincipal}
 * reads a different shape from the one {@code CallerIdentity} reads:
 *
 * <ul>
 *   <li>{@code sub} is {@code client_accounts.id} — never a {@code users} id,
 *       and never compared with one. {@link ClientPrincipal} keeps the two
 *       apart by principal type rather than by hoping the sequences never
 *       collide.</li>
 *   <li>{@code principal_type} is always {@code CLIENT} — {@link
 *       PrincipalType#CLAIM}, the same claim name {@code CallerIdentity}
 *       refuses to see on a staff token and vice versa.</li>
 *   <li>{@code client_id}/{@code ob_client_id} are written only when
 *       non-null, mirroring the fail-open convention {@code
 *       AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM} uses — an absent claim
 *       reads as "this login holds no such client", never as "unrestricted".
 *       There is no {@code role}, no {@code permissions[]}: a portal caller
 *       has none, by {@link ClientPrincipal}'s own account.</li>
 *   <li>{@code mustChangePassword} is the same idea as the staff claim of the
 *       same name, emitted only when true, and it is what {@link
 *       PortalPasswordChangeGate} refuses every route but the password-set
 *       one on.</li>
 * </ul>
 */
@Component
class PortalAccessTokenIssuer {

    /** Read by {@link PortalPasswordChangeGate}. Emitted only when true — absence means "not required". */
    static final String MUST_CHANGE_PASSWORD_CLAIM = "mustChangePassword";

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    PortalAccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    PortalAccessToken issue(ClientAccountRow account) {
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
        if (account.mustChangePassword()) {
            claims.claim(MUST_CHANGE_PASSWORD_CLAIM, true);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();

        return new PortalAccessToken(token, (int) properties.accessTokenTtl().toSeconds());
    }
}
