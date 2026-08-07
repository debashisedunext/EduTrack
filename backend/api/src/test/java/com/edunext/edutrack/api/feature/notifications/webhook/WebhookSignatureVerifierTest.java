package com.edunext.edutrack.api.feature.notifications.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-034 · the only thing between the internet and an endpoint that can stop
 * someone's mail.
 *
 * <p>The bounce webhook is necessarily unauthenticated — the provider has no
 * EduTrack session — so if the signature check is wrong, anyone who can reach
 * the URL can suppress an arbitrary address and silence every escalation mail
 * to that person, with no error raised anywhere.
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "a-shared-secret-from-the-provider";
    private static final byte[] BODY =
            "{\"email\":\"ravi@example.com\",\"type\":\"BOUNCE\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void acceptsASignatureComputedWithTheSharedSecret() {
        assertThat(verifier.isValid(BODY, sign(BODY, SECRET))).isTrue();
    }

    @Test
    void acceptsTheProviderStylePrefixedForm() {
        assertThat(verifier.isValid(BODY, "sha256=" + sign(BODY, SECRET))).isTrue();
    }

    @Test
    void rejectsASignatureMadeWithTheWrongSecret() {
        assertThat(verifier.isValid(BODY, sign(BODY, "not-the-secret"))).isFalse();
    }

    /** The point of signing: the body cannot be altered in flight. */
    @Test
    void rejectsATamperedBody() {
        String signature = sign(BODY, SECRET);
        byte[] tampered =
                "{\"email\":\"ceo@example.com\",\"type\":\"BOUNCE\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(tampered, signature))
                .as("swapping the address must not survive verification")
                .isFalse();
    }

    /**
     * An unconfigured secret must reject everything. Treating "no secret" as
     * "skip verification" is how an open endpoint reaches production the first
     * time someone forgets the environment variable.
     */
    @Test
    void failsClosedWhenNoSecretIsConfigured() {
        WebhookSignatureVerifier unconfigured = new WebhookSignatureVerifier("");

        assertThat(unconfigured.isValid(BODY, sign(BODY, SECRET))).isFalse();
        assertThat(unconfigured.isValid(BODY, "")).isFalse();
        assertThat(new WebhookSignatureVerifier(null).isValid(BODY, "anything")).isFalse();
    }

    @Test
    void rejectsAMissingOrMalformedSignature() {
        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, "")).isFalse();
        assertThat(verifier.isValid(BODY, "not-hex-at-all")).isFalse();
        assertThat(verifier.isValid(BODY, "abcd")).as("right charset, wrong length").isFalse();
    }

    private static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
