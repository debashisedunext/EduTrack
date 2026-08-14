package com.edunext.edutrack.domain.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-045 · the VAPID token.
 *
 * <p>The signature is verified with the <em>public</em> key rather than
 * compared to a fixed string, because ECDSA is randomised — the same claims
 * signed twice produce different bytes, and a golden-file test here would
 * either be flaky or would be pinning a nonce that must not be pinned.
 */
class VapidSignerTest {

    /** RFC 8291's example pair, reused — any valid P-256 pair does here. */
    private static final String PRIVATE_KEY = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String PUBLIC_KEY =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String ENDPOINT =
            "https://fcm.googleapis.com/fcm/send/abc123?token=xyz";

    private final VapidSigner signer = new VapidSigner(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("the header carries the token and the key that signed it")
    void theHeaderIsShapedAsRfc8292Requires() {
        String header = signer.authorizationHeader(
                ENDPOINT, "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY);

        assertThat(header).startsWith("vapid t=").contains(", k=" + PUBLIC_KEY);
        assertThat(header.split("t=")[1].split(",")[0].split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("the signature verifies under the public key")
    void theTokenIsGenuinelySigned() throws Exception {
        String jwt = tokenFrom(signer.authorizationHeader(
                ENDPOINT, "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY));

        String[] parts = jwt.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

        assertThat(verifier.verify(toDer(Base64.getUrlDecoder().decode(parts[2]))))
                .as("a push service checking this token would accept it")
                .isTrue();
    }

    @Test
    @DisplayName("aud is the push service's origin, never the endpoint path")
    void theAudienceIsTheOriginOnly() throws Exception {
        JsonNode claims = claimsFrom(signer.authorizationHeader(
                ENDPOINT, "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY));

        // The endpoint path is the unguessable half of the subscription. Putting
        // it in a token that travels in a header proxies log would leak it, and
        // RFC 8292 §2 asks for the origin anyway.
        assertThat(claims.get("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("aud").asText()).doesNotContain("abc123");
    }

    @Test
    @DisplayName("a non-default port stays part of the audience")
    void aPortIsPartOfTheOrigin() throws Exception {
        JsonNode claims = claimsFrom(signer.authorizationHeader(
                "https://push.example.test:8443/send/abc", "mailto:ops@edunext.test",
                PRIVATE_KEY, PUBLIC_KEY));

        assertThat(claims.get("aud").asText()).isEqualTo("https://push.example.test:8443");
    }

    @Test
    @DisplayName("exp is inside RFC 8292's 24-hour ceiling")
    void theTokenExpiresWellInsideTheLimit() throws Exception {
        JsonNode claims = claimsFrom(signer.authorizationHeader(
                ENDPOINT, "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY));

        long exp = claims.get("exp").asLong();
        assertThat(exp).isGreaterThan(NOW.getEpochSecond());
        // Twelve hours, not the maximum: a token minted at the boundary and a
        // push service with a slow clock is a 401 nobody can reproduce.
        assertThat(exp - NOW.getEpochSecond()).isLessThanOrEqualTo(12 * 3600);
    }

    @Test
    @DisplayName("the subject travels as sub, so a push service can reach us")
    void theSubjectIsCarried() throws Exception {
        JsonNode claims = claimsFrom(signer.authorizationHeader(
                ENDPOINT, "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY));

        assertThat(claims.get("sub").asText()).isEqualTo("mailto:ops@edunext.test");
    }

    @Test
    @DisplayName("two tokens for the same claims differ — ECDSA is randomised")
    void signaturesAreNotDeterministic() {
        String first = signer.authorizationHeader(ENDPOINT, "mailto:a@b.test", PRIVATE_KEY, PUBLIC_KEY);
        String second = signer.authorizationHeader(ENDPOINT, "mailto:a@b.test", PRIVATE_KEY, PUBLIC_KEY);

        // Worth pinning: it is why this suite verifies signatures instead of
        // comparing them, and a change to deterministic ECDSA would be a
        // deliberate decision rather than an accident.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a relative endpoint is refused rather than signed for nobody")
    void anEndpointWithoutAnOriginIsRejected() {
        assertThatThrownBy(() -> signer.authorizationHeader(
                "/fcm/send/abc", "mailto:ops@edunext.test", PRIVATE_KEY, PUBLIC_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("R and S are left-padded to 32 bytes each")
    void theJoseSignatureIsFixedWidth() {
        // DER drops leading zeroes; a naive copy yields a signature that
        // verifies most of the time and fails about one call in 128 — the kind
        // of intermittent that gets blamed on the network for weeks.
        byte[] der = derOf(BigInteger.ONE, BigInteger.ONE);

        byte[] jose = VapidSigner.toJoseSignature(der);

        assertThat(jose).hasSize(64);
        assertThat(jose[31]).isEqualTo((byte) 1);
        assertThat(jose[63]).isEqualTo((byte) 1);
        assertThat(jose[0]).isZero();
        assertThat(jose[32]).isZero();
    }

    // ------------------------------------------------------------- helpers

    private static String tokenFrom(String header) {
        return header.substring("vapid t=".length(), header.indexOf(", k="));
    }

    private JsonNode claimsFrom(String header) throws Exception {
        String payload = tokenFrom(header).split("\\.")[1];
        return json.readTree(Base64.getUrlDecoder().decode(payload));
    }

    private static PublicKey publicKey() throws Exception {
        byte[] point = Base64.getUrlDecoder().decode(PUBLIC_KEY);
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(point, 33, 65));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec p256 = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256));
    }

    /** JOSE's raw pair back to the DER a JCA verifier expects. */
    private static byte[] toDer(byte[] jose) {
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(jose, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(jose, 32, 64));
        return derOf(r, s);
    }

    private static byte[] derOf(BigInteger r, BigInteger s) {
        try {
            org.bouncycastle.asn1.ASN1EncodableVector v = new org.bouncycastle.asn1.ASN1EncodableVector();
            v.add(new org.bouncycastle.asn1.ASN1Integer(r));
            v.add(new org.bouncycastle.asn1.ASN1Integer(s));
            return new org.bouncycastle.asn1.DERSequence(v).getEncoded();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
