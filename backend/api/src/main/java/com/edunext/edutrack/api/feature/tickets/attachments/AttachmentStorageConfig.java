package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.storage.ObjectStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * C-025 · the attachment half of the object store.
 *
 * <p><b>The clients themselves moved to {@code api/storage/ObjectStorageConfig}
 * in B-036</b>, which is exactly what {@code AttachmentStorageProperties} asked
 * for in writing: PLAN.md §2.2 lists three users of the bucket — attachments,
 * avatars and import error reports — and the record said it should move out of
 * this package "when the second one arrives" rather than be imported across a
 * feature boundary. The import error report is the second one.
 *
 * <p>What is left here is the bean that is genuinely about attachments. Nothing
 * else changed: {@link AttachmentStorage} is unchanged, {@link S3AttachmentStorage}
 * is unchanged, and the {@code edutrack.storage} keys are the same keys with the
 * same defaults.
 */
@Configuration
class AttachmentStorageConfig {

    @Bean
    AttachmentStorage attachmentStorage(S3Client s3, S3Presigner presigner,
                                        ObjectStorageProperties properties) {
        return new S3AttachmentStorage(s3, presigner, properties.bucket());
    }
}
