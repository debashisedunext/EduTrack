package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * C-025 · the S3/MinIO clients, built from {@code edutrack.storage}.
 *
 * <p>Two clients rather than one, because they do different jobs: {@link S3Client}
 * moves bytes and {@link S3Presigner} only computes signatures — it never opens a
 * connection at all, which is why a presigned URL can be minted for an object in
 * a bucket the application cannot currently reach.
 *
 * <p><b>Nothing here contacts the network.</b> Both builders resolve credentials
 * and endpoints eagerly from configuration and connect lazily, which is what lets
 * {@code ApplicationSmokeTest} and {@code RouteAuthorizationTest} build the whole
 * context with no MinIO running — the same property {@code docker compose up}
 * being optional for a unit test depends on.
 *
 * <p>Credentials come from properties rather than from the SDK's default provider
 * chain. That is right for MinIO, which has no instance metadata to query, and it
 * keeps a misconfigured production deployment failing at startup on a missing
 * property rather than silently picking up whatever ambient role the host
 * happens to carry.
 */
@Configuration
@EnableConfigurationProperties(AttachmentStorageProperties.class)
class AttachmentStorageConfig {

    @Bean
    S3Client attachmentS3Client(AttachmentStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    @Bean
    S3Presigner attachmentS3Presigner(AttachmentStorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build())
                .build();
    }

    @Bean
    AttachmentStorage attachmentStorage(S3Client s3, S3Presigner presigner,
                                        AttachmentStorageProperties properties) {
        return new S3AttachmentStorage(s3, presigner, properties.bucket());
    }

    private static StaticCredentialsProvider credentials(AttachmentStorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
