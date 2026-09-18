package com.edunext.edutrack.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code edutrack.storage} — MinIO in development, S3 in production (PLAN.md
 * §2.1).
 *
 * <h2>Why this is here rather than in a feature package</h2>
 *
 * <p>It was {@code AttachmentStorageProperties}, in
 * {@code feature/tickets/attachments}, and that file's own javadoc said what
 * should happen next:
 *
 * <blockquote>PLAN.md lists three eventual users of the bucket — attachments,
 * avatars and import error reports — so <b>when the second one arrives this
 * record should move out of this package</b> rather than being imported across a
 * feature boundary. It lives here today because a configuration class in a
 * shared package with exactly one caller is speculative generality, and moving
 * it later is a rename.</blockquote>
 *
 * <p>B-036 is the second one. The move is the rename that comment asked for, and
 * it is what keeps the bucket described in one place: two records binding
 * {@code edutrack.storage} would be two declarations of the same four keys, and
 * two {@link software.amazon.awssdk.services.s3.S3Client} beans would make every
 * injection of one ambiguous.
 *
 * <h2>Blank is the production switch, for two keys</h2>
 *
 * <p>A blank {@link #endpoint} means real AWS S3, and blank credentials mean the
 * SDK's default provider chain — an instance role. Both were <b>described</b>
 * here before they <b>worked</b>: this javadoc said "empty in production" while
 * {@code ObjectStorageConfig} overrode the endpoint unconditionally, and
 * {@code URI.create("")} has no scheme, which the SDK rejects at startup. The
 * switch is now honoured rather than only documented.
 *
 * <p>Blank rather than a {@code mode} enum because there is nothing a mode could
 * express that these do not, and a third way to say "which store" is a third
 * thing to get out of step with the other two.
 *
 * @param endpoint MinIO's address locally; <b>blank in production</b>, where the
 *                 AWS SDK resolves the real S3 endpoint from the region
 * @param region   ignored entirely by MinIO while the SDK insists on one being
 *                 set — a signature is computed over it either way. <b>Load
 *                 bearing against real S3:</b> SigV4 signs the region, so a wrong
 *                 value is a signature mismatch on every presigned URL
 * @param pathStyle {@code http://host:9000/bucket/key} rather than
 *                  {@code http://bucket.host:9000/key}. Required for MinIO, where
 *                  the bucket is not a DNS name. <b>Set false against real S3:</b>
 *                  path-style is deprecated for buckets created after Sept 2020
 * @param accessKey blank in production, where {@link #hasStaticCredentials()}
 *                  then routes to the instance role
 * @param secretKey see {@code accessKey}
 */
@ConfigurationProperties("edutrack.storage")
public record ObjectStorageProperties(

        @DefaultValue("http://localhost:9000") String endpoint,
        @DefaultValue("edutrack") String bucket,
        @DefaultValue("minioadmin") String accessKey,
        @DefaultValue("minioadmin") String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("true") boolean pathStyle) {

    /**
     * Whether the SDK should resolve AWS's own endpoint from the region rather
     * than being pointed at a host we name.
     */
    public boolean awsManaged() {
        return endpoint == null || endpoint.isBlank();
    }

    /**
     * Whether an explicit key pair was configured, as against an instance role.
     *
     * <p>Both halves must be present. A configuration with one of the two set is
     * a half-finished edit, and falling back to the role there would take the
     * ambient identity while looking like it had taken the configured one.
     */
    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    /**
     * The origin a <b>browser</b> fetches presigned objects from — the value
     * {@code ContentSecurityPolicy} puts in {@code img-src} and {@code connect-src}.
     *
     * <p>This exists because the endpoint alone cannot answer the question in
     * production. Blank means "ask the SDK", and a CSP directive cannot ask the
     * SDK anything — it needs a literal origin before the first request is made.
     * Reading {@link #endpoint} directly, as the policy used to, put <i>nothing</i>
     * in those directives on a real deployment, and a blocked {@code img-src} is
     * a gallery of broken thumbnails that works perfectly on every laptop.
     *
     * <p>The two AWS forms are not interchangeable. Virtual-hosted style puts the
     * bucket in the host, so the origin is bucket-specific and a wrong guess is
     * silently blocked; path-style keeps the bucket in the path and shares one
     * regional origin.
     */
    public String browserEndpoint() {
        if (!awsManaged()) {
            return endpoint;
        }
        return pathStyle
                ? "https://s3." + region + ".amazonaws.com"
                : "https://" + bucket + ".s3." + region + ".amazonaws.com";
    }
}
