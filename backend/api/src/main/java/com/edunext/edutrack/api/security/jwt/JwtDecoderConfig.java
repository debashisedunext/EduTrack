package com.edunext.edutrack.api.security.jwt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * A-025 · the verifying half of {@link JwtEncoderConfig} — the first thing in
 * this system that reads an access token rather than writing one.
 *
 * <p>A-022 minted tokens nothing verified, on the understanding that A-032's
 * filter chain would be the first reader. {@code POST /auth/logout} arrives
 * first: it is the only endpoint so far that the contract does <b>not</b> mark
 * {@code security: []}, so it has to establish who is calling before it can
 * revoke their session. Rather than let that controller hand-parse a JWT, the
 * decoder lands here as proper wiring — A-032 will consume this same bean
 * instead of introducing a second one keyed off the same secret.
 *
 * <p><b>Same secret, same algorithm, deliberately restricted.</b>
 * {@code macAlgorithm(HS256)} is not decoration: without it Nimbus accepts any
 * MAC algorithm the token's own header names, which lets a caller choose the
 * weakest one this key will satisfy. The header is attacker-controlled input,
 * so the accepted algorithm is pinned server-side to the one
 * {@code JwtEncoderConfig} actually signs with.
 *
 * <p><b>Issuer and expiry are validated here, not by callers.</b>
 * {@link JwtValidators#createDefaultWithIssuer} enforces {@code exp}, {@code
 * nbf} and {@code iss}. Leaving expiry to a caller's own {@code Instant}
 * comparison is how one endpoint eventually forgets — and an expired access
 * token that still logs you out is a small bug, while the same omission in
 * A-032 is a total authentication bypass. It belongs to the decoder, once.
 *
 * <p><b>A-032 · revocation is validated here for the same reason.</b> The
 * injected {@link OAuth2TokenValidator} is the blacklist check, composed
 * alongside the defaults rather than bolted onto the filter chain. Putting it on
 * the chain alone would leave the services that call {@code AccessTokenVerifier}
 * directly honouring a token the chain would have refused — two verification
 * paths, differing on the one question that decides whether logout means
 * anything. Injected by its Spring interface so this package needs no visibility
 * into {@code feature/auth}, where the blacklist lives.
 */
@Configuration
public class JwtDecoderConfig {

    /** RFC 7518 §3.2, mirroring {@link JwtEncoderConfig}. */
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, OAuth2TokenValidator<Jwt> revocationValidator) {
        byte[] secret = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);

        // The encoder makes the same check, and both must: a context that can
        // sign but not verify is a fleet that issues tokens nothing can honour.
        // Failing at startup puts it in front of whoever set the property.
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "edutrack.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256 (got " + secret.length + "). Set JWT_SIGNING_SECRET.");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();

        // Composed, not replaced. Calling setJwtValidator with the revocation
        // check alone would silently drop exp, nbf and iss — an expired token
        // would then pass, which is the kind of bypass that looks like a
        // one-line simplification in review.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                revocationValidator));
        return decoder;
    }
}
