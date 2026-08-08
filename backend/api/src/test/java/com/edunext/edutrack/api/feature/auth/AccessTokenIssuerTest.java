package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.api.security.jwt.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-022 · claim mapping and signature correctness, independent of HTTP or the
 * database. {@code AuthLoginIT} proves the claims match a scope actually
 * resolved from a real login; this proves the token itself is what an
 * offline HS256 verifier would accept — encoder and decoder are built from
 * the secret independently here, the same way A-032's future verifier and
 * this issuer will only ever agree because they hold the same one.
 */
class AccessTokenIssuerTest {

    private static final String SECRET =
            "unit-test-only-jwt-signing-secret-at-least-32-bytes-long";

    private static final String ISSUER = "https://edutrack-test";

    private static final AuthenticatedUser USER = new AuthenticatedUser(
            42L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
            "DEVELOPER", "Asia/Kolkata", false,
            List.of("ticket.read", "ticket.assign"), List.of(11L, 12L), List.of(99L));

    private static JwtEncoder encoderFor(String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    private static JwtDecoder decoderFor(String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Test
    @DisplayName("the minted token carries exactly the §10.1 claim set")
    void mintsTheDocumentedClaimSet() {
        JwtProperties properties = new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15));
        AccessTokenIssuer issuer = new AccessTokenIssuer(encoderFor(SECRET), properties);

        AccessToken token = issuer.issue(USER);
        assertThat(token.expiresInSeconds()).isEqualTo(900);

        Jwt jwt = decoderFor(SECRET).decode(token.value());
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString("role")).isEqualTo("DEVELOPER");
        assertThat(jwt.getClaimAsStringList("permissions")).containsExactly("ticket.read", "ticket.assign");
        assertThat(asLongs(jwt.getClaim("projects"))).containsExactly(11L, 12L);
        assertThat(asLongs(jwt.getClaim("reportees"))).containsExactly(99L);
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(900);
    }

    @Test
    @DisplayName("two tokens minted for the same user carry two different jtis")
    void eachTokenGetsAFreshJti() {
        JwtProperties properties = new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15));
        AccessTokenIssuer issuer = new AccessTokenIssuer(encoderFor(SECRET), properties);

        Jwt first = decoderFor(SECRET).decode(issuer.issue(USER).value());
        Jwt second = decoderFor(SECRET).decode(issuer.issue(USER).value());

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    @DisplayName("a token signed with a different secret fails verification")
    void signatureIsRejectedByTheWrongSecret() {
        JwtProperties properties = new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15));
        AccessTokenIssuer issuer = new AccessTokenIssuer(encoderFor(SECRET), properties);
        String differentSecret = "a-completely-different-signing-secret-also-32+-bytes-long";

        String token = issuer.issue(USER).value();

        assertThatThrownBy(() -> decoderFor(differentSecret).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> asLongs(Object claim) {
        return ((List<Number>) claim).stream().map(Number::longValue).toList();
    }
}
