package com.edunext.edutrack.api.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three questions the record answers that its components do not: which
 * store, which identity, and which origin a browser will be talking to.
 */
class ObjectStoragePropertiesTest {

    private static ObjectStorageProperties minio() {
        return new ObjectStorageProperties(
                "http://localhost:9000", "edutrack", "minioadmin", "minioadmin", "us-east-1", true);
    }

    private static ObjectStorageProperties awsS3() {
        return new ObjectStorageProperties("", "edutrack-prod", "", "", "ap-south-1", false);
    }

    @Nested
    @DisplayName("awsManaged")
    class AwsManaged {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("a blank endpoint means real S3 — the SDK resolves it from the region")
        void blankIsAws(String endpoint) {
            assertThat(props(endpoint).awsManaged()).isTrue();
        }

        @Test
        @DisplayName("a configured endpoint is an override, and is never AWS-managed")
        void configuredIsOverride() {
            assertThat(minio().awsManaged()).isFalse();
            assertThat(props("https://minio.internal:9000").awsManaged()).isFalse();
        }

        private ObjectStorageProperties props(String endpoint) {
            return new ObjectStorageProperties(endpoint, "edutrack", "", "", "us-east-1", false);
        }
    }

    @Nested
    @DisplayName("hasStaticCredentials")
    class StaticCredentials {

        @Test
        @DisplayName("both present is a configured key pair")
        void bothPresent() {
            assertThat(minio().hasStaticCredentials()).isTrue();
        }

        @Test
        @DisplayName("both blank routes to the instance role")
        void bothBlank() {
            assertThat(awsS3().hasStaticCredentials()).isFalse();
        }

        @Test
        @DisplayName("half a key pair is a half-finished edit, not a request for the instance role")
        void halfConfiguredIsNotCredentials() {
            // Falling back to the role here would take the ambient identity while
            // looking like it had taken the configured one.
            assertThat(creds("AKIAEXAMPLE", "").hasStaticCredentials()).isFalse();
            assertThat(creds("", "secret").hasStaticCredentials()).isFalse();
            assertThat(creds(null, "secret").hasStaticCredentials()).isFalse();
            assertThat(creds("AKIAEXAMPLE", null).hasStaticCredentials()).isFalse();
        }

        private ObjectStorageProperties creds(String access, String secret) {
            return new ObjectStorageProperties("", "edutrack", access, secret, "us-east-1", false);
        }
    }

    @Nested
    @DisplayName("browserEndpoint — what reaches the CSP")
    class BrowserEndpoint {

        @Test
        @DisplayName("an overridden endpoint is handed back unchanged")
        void overrideIsPassedThrough() {
            assertThat(minio().browserEndpoint()).isEqualTo("http://localhost:9000");
        }

        @Test
        @DisplayName("virtual-hosted style puts the bucket in the host, so the origin is bucket-specific")
        void virtualHostedStyle() {
            assertThat(awsS3().browserEndpoint())
                    .isEqualTo("https://edutrack-prod.s3.ap-south-1.amazonaws.com");
        }

        @Test
        @DisplayName("path-style shares one regional origin across buckets")
        void pathStyle() {
            var properties = new ObjectStorageProperties("", "edutrack-prod", "", "", "eu-west-1", true);

            assertThat(properties.browserEndpoint()).isEqualTo("https://s3.eu-west-1.amazonaws.com");
        }

        @Test
        @DisplayName("it is never blank against AWS — a blank one drops the source from img-src entirely")
        void neverBlankAgainstAws() {
            // The regression this method exists for: the policy read endpoint()
            // directly, which is blank in production, so every presigned download
            // and thumbnail was blocked by a policy that was correct on a laptop.
            assertThat(awsS3().browserEndpoint()).isNotBlank().startsWith("https://");
        }
    }
}
