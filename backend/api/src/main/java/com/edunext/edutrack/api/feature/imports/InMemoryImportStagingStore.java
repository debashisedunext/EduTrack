package com.edunext.edutrack.api.feature.imports;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B-030 · the interim staging store, and openly a stopgap.
 *
 * <p><b>This holds parsed workbooks in heap.</b> That is uncomfortably close to
 * the thing PLAN.md §2.2 rejects the POI DOM reader for — "would load a
 * 5,000-row workbook fully into memory per concurrent import" — and the
 * resemblance is not lost on me. Two differences make it survivable in the
 * short term, and neither makes it right:
 *
 * <ul>
 *   <li>What is held is extracted cell text, not a POI object graph. A 5,000-row
 *       client sheet is a few MB of strings against tens of MB of {@code XSSFRow}.
 *   <li>{@link #maxUploads} caps concurrent stagings outright, so the ceiling is
 *       bounded and known rather than however many people happen to click at once.
 *   </ul>
 *
 * <p>It is replaced by object storage in B-032/B-036, which is the task that
 * introduces the S3 client. Until then this is <b>single-instance only</b>: two
 * API pods behind a load balancer would stage on one and validate on the other,
 * and the user would be told their upload expired. That is the strongest reason
 * to replace it and the reason it is stated here rather than discovered in
 * staging.
 */
@Component
class InMemoryImportStagingStore implements ImportStagingStore {

    private final Map<UUID, StagedUpload> staged = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int maxUploads;

    @Autowired
    InMemoryImportStagingStore(
            @Value("${edutrack.imports.staging-ttl:PT30M}") Duration ttl,
            @Value("${edutrack.imports.max-staged-uploads:20}") int maxUploads) {
        this(Clock.systemUTC(), ttl, maxUploads);
    }

    /** Test seam — expiry cannot be asserted against a clock that only moves forwards. */
    InMemoryImportStagingStore(Clock clock, Duration ttl, int maxUploads) {
        this.clock = clock;
        this.ttl = ttl;
        this.maxUploads = maxUploads;
    }

    @Override
    public UUID stage(StagedUpload upload) {
        purgeExpired();
        if (staged.size() >= maxUploads) {
            // Refusing is the honest failure. Evicting somebody else's upload to
            // make room would fail their wizard at commit, in a way they cannot
            // distinguish from losing their work.
            throw new IllegalStateException(
                    "Too many imports in progress (" + maxUploads + "). Try again shortly.");
        }
        staged.put(upload.uploadId(), upload);
        return upload.uploadId();
    }

    @Override
    public Optional<StagedUpload> find(UUID uploadId) {
        purgeExpired();
        return Optional.ofNullable(staged.get(uploadId));
    }

    @Override
    public void discard(UUID uploadId) {
        staged.remove(uploadId);
    }

    /**
     * Swept on access rather than by a scheduled task: the map is small and
     * bounded, and a {@code @Scheduled} sweep here would need ShedLock reasoning
     * for a structure that is per-instance by nature.
     */
    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        Iterator<StagedUpload> it = staged.values().iterator();
        while (it.hasNext()) {
            if (it.next().stagedAt().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}
