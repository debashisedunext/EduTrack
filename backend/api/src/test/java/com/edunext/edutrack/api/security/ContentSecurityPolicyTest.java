package com.edunext.edutrack.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-074 · how the policy is assembled.
 *
 * <p>{@link SecurityHardeningIT} asserts the header arrives on real responses;
 * this asserts the parts of the assembly that only show up with an input you
 * cannot produce over HTTP — a document with three inline scripts, an object
 * store on a non-default port, an endpoint somebody mistyped.
 */
class ContentSecurityPolicyTest {

    private static final String ENDPOINT = "http://localhost:9000";

    private static Resource document(String html) {
        return new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8));
    }

    /** A missing file, which is what a backend-only build actually has. */
    private static Resource noDocument() {
        return new ClassPathResource("/static/this-file-does-not-exist.html");
    }

    private static String expectedHash(String scriptBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scriptBody.getBytes(StandardCharsets.UTF_8));
            return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("inline script hashes")
    class Hashes {

        @Test
        @DisplayName("an inline script is allowed by its exact hash")
        void inlineScriptIsHashed() {
            String body = "\n  document.documentElement.classList.add('dark')\n";
            String policy = ContentSecurityPolicy.policyFor(
                    ENDPOINT, document("<html><head><script>" + body + "</script></head></html>"));

            assertThat(policy).contains(expectedHash(body));
        }

        /**
         * The bytes matter. A browser hashes the script's content exactly as it
         * appears, so trimming or normalising whitespace anywhere in the
         * pipeline produces a hash that matches nothing — and the symptom is a
         * blank application, not an error.
         */
        @Test
        @DisplayName("the hash is over the exact bytes, whitespace included")
        void whitespaceIsNotNormalised() {
            String padded = "   var a = 1;   ";
            String policy = ContentSecurityPolicy.policyFor(
                    ENDPOINT, document("<script>" + padded + "</script>"));

            assertThat(policy).contains(expectedHash(padded));
            assertThat(policy).doesNotContain(expectedHash(padded.trim()));
        }

        /**
         * {@code <script type="module" src="/assets/index-abc123.js">} is what
         * Vite emits, and it needs no hash — it is fetched from {@code 'self'}.
         * Hashing it would be harmless but wrong, and would change on every
         * build, which is how somebody concludes the mechanism is broken.
         */
        @Test
        @DisplayName("a script with src is not hashed — it is covered by 'self'")
        void externalScriptsAreNotHashed() {
            String policy = ContentSecurityPolicy.policyFor(ENDPOINT, document(
                    "<script type=\"module\" src=\"/assets/index-abc123.js\"></script>"));

            assertThat(directive(policy, "script-src"))
                    .as("only 'self' — no hash for a file fetched from our own origin")
                    .isEqualTo("script-src 'self'");
        }

        @Test
        @DisplayName("several inline scripts each get a hash")
        void everyInlineScriptIsHashed() {
            String policy = ContentSecurityPolicy.policyFor(ENDPOINT, document(
                    "<script>one()</script><script>two()</script>"));

            assertThat(policy).contains(expectedHash("one()"), expectedHash("two()"));
        }

        @Test
        @DisplayName("an empty script tag contributes nothing")
        void emptyScriptsAreSkipped() {
            String policy = ContentSecurityPolicy.policyFor(ENDPOINT, document("<script>  </script>"));
            assertThat(directive(policy, "script-src")).isEqualTo("script-src 'self'");
        }

        /**
         * The backend runs without a built frontend all through development and
         * in every integration test in this module. That must produce a valid,
         * stricter policy rather than an exception during context refresh.
         */
        /**
         * The shape Vite actually emits, copied from a real {@code npm run
         * build} of this repo — the theme script preserved verbatim, the entry
         * point rewritten to a hashed asset with {@code crossorigin} sitting
         * between the tag name and {@code src}.
         *
         * <p>That attribute order is the case worth pinning. The exclusion is a
         * negative lookahead for {@code src} anywhere in the tag, and a naive
         * version that only looked at the first attribute would hash the entry
         * point too — producing a policy that changes on every build and a
         * `script-src` nobody could reason about.
         */
        @Test
        @DisplayName("against Vite's real output, exactly one script is hashed")
        void matchesTheShapeViteActuallyEmits() {
            String themeScript = """

                          try {
                            if (localStorage.getItem('edutrack-theme') === 'dark') {
                              document.documentElement.classList.add('dark')
                            }
                          } catch (e) {
                            /* storage denied */
                          }
                        """;
            String built = """
                    <!doctype html>
                    <html lang="en">
                      <head>
                        <title>EduTrack</title>
                        <!-- D-15 · apply the stored theme before the first paint. -->
                        <script>%s</script>
                        <script type="module" crossorigin src="/assets/index-C0gcYpEE.js"></script>
                        <link rel="stylesheet" crossorigin href="/assets/index-Gp_g2XC9.css">
                      </head>
                      <body><div id="root"></div></body>
                    </html>
                    """.formatted(themeScript);

            String scriptSrc = directive(ContentSecurityPolicy.policyFor(ENDPOINT, document(built)), "script-src");

            assertThat(scriptSrc).isEqualTo("script-src 'self' " + expectedHash(themeScript));
            assertThat(scriptSrc.split("'sha256-", -1))
                    .as("the module script has src and must not be hashed")
                    .hasSize(2);
        }

        @Test
        @DisplayName("a missing index.html yields a stricter policy, not a failure")
        void missingDocumentIsNotAnError() {
            String policy = ContentSecurityPolicy.policyFor(ENDPOINT, noDocument());
            assertThat(directive(policy, "script-src")).isEqualTo("script-src 'self'");
            assertThat(policy).contains("default-src 'self'");
        }
    }

    @Nested
    @DisplayName("the object-store origin")
    class ObjectStore {

        /**
         * Attachments are handed out as presigned URLs on the store's own
         * origin (§4B.4), so a policy without it blocks every image in the
         * gallery — and CSP failures appear in the browser console, not in any
         * test.
         */
        @Test
        @DisplayName("is allowed for images and for fetch")
        void originIsAllowedWhereAttachmentsAreLoaded() {
            String policy = ContentSecurityPolicy.policyFor("http://minio.internal:9000", document("<html></html>"));

            assertThat(directive(policy, "img-src")).contains("http://minio.internal:9000");
            assertThat(directive(policy, "connect-src")).contains("http://minio.internal:9000");
        }

        @Test
        @DisplayName("is reduced to an origin — a CSP source is not a URL")
        void pathAndQueryAreStripped() {
            assertThat(ContentSecurityPolicy.originOf("https://s3.eu-west-1.amazonaws.com/edutrack-bucket?x=1"))
                    .isEqualTo("https://s3.eu-west-1.amazonaws.com");
        }

        @Test
        @DisplayName("keeps a non-default port, which MinIO always has")
        void portIsKept() {
            assertThat(ContentSecurityPolicy.originOf("http://localhost:9000")).isEqualTo("http://localhost:9000");
        }

        @Test
        @DisplayName("drops the default port when there is none")
        void defaultPortIsOmitted() {
            assertThat(ContentSecurityPolicy.originOf("https://cdn.example.com")).isEqualTo("https://cdn.example.com");
        }

        /**
         * A browser discards an entire policy it cannot parse. Emitting a
         * malformed source would therefore not merely fail to allow the store —
         * it would silently remove every protection in the header, which is the
         * worst possible outcome and the one nobody would notice.
         */
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "not a url", "/just/a/path", "localhost:9000"})
        @DisplayName("an unusable endpoint drops the source rather than emitting something malformed")
        void unusableEndpointsAreDropped(String endpoint) {
            assertThat(ContentSecurityPolicy.originOf(endpoint)).isNull();

            String policy = ContentSecurityPolicy.policyFor(endpoint, document("<html></html>"));
            assertThat(directive(policy, "img-src"))
                    .as("still a well-formed directive — a policy a browser rejects protects nothing")
                    .isEqualTo("img-src 'self' data: blob:");
            assertThat(directive(policy, "connect-src")).isEqualTo("connect-src 'self'");
        }
    }

    @Nested
    @DisplayName("the fixed directives")
    class Fixed {

        @Test
        @DisplayName("blob: is allowed for the gallery's local previews")
        void blobIsAllowedForPreviews() {
            assertThat(directive(policy(), "img-src")).contains("blob:");
        }

        /**
         * Not an oversight — see {@link ContentSecurityPolicy}'s javadoc.
         * react-remove-scroll, which every Radix dialog depends on, injects a
         * {@code <style>} element at runtime and there is no nonce to give it.
         * Pinned so that the trade-off is visible in the test names rather than
         * discovered by whoever tightens it and breaks every dialog.
         */
        @Test
        @DisplayName("style-src allows 'unsafe-inline', because Radix injects a <style> element")
        void styleSrcAllowsInline() {
            assertThat(directive(policy(), "style-src")).isEqualTo("style-src 'self' 'unsafe-inline'");
        }

        @Test
        @DisplayName("script-src never allows 'unsafe-inline' or 'unsafe-eval'")
        void scriptSrcIsStrict() {
            assertThat(directive(policy(), "script-src"))
                    .doesNotContain("'unsafe-inline'")
                    .doesNotContain("'unsafe-eval'");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "default-src 'self'",
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'",
        })
        @DisplayName("the refusals are present")
        void refusalsArePresent(String expected) {
            assertThat(policy()).contains(expected);
        }

        /**
         * It would break local development against http://localhost:9000 MinIO,
         * and TLS is A-075's. Asserted rather than merely omitted so that adding
         * it is a decision somebody makes on purpose.
         */
        @Test
        @DisplayName("upgrade-insecure-requests is not emitted")
        void noUpgradeInsecureRequests() {
            assertThat(policy()).doesNotContain("upgrade-insecure-requests");
        }

        private String policy() {
            return ContentSecurityPolicy.policyFor(ENDPOINT, document("<html></html>"));
        }
    }

    private static String directive(String policy, String name) {
        for (String part : policy.split(";")) {
            String trimmed = part.trim();
            if (trimmed.equals(name) || trimmed.startsWith(name + " ")) {
                return trimmed;
            }
        }
        throw new AssertionError("no " + name + " directive in: " + policy);
    }
}
