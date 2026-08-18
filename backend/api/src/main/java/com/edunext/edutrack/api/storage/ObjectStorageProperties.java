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
 * @param endpoint MinIO's address locally; empty in production, where the AWS
 *                 SDK resolves the real S3 endpoint from the region
 * @param region   not in {@code application.yml} and defaulted, because MinIO
 *                 ignores it entirely while the SDK insists on one being set —
 *                 a signature is computed over it either way
 * @param pathStyle {@code http://host:9000/bucket/key} rather than
 *                  {@code http://bucket.host:9000/key}. Required for MinIO,
 *                  where the bucket is not a DNS name, and harmless against S3
 */
@ConfigurationProperties("edutrack.storage")
public record ObjectStorageProperties(

        @DefaultValue("http://localhost:9000") String endpoint,
        @DefaultValue("edutrack") String bucket,
        @DefaultValue("minioadmin") String accessKey,
        @DefaultValue("minioadmin") String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("true") boolean pathStyle) {
}
