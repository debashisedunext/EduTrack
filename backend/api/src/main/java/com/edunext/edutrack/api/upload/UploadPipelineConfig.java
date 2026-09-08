package com.edunext.edutrack.api.upload;

import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentScanner;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentTypePolicy;
import com.edunext.edutrack.api.feature.tickets.attachments.ImageMetadataStripper;
import com.edunext.edutrack.api.feature.tickets.attachments.StorageKey;
import com.edunext.edutrack.api.feature.tickets.attachments.UnsupportedAttachmentTypeException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * B-107 · {@link UploadPipeline} over C-025's beans — the whole of the
 * indirection, in one file.
 *
 * <p><b>Nothing here decides anything.</b> Every method delegates, and that is
 * the property worth protecting: the moment this class starts choosing a media
 * type, widening an allow-list or interpreting a verdict, there are two answers
 * to "is this file safe" again and the port has become the second pipeline it
 * exists to prevent. The only translation performed is of an exception type, and
 * the message travels with it.
 *
 * <p>This is the one class in the application that names both sides. It lives
 * outside {@code api.feature} so that neither module names the other, which is
 * A-115's actual requirement — see {@link UploadPipeline} for why the smaller
 * fix was taken now and what the larger one is.
 *
 * <p><b>Not annotated {@code @ConditionalOn…} anything.</b> The beans it wraps
 * are unconditional, and a pipeline that quietly resolved to nothing in some
 * profile would make uploads fail at the first byte with a wiring error rather
 * than a refusal.
 */
@Configuration
class UploadPipelineConfig {

    @Bean
    UploadPipeline uploadPipeline(AttachmentTypePolicy types,
                                  ImageMetadataStripper stripper,
                                  AttachmentStorage storage,
                                  AttachmentScanner scanner,
                                  AttachmentProperties properties) {
        return new DelegatingUploadPipeline(types, stripper, storage, scanner, properties);
    }

    /**
     * A named class rather than a lambda-heavy anonymous one, so a stack trace
     * from a failed upload says where it went through.
     */
    private record DelegatingUploadPipeline(AttachmentTypePolicy types,
                                            ImageMetadataStripper stripper,
                                            AttachmentStorage storage,
                                            AttachmentScanner scanner,
                                            AttachmentProperties properties)
            implements UploadPipeline {

        @Override
        public Vetted vet(String fileName, byte[] content) {
            AttachmentTypePolicy.Accepted accepted;
            try {
                accepted = types.reconcile(fileName, content);
            } catch (UnsupportedAttachmentTypeException refused) {
                // The message is written for the person who chose the file and is
                // carried across unchanged; only the type is translated.
                throw new UnsupportedUploadTypeException(refused.getMessage(), refused);
            }
            return new Vetted(accepted.mediaType(), stripper.strip(accepted.type(), content));
        }

        @Override
        public void put(UploadKey key, byte[] content, String mediaType) {
            storage.put(adapt(key), content, mediaType);
        }

        @Override
        public URI signedDownloadUrl(UploadKey key, String fileName, String mediaType, Duration ttl) {
            return storage.signedDownloadUrl(adapt(key), fileName, mediaType, ttl);
        }

        @Override
        public void delete(UploadKey key) {
            storage.delete(adapt(key));
        }

        @Override
        public Optional<byte[]> read(UploadKey key) {
            return storage.read(adapt(key));
        }

        @Override
        public Verdict scan(String fileName, byte[] content) {
            return switch (scanner.scan(fileName, content)) {
                case CLEAN -> Verdict.CLEAN;
                case INFECTED -> Verdict.INFECTED;
                case UNKNOWN -> Verdict.UNKNOWN;
            };
        }

        @Override
        public long maxFileBytes() {
            return properties.maxFileBytes();
        }

        @Override
        public Duration signedUrlTtl() {
            return properties.signedUrlTtl();
        }

        @Override
        public Duration removalWindow() {
            return properties.deleteWindow();
        }

        @Override
        public boolean scanFailOpen() {
            return properties.scan().failOpen();
        }

        /**
         * Both interfaces declare one method with the same meaning, so the
         * adaptation is the method reference and nothing else. The key's own
         * validation has already happened in whichever implementation produced
         * it — this does not, and must not, re-derive it.
         */
        private static StorageKey adapt(UploadKey key) {
            return key::value;
        }
    }
}
