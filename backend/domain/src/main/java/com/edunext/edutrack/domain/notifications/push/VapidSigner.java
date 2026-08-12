package com.edunext.edutrack.domain.notifications.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D-045 · the VAPID authorization header (RFC 8292).
 *
 * <p>A JWT signed with the deployment's private key, proving to the push
 * service that this sender is the one the browser subscribed to. It is not
 * about the user at all — the subscription already names the browser; this
 * names <em>us</em>, so a leaked endpoint cannot be pushed to by anybody else.
 *
 * <p>Signed with plain JCE rather than Nimbus so this class can sit in
 * {@code domain}: the worker raises most of the notifications worth pushing —
 * every SLA alert — and {@code spring-security-oauth2-jose} is an {@code api}
 * dependency. The only fiddly part is that JCA emits a DER-encoded ECDSA
 * signature while JOSE wants the raw {@code R || S} pair, which
 * {@link #toJoseSignature} converts.
 */
public class VapidSigner {

    /**
     * RFC 8292 §2: {@code exp} must be no more than 24 hours ahead. Twelve is
     * used rather than the maximum, because a token minted at the boundary and
     * a push service whose clock runs slow is a 401 nobody can reproduce.
     */
    private static final Duration LIFETIME = Duration.ofHours(12);

    private static final String HEADER =
            base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    public VapidSigner(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param endpoint   the subscription's push endpoint; only its origin is used
     * @param subject    the {@code sub} claim — a {@code mailto:} or {@code https:}
     *                   the push service can use to reach whoever runs this sender
     * @param privateKey base64url of the P-256 private scalar
     * @param publicKey  base64url of the uncompressed public point
     * @return the complete {@code Authorization} header value
     */
    public String authorizationHeader(String endpoint, String subject,
                                      String privateKey, String publicKey) {
        String jwt = sign(audienceOf(endpoint), subject, privateKey);
        // RFC 8292 §3.1 — one header carrying both the token and the key it
        // was signed with, so the push service can verify without a lookup.
        return "vapid t=" + jwt + ", k=" + publicKey;
    }

    /**
     * The {@code aud} claim is the push service's <strong>origin</strong>, not
     * the endpoint.
     *
     * <p>Sending the full endpoint would put the subscription's unguessable
     * path inside a token, and the token travels in a header that proxies log.
     * It would also be rejected: RFC 8292 §2 says the audience is the origin.
     */
    static String audienceOf(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("push endpoint is not an absolute URL: " + endpoint);
        }
        String origin = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    }

    private String sign(String audience, String subject, String privateKey) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", clock.instant().plus(LIFETIME).getEpochSecond());
        claims.put("sub", subject);

        String payload;
        try {
            payload = base64Url(json.writeValueAsBytes(claims));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("push: could not serialise VAPID claims", e);
        }

        String signingInput = HEADER + "." + payload;
        byte[] der = rawSign(signingInput.getBytes(StandardCharsets.US_ASCII), privateKey);
        return signingInput + "." + base64Url(toJoseSignature(der));
    }

    private static byte[] rawSign(byte[] signingInput, String privateKeyBase64Url) {
        try {
            java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = params.getParameterSpec(ECParameterSpec.class);

            BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(privateKeyBase64Url));
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, p256));

            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            signature.update(signingInput);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("push: could not sign the VAPID token", e);
        }
    }

    /**
     * DER {@code SEQUENCE(INTEGER r, INTEGER s)} → the 64 raw bytes JOSE wants.
     *
     * <p>Both halves are left-padded to 32. DER drops leading zeroes and adds a
     * sign byte when the high bit is set, so a naive copy produces a signature
     * that verifies most of the time and fails roughly one call in 128 — the
     * kind of intermittent that gets blamed on the network for weeks.
     */
    static byte[] toJoseSignature(byte[] der) {
        ASN1Sequence sequence = ASN1Sequence.getInstance(der);
        BigInteger r = ASN1Integer.getInstance(sequence.getObjectAt(0)).getValue();
        BigInteger s = ASN1Integer.getInstance(sequence.getObjectAt(1)).getValue();

        byte[] jose = new byte[64];
        copyLeftPadded(r, jose, 0);
        copyLeftPadded(s, jose, 32);
        return jose;
    }

    private static void copyLeftPadded(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, target, offset + 32 - length, length);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
