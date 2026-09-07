package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.domain.onboarding.ObEscalation;
import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import com.edunext.edutrack.domain.onboarding.ObEscalationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-115 · list paging, scope short-circuiting, and the acknowledge/resolve
 * outcomes — without a database. {@code ObEscalationScannerIT} covers the
 * SQL and the ladder itself.
 */
class ObEscalationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final long ME = 7;

    private final ObEscalationReadRepository reads = mock(ObEscalationReadRepository.class);
    private final ObEscalationRepository escalations = mock(ObEscalationRepository.class);
    private final ObEscalationService service =
            new ObEscalationService(reads, escalations, Clock.fixed(NOW, ZoneOffset.UTC));

    private static final ObEscalationScope ADMIN = new ObEscalationScope("OB_ADMIN", ME);
    private static final ObEscalationScope NO_STANDING = new ObEscalationScope("", ME);

    // ── list ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a caller with no onboarding standing sees an empty list, never the database")
    void deniedScopeNeverQueries() {
        var page = service.list(NO_STANDING, null, null, null, null, null, null, 10);

        assertThat(page.data()).isEmpty();
        assertThat(page.meta().hasMore()).isFalse();
        verify(reads, never()).list(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("the extra row is asked for, and never sent")
    void theProbeRowIsStripped() {
        when(reads.list(eq(ADMIN), any(), any(), any(), any(), any(), any(), eq(11)))
                .thenReturn(rows(11));

        var page = service.list(ADMIN, null, null, null, null, null, null, 10);

        assertThat(page.data()).hasSize(10);
        assertThat(page.meta().hasMore()).isTrue();
        // The cursor names the last row sent, never the probe row.
        assertThat(page.meta().nextCursor()).isNotNull();
    }

    @Test
    void aShortPageHasNoCursor() {
        when(reads.list(eq(ADMIN), any(), any(), any(), any(), any(), any(), eq(11)))
                .thenReturn(rows(4));

        var page = service.list(ADMIN, null, null, null, null, null, null, 10);

        assertThat(page.data()).hasSize(4);
        assertThat(page.meta().hasMore()).isFalse();
        assertThat(page.meta().nextCursor()).isNull();
    }

    // ── acknowledge ──────────────────────────────────────────────────────

    @Test
    @DisplayName("no such row, or one out of scope, is not found")
    void acknowledgeMissingIsNotFound() {
        when(reads.findById(ADMIN, 5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(ADMIN, 5, ME))
                .isInstanceOf(EscalationNotFoundException.class);
    }

    @Test
    @DisplayName("a resolved rung cannot be acknowledged")
    void acknowledgeResolvedIsRefused() {
        when(reads.findById(ADMIN, 5)).thenReturn(Optional.of(row(5, NOW, NOW, ME)));

        assertThatThrownBy(() -> service.acknowledge(ADMIN, 5, ME))
                .isInstanceOf(EscalationAlreadyResolvedException.class);
        verify(escalations, never()).findById(anyLong());
    }

    @Test
    @DisplayName("acknowledging an already-acknowledged rung returns it unchanged, not restamped")
    void acknowledgeIsIdempotent() {
        when(reads.findById(ADMIN, 5)).thenReturn(Optional.of(row(5, NOW.minusSeconds(60), null, null)));

        var result = service.acknowledge(ADMIN, 5, ME);

        assertThat(result.acknowledgedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(escalations, never()).findById(anyLong());
        verify(escalations, never()).save(any());
    }

    @Test
    @DisplayName("a fresh acknowledge stamps the caller and the current time")
    void acknowledgeStampsCallerAndNow() {
        when(reads.findById(ADMIN, 5))
                .thenReturn(Optional.of(row(5, null, null, null)))
                .thenReturn(Optional.of(row(5, NOW, null, null)));
        ObEscalation entity = entity(5);
        when(escalations.findById(5L)).thenReturn(Optional.of(entity));

        service.acknowledge(ADMIN, 5, ME);

        assertThat(entity.getAcknowledgedBy()).isEqualTo(ME);
        assertThat(entity.getAcknowledgedAt()).isEqualTo(NOW);
        verify(escalations).save(entity);
    }

    // ── resolve ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a resolved rung cannot be resolved again")
    void resolveResolvedIsRefused() {
        when(reads.findById(ADMIN, 5)).thenReturn(Optional.of(row(5, NOW, NOW, ME)));

        assertThatThrownBy(() -> service.resolve(ADMIN, 5, ME, "fixed it"))
                .isInstanceOf(EscalationAlreadyResolvedException.class);
    }

    @Test
    @DisplayName("resolving stamps the caller, the time and the mandatory note")
    void resolveStampsEverything() {
        when(reads.findById(ADMIN, 5))
                .thenReturn(Optional.of(row(5, NOW.minusSeconds(60), null, null)))
                .thenReturn(Optional.of(row(5, NOW.minusSeconds(60), NOW, ME)));
        ObEscalation entity = entity(5);
        when(escalations.findById(5L)).thenReturn(Optional.of(entity));

        service.resolve(ADMIN, 5, ME, "fixed it");

        assertThat(entity.getResolvedBy()).isEqualTo(ME);
        assertThat(entity.getResolvedAt()).isEqualTo(NOW);
        assertThat(entity.getResolutionNote()).isEqualTo("fixed it");
        verify(escalations).save(entity);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static ObEscalation entity(long id) {
        ObEscalation e = new ObEscalation();
        e.setId(id);
        e.setObClientId(1L);
        e.setJourneyId(2L);
        e.setStepId(3L);
        e.setLevel(ObEscalationLevel.L1);
        e.setReason("TAT_BREACH");
        e.setEscalatedAt(NOW.minusSeconds(600));
        return e;
    }

    private static ObEscalationReadRepository.Row row(long id, Instant acknowledgedAt,
                                                       Instant resolvedAt, Long resolvedBy) {
        return new ObEscalationReadRepository.Row(
                id, 1L, "Acme", 2L, 3L, "Data migration", "L1", "TAT_BREACH",
                9L, "Owner", NOW.minusSeconds(600),
                acknowledgedAt == null ? null : ME, "Me", acknowledgedAt,
                resolvedBy, "Resolver", resolvedAt,
                resolvedAt == null ? null : "fixed it");
    }

    private static List<ObEscalationReadRepository.Row> rows(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> row(i, null, null, null))
                .toList();
    }
}
