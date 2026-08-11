package com.edunext.edutrack.api.feature.imports;

import java.util.Optional;
import java.util.UUID;

/**
 * B-030 · where an upload waits between step 2 and step 5.
 *
 * <p>An interface with one implementation, which normally deserves suspicion.
 * It is here because the eventual implementation is decided and is not this one:
 * PLAN.md §2.2 puts import artefacts in MinIO/S3, and <b>no object-storage
 * client is wired into this backend yet</b> — the AWS SDK is a declared
 * dependency with no bean behind it. B-030 needs somewhere to put a parsed
 * workbook now, and the choice of where is B-032's and B-036's to make.
 *
 * <p>Naming the seam costs one file. Not naming it means the temporary answer
 * becomes load-bearing across four tasks first and is then discovered to be
 * load-bearing.
 */
public interface ImportStagingStore {

    /** @return the handle the wizard carries as {@code uploadId} */
    UUID stage(StagedUpload upload);

    /**
     * @return empty once the upload has expired — a wizard left open over lunch
     *         must fail with "your upload expired, please re-upload", not commit
     *         a file the user has since edited
     */
    Optional<StagedUpload> find(UUID uploadId);

    /** Called after a successful commit; expiry handles everything else. */
    void discard(UUID uploadId);
}
