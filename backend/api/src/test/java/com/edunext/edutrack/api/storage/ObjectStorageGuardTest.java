package com.edunext.edutrack.api.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Each refusal, asserted directly — so the guard cannot be weakened without a
 * test saying so, and so the messages stay the ones a deployment actually needs.
 */
class ObjectStorageGuardTest {

    /** A correctly configured production deployment: blank endpoint, instance role. */
    private static ObjectStorageProperties valid() {
        return new ObjectStorageProperties("", "edutrack-prod", "", "", "ap-south-1", false);
    }

    @Test
    @DisplayName("a real S3 configuration starts")
    void validConfigurationPasses() {
        assertThatCode(() -> ObjectStorageGuard.check(valid())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-AWS S3-compatible host is allowed — MinIO on a server is a real deployment")
    void selfHostedEndpointPasses() {
        var properties = new ObjectStorageProperties(
                "https://minio.internal:9000", "edutrack", "key", "secret", "us-east-1", true);

        assertThatCode(() -> ObjectStorageGuard.check(properties)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:9000",
            "http://127.0.0.1:9000",
            "http://[::1]:9000",
            "HTTP://LOCALHOST:9000"
    })
    @DisplayName("the local MinIO default is refused — this is the mistake the guard exists for")
    void localhostIsRefused(String endpoint) {
        var properties = new ObjectStorageProperties(
                endpoint, "edutrack", "minioadmin", "minioadmin", "us-east-1", true);

        assertThatIllegalStateException()
                .isThrownBy(() -> ObjectStorageGuard.check(properties))
                .withMessageContaining("S3_ENDPOINT");
    }

    @Test
    @DisplayName("path-style against real AWS is refused — it does not resolve for modern buckets")
    void pathStyleAgainstAwsIsRefused() {
        var properties = new ObjectStorageProperties("", "edutrack-prod", "", "", "ap-south-1", true);

        assertThatIllegalStateException()
                .isThrownBy(() -> ObjectStorageGuard.check(properties))
                .withMessageContaining("S3_PATH_STYLE=false");
    }

    @Test
    @DisplayName("the committed minioadmin credentials are refused in production")
    void committedCredentialsAreRefused() {
        var properties = new ObjectStorageProperties(
                "", "edutrack-prod", "minioadmin", "minioadmin", "ap-south-1", false);

        assertThatIllegalStateException()
                .isThrownBy(() -> ObjectStorageGuard.check(properties))
                .withMessageContaining("minioadmin");
    }

    @Test
    @DisplayName("real static credentials are allowed, for a deployment with no instance role")
    void realStaticCredentialsPass() {
        var properties = new ObjectStorageProperties(
                "", "edutrack-prod", "AKIAEXAMPLE", "a-real-secret", "ap-south-1", false);

        assertThatCode(() -> ObjectStorageGuard.check(properties)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a missing bucket is refused")
    void blankBucketIsRefused(String bucket) {
        var properties = new ObjectStorageProperties("", bucket, "", "", "ap-south-1", false);

        assertThatIllegalStateException()
                .isThrownBy(() -> ObjectStorageGuard.check(properties))
                .withMessageContaining("S3_BUCKET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a missing region is refused — SigV4 signs it, so a wrong one breaks every presigned URL")
    void blankRegionIsRefused(String region) {
        var properties = new ObjectStorageProperties("", "edutrack-prod", "", "", region, false);

        assertThatIllegalStateException()
                .isThrownBy(() -> ObjectStorageGuard.check(properties))
                .withMessageContaining("S3_REGION");
    }
}
