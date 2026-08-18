package com.edunext.edutrack.api.feature.imports;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * B-036 · {@link ImportReportStore} against S3/MinIO — PLAN.md §2.2.
 *
 * <p>Uses the application's one {@link S3Client}, declared in
 * {@code api/storage/ObjectStorageConfig}. The bucket is the same bucket
 * attachments use; the key prefix is what keeps them apart.
 */
class S3ImportReportStore implements ImportReportStore {

    /**
     * {@code imports/CLIENT/412/errors.xlsx}.
     *
     * <p>The entity code and the batch id are both in the path rather than only
     * the id, so a bucket listing is readable by a person looking for one run —
     * and so a lifecycle rule can expire import artefacts without touching
     * {@code tickets/}, which is the prefix §4B.4 gave attachments.
     *
     * <p>The <em>object</em> name is fixed. What the browser saves it as is
     * decided at download time by {@link ImportErrorReportService#fileName},
     * because that name is for a person's Downloads folder and this one only has
     * to be unique within its own directory.
     */
    private static final String KEY_FORMAT = "imports/%s/%d/errors.xlsx";

    /** The contract's media type for the template, and the same file format. */
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final S3Client s3;
    private final String bucket;

    S3ImportReportStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String put(long batchId, String entityCode, byte[] workbook) {
        String key = KEY_FORMAT.formatted(entityCode, batchId);
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        // Stored on the object because it is served back on the
                        // download, and a workbook served as octet-stream is one
                        // Excel opens with a warning the user has no way to read.
                        .contentType(XLSX)
                        .build(),
                RequestBody.fromBytes(workbook));
        return key;
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException gone) {
            // An object a lifecycle rule expired, or one a bucket was emptied
            // of. Empty rather than an exception: the batch row still says a
            // report was written, and the honest answer to the download is that
            // it is no longer there — which is a 404, not a 500.
            return Optional.empty();
        }
    }
}
