package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * C-025 · {@code edutrack.storage} — MinIO in development, S3 in production
 * (PLAN.md §2.1).
 *
 * <p>These four keys have been in {@code application.yml} since Stream A wrote
 * it and this is their first consumer. PLAN.md lists three eventual users of the
 * bucket — attachments, avatars and import error reports — so <b>when the second
 * one arrives this record should move out of this package</b> rather than being
 * imported across a feature boundary. It lives here today because a
 * configuration class in a shared package with exactly one caller is speculative
 * generality, and moving it later is a rename.
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
record AttachmentStorageProperties(

        @DefaultValue("http://localhost:9000") String endpoint,
        @DefaultValue("edutrack") String bucket,
        @DefaultValue("minioadmin") String accessKey,
        @DefaultValue("minioadmin") String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("true") boolean pathStyle) {
}
