package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.storage.ObjectStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A-074 · the Content-Security-Policy, assembled from what this deployment
 * actually serves rather than from a copied template.
 *
 * <p>{@code SecurityConfig} has carried the sentence "Full CSP tokens are
 * A-074's, with CSP and security headers" since A-032. This is that half. The
 * policy is computed once at startup and handed to Spring Security's header
 * writer as a single string.
 *
 * <h2>One policy, not one per response kind</h2>
 *
 * <p>An API JSON response and the SPA shell get the same header. Two policies
 * would mean two things to keep true, and the second one — the one on responses
 * nobody renders — is the one that rots. Every directive below is either
 * meaningful for the document or harmless on JSON.
 *
 * <h2>The three directives that could not be {@code 'self'}, and why</h2>
 *
 * <p><b>{@code script-src} carries a hash, because {@code index.html} has an
 * inline script.</b> D-15 applies the stored theme before the first paint, and
 * its comment explains at length why it is inline and synchronous — an external
 * script is a round trip before paint, which is the flash it exists to remove.
 * A strict policy would block it and restore the flash permanently.
 *
 * <p><b>The hash is computed from the file that is actually served, at startup
 * — never written down.</b> {@code SpaResourceConfig} serves
 * {@code classpath:/static/index.html}; this reads the same resource and hashes
 * whatever inline scripts it finds. A hardcoded {@code 'sha256-…'} would be a
 * constant that has to be re-derived by hand every time that script changes, and
 * the failure mode is a blank application in production and a green test suite,
 * because nothing in a unit test parses CSP. Deriving it removes the possibility
 * of drift rather than testing for it. Vite is also free to minify the inline
 * script during the build: hashing the built artefact is correct by
 * construction, hashing the source would not be.
 *
 * <p><b>{@code style-src} keeps {@code 'unsafe-inline'}, and this is a
 * deliberate limit on how strict the policy gets.</b> Radix's dialog, popover
 * and select all depend on {@code react-remove-scroll}, which injects a
 * {@code <style>} element at runtime to lock body scroll. There is no nonce to
 * give it — Radix exposes no such API — so {@code style-src 'self'} would break
 * every dialog in the product. The exposure is real but bounded: inline
 * <i>styles</i> cannot execute script, and the attacks they enable need an
 * injection point that {@code script-src} already closes. Recorded here rather
 * than quietly omitted, because "we have a strict CSP" and "our CSP allows
 * unsafe-inline styles" are both true and only one of them is usually said.
 * React's own {@code style={{…}}} props need nothing: React writes them through
 * the CSSOM, which CSP does not govern.
 *
 * <p><b>{@code img-src} and {@code connect-src} carry the object-store
 * origin.</b> §4B.4 hands attachments out as short-lived presigned URLs, so the
 * gallery loads images from MinIO or S3 directly — a different origin from the
 * application. The origin is read from {@link ObjectStorageProperties} rather
 * than hardcoded, so the policy follows the deployment instead of being right
 * on a laptop and wrong in production. {@code blob:} is there for the same
 * screen's local previews, which {@code useTicketAttachments} creates with
 * {@code createObjectURL} before the upload completes.
 *
 * <p><b>The realtime socket needs nothing beyond {@code 'self'}, and that is
 * worth stating because it looks like an omission.</b> D-015 connects SockJS to
 * {@code /ws} on our own origin, and CSP Level 3 has {@code 'self'} match a
 * {@code ws:}/{@code wss:} URL whose host and port are the document's — so a
 * bare {@code ws:} in {@code connect-src} would widen the policy to <i>every</i>
 * host rather than fix anything. SockJS's XHR fallback is same-origin too.
 *
 * <h2>What the policy refuses outright</h2>
 *
 * <p>{@code object-src 'none'} (no Flash/applet embedding), {@code base-uri
 * 'self'} (a stolen {@code <base>} tag re-points every relative URL in the
 * document), {@code form-action 'self'} and {@code frame-ancestors 'none'} —
 * the last of which is the modern {@code X-Frame-Options} and is why clickjacking
 * is closed twice.
 *
 * <p><b>{@code upgrade-insecure-requests} is not here.</b> It would break local
 * development against {@code http://localhost:9000} MinIO, and TLS termination
 * is A-075's. HSTS covers the same ground for a deployment that has it.
 */
@Component
public class ContentSecurityPolicy {

    private static final Logger log = LoggerFactory.getLogger(ContentSecurityPolicy.class);

    /** The document the SPA is served from, and the only file with inline script. */
    static final String INDEX_HTML = "/static/index.html";

    /**
     * Inline {@code <script>} blocks — those with no {@code src}. The negative
     * lookahead is what excludes {@code <script type="module" src="…">}, which
     * needs no hash because it is fetched from {@code 'self'}.
     */
    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<script(?![^>]*\\ssrc\\s*=)[^>]*>(.*?)</script>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final String policy;

    public ContentSecurityPolicy(ObjectStorageProperties storage) {
        this.policy = policyFor(storage.endpoint(), new ClassPathResource(INDEX_HTML));
    }

    /** The assembled header value. */
    public String policyDirectives() {
        return policy;
    }

    /**
     * The same assembly against a substitute endpoint and document.
     *
     * <p>A static factory rather than a second constructor: two constructors on
     * a {@code @Component} leave Spring with no way to choose between them, and
     * it says so only at context refresh — which is 19 integration tests
     * reporting an identical unrelated error, as this class did on first run.
     */
    static String policyFor(String endpoint, Resource index) {
        return build(originOf(endpoint), hashesOf(index));
    }

    private static String build(String objectStoreOrigin, List<String> scriptHashes) {
        String store = objectStoreOrigin == null ? "" : " " + objectStoreOrigin;

        List<String> directives = new ArrayList<>();
        directives.add("default-src 'self'");
        directives.add("script-src 'self'" + (scriptHashes.isEmpty() ? "" : " " + String.join(" ", scriptHashes)));
        // See the class javadoc — react-remove-scroll, and no nonce to give it.
        directives.add("style-src 'self' 'unsafe-inline'");
        directives.add("img-src 'self' data: blob:" + store);
        directives.add("font-src 'self' data:");
        directives.add("connect-src 'self'" + store);
        directives.add("object-src 'none'");
        directives.add("base-uri 'self'");
        directives.add("form-action 'self'");
        directives.add("frame-ancestors 'none'");
        return String.join("; ", directives);
    }

    /**
     * Scheme, host and port of the object store — never its path or credentials.
     *
     * <p>A CSP source expression is an origin; handing it a full endpoint URL
     * with a path silently narrows nothing and is simply wrong. Returns null for
     * an unset or unparseable endpoint, which drops the source rather than
     * emitting a malformed directive — a browser discards the whole policy on a
     * parse error, so a bad value here would remove every protection below it.
     */
    static String originOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            log.warn("csp: object-store endpoint '{}' is not a URI; attachments will be blocked by CSP", endpoint);
            return null;
        }
    }

    /**
     * {@code 'sha256-…'} for every inline script in the served document.
     *
     * <p>A missing {@code index.html} is normal and not an error: the backend
     * runs without a built frontend all through development and in every
     * integration test. There is then no document to protect and no inline
     * script to allow, so the policy is simply stricter.
     */
    private static List<String> hashesOf(Resource index) {
        if (!index.exists()) {
            log.debug("csp: no {} on the classpath — script-src stays 'self' alone", INDEX_HTML);
            return List.of();
        }
        String html;
        try (InputStream in = index.getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Refusing to start would take the whole application down over a
            // header. Logged loudly instead: the SPA's theme script breaks,
            // which is visible, rather than the API becoming unavailable.
            log.error("csp: could not read {} — inline scripts will be blocked", INDEX_HTML, e);
            return List.of();
        }

        Set<String> hashes = new LinkedHashSet<>();
        Matcher matcher = INLINE_SCRIPT.matcher(html);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (!body.isBlank()) {
                hashes.add(sha256(body));
            }
        }
        log.info("csp: allowing {} inline script hash(es) from {}", hashes.size(), INDEX_HTML);
        return List.copyOf(hashes);
    }

    /**
     * The hash is over the script's <b>exact</b> bytes, whitespace included —
     * that is what the browser hashes, so trimming here would produce a value
     * that never matches anything.
     */
    private static String sha256(String scriptBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scriptBody.getBytes(StandardCharsets.UTF_8));
            return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
