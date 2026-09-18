package com.edunext.edutrack.api.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * The S3/MinIO clients, built from {@code edutrack.storage} — <b>one pair for
 * the whole application</b>.
 *
 * <p>Written by C-025 as {@code AttachmentStorageConfig} and moved here by
 * B-036, which is the second consumer of the bucket that record's javadoc said
 * would trigger the move. What stayed behind in
 * {@code feature/tickets/attachments} is the one bean that is actually about
 * attachments; what came here is the two that are about the object store.
 *
 * <p>The alternative was a second {@link S3Client} declared in
 * {@code feature/imports}, which does not work and fails in the least helpful
 * way: two beans of one type make {@code AttachmentStorageConfig}'s own
 * by-type injection ambiguous, so adding an import feature would break
 * attachments at context startup.
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
 * <h2>Two things are conditional, and both are the difference between MinIO and S3</h2>
 *
 * <p><b>The endpoint override is applied only when one is configured.</b>
 * {@code ObjectStorageProperties} always said the endpoint is "empty in
 * production, where the AWS SDK resolves the real S3 endpoint from the region",
 * and this class overrode it unconditionally anyway — so following that
 * instruction produced {@code URI.create("")}, which has no scheme, which the
 * SDK rejects outright. A deployment that instead left the key unset got the
 * MinIO default and pointed production at {@code localhost:9000}. Neither is a
 * way to reach S3; {@link ObjectStorageProperties#awsManaged()} is.
 *
 * <p><b>Credentials fall back to the default provider chain when none are
 * configured.</b> This reverses what this file used to say, so the old reasoning
 * is answered rather than deleted: it argued that static properties keep a
 * misconfigured deployment "failing at startup on a missing property rather than
 * silently picking up whatever ambient role the host happens to carry".
 *
 * <ul>
 *   <li>The startup failure it describes never happened. The properties carry
 *       {@code minioadmin} defaults, so a deployment missing both keys starts
 *       cleanly and fails later, per-request, against S3.</li>
 *   <li>Nothing is silent: the chain is reached only by deliberately blanking
 *       both keys, and {@link ObjectStorageGuard} logs which identity was chosen
 *       at startup.</li>
 *   <li>The alternative it preferred is worse. Static keys in production mean a
 *       long-lived AWS secret in an environment variable, on every host, that
 *       nothing rotates — which is the credential most likely to end up in a log
 *       or an image layer. An instance role's credentials are minted per host and
 *       expire on their own.</li>
 * </ul>
 *
 * <p>MinIO is unaffected either way: it has no instance metadata, and the
 * {@code minioadmin} defaults mean local development never reaches the chain.
 */
@Configuration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class ObjectStorageConfig {

    @Bean
    S3Client objectStorageS3Client(ObjectStorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build());

        if (!properties.awsManaged()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    S3Presigner objectStorageS3Presigner(ObjectStorageProperties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build());

        if (!properties.awsManaged()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    /**
     * Configured keys when both are present, the instance role otherwise.
     *
     * <p>{@link DefaultCredentialsProvider} resolves environment variables, the
     * shared profile file, container credentials (ECS, and EKS via IRSA) and the
     * EC2 instance metadata service, in that order — so one blank pair covers
     * every way AWS hands an identity to a host.
     */
    private static AwsCredentialsProvider credentials(ObjectStorageProperties properties) {
        if (!properties.hasStaticCredentials()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
