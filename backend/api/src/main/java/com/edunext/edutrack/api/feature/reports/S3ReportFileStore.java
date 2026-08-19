package com.edunext.edutrack.api.feature.reports;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * A-065 · {@link ReportFileStore} against S3/MinIO.
 *
 * <p>Uses the application's one {@link S3Client} from
 * {@code api/storage/ObjectStorageConfig}. Same bucket as attachments and
 * import reports; the key prefix is what keeps them apart.
 */
class S3ReportFileStore implements ReportFileStore {

    /**
     * {@code reports/schedules/12/runs/348/sla-breach-2026-08-19.xlsx}.
     *
     * <p>Both ids in the path rather than only the run id, so a bucket listing
     * groups a schedule's history together for a person looking through it, and
     * so a lifecycle rule can expire {@code reports/} without touching
     * {@code tickets/} — where §4B.4 put attachments that must be kept.
     *
     * <p>The human filename is in the key as well as in the database. It costs
     * nothing and it means an object recovered from a backup with no row beside
     * it still says what it is.
     */
    private static final String KEY_FORMAT = "reports/schedules/%d/runs/%d/%s";

    private final S3Client s3;
    private final String bucket;

    S3ReportFileStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String put(long scheduleId, long runId, String fileName, String contentType, byte[] file) {
        String key = KEY_FORMAT.formatted(scheduleId, runId, fileName);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        // The real type, not octet-stream: it is served straight
                        // back on the download, and a workbook delivered as
                        // octet-stream is one Excel opens with a warning the
                        // user has no way to act on.
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(file));
        return key;
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException gone) {
            // A run row can outlive its object — a lifecycle rule expires the
            // file long before the history of who was emailed stops mattering.
            // Empty rather than an exception, so the route answers 404 for a
            // file that has aged out rather than 500 for one that was never
            // there.
            return Optional.empty();
        }
    }
}
