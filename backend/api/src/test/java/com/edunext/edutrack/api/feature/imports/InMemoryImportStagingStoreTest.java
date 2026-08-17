package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B-030 · the interim staging store — expiry and the concurrency ceiling. */
class InMemoryImportStagingStoreTest {

    private static final Instant T0 = Instant.parse("2026-08-10T10:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryImportStagingStore store =
            new InMemoryImportStagingStore(clock, Duration.ofMinutes(30), 3);

    @Test
    void stagesAndFindsAnUpload() {
        StagedUpload upload = upload("clients.xlsx");

        UUID id = store.stage(upload);

        assertThat(store.find(id)).contains(upload);
    }

    @Test
    void findingSomethingNeverStagedIsEmptyRatherThanAFailure() {
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a wizard left open past the TTL expires rather than committing a stale file")
    void expiresAfterTheTtl() {
        UUID id = store.stage(upload("clients.xlsx"));

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.find(id)).isEmpty();
    }

    @Test
    void survivesRightUpToTheTtl() {
        UUID id = store.stage(upload("clients.xlsx"));

        clock.advance(Duration.ofMinutes(29));

        assertThat(store.find(id)).isPresent();
    }

    @Test
    void discardRemovesImmediately() {
        UUID id = store.stage(upload("clients.xlsx"));

        store.discard(id);

        assertThat(store.find(id)).isEmpty();
    }

    @Test
    @DisplayName("over the ceiling it refuses rather than evicting somebody else's work")
    void refusesBeyondTheCap() {
        store.stage(upload("a.xlsx"));
        store.stage(upload("b.xlsx"));
        store.stage(upload("c.xlsx"));

        assertThatThrownBy(() -> store.stage(upload("d.xlsx")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many imports in progress");
    }

    @Test
    @DisplayName("expired entries free their slot, so the cap is concurrency and not a lifetime total")
    void expiredEntriesReleaseCapacity() {
        store.stage(upload("a.xlsx"));
        store.stage(upload("b.xlsx"));
        store.stage(upload("c.xlsx"));

        clock.advance(Duration.ofMinutes(31));

        UUID id = store.stage(upload("d.xlsx"));
        assertThat(store.find(id)).isPresent();
    }

    private StagedUpload upload(String fileName) {
        return new StagedUpload(
                UUID.randomUUID(),
                fileName,
                List.of("Sheet1"),
                "Sheet1",
                List.of("Client Code", "Name"),
                List.of(new StagedRow(2, Map.of("Client Code", "ACME", "Name", "Acme Corporation"))),
                clock.instant());
    }

    /** A clock that only this test moves — expiry is not assertable against one that ticks on its own. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
