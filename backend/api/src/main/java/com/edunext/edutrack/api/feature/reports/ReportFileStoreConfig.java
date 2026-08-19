package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.storage.ObjectStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A-065 · binds {@link ReportFileStore} to its S3 implementation, following
 * {@code ImportReportStoreConfig} exactly.
 *
 * <p>The wiring is here rather than an {@code @Component} on
 * {@link S3ReportFileStore} so that everything above — the runner and the
 * download route — sees the interface, and a test can substitute an in-memory
 * store without MinIO running.
 */
@Configuration
class ReportFileStoreConfig {

    @Bean
    ReportFileStore reportFileStore(S3Client s3, ObjectStorageProperties properties) {
        return new S3ReportFileStore(s3, properties.bucket());
    }
}
