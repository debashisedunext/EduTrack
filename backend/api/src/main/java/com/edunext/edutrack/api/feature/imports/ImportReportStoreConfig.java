package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.api.storage.ObjectStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * B-036 · the one bean that binds the error report to the object store.
 *
 * <p>Deliberately the only place in this package that names S3. Everything else
 * — the writer, the service, the route — sees {@link ImportReportStore}, which
 * is why {@code ImportErrorReportServiceTest} can prove that a storage outage
 * costs a report rather than an import without a container running.
 */
@Configuration
class ImportReportStoreConfig {

    @Bean
    ImportReportStore importReportStore(S3Client s3, ObjectStorageProperties properties) {
        return new S3ImportReportStore(s3, properties.bucket());
    }
}
