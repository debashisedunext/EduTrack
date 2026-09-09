package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.api.feature.onboarding.communications.ObCommunicationService;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-126 · {@code ob_client_escalations}' raise idempotency, the guarded
 * resolve, and which notifications each fires — without a database. The SQL
 * itself (the unique-open constraint, the guarded {@code UPDATE}) is a job
 * for an integration test against a real MySQL instance, not built here.
 */
class ObClientEscalationServiceTest {

    private static final long OB_CLIENT_ID = 1L;
    private static final long JOURNEY_ID = 2L;
    private static final long STEP_ID = 3L;
    private static final long CONTACT_ID = 9L;
    private static final long OWNER_ID = 21L;
    private static final long MANAGER_ID = 22L;
    private static final long STAFF_ID = 30L;
    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

    private static final ObEscalationScope ADMIN = new ObEscalationScope("OB_ADMIN", STAFF_ID);
    private static final ObEscalationScope NO_STANDING = new ObEscalationScope("", STAFF_ID);

    private final ObClientEscalationRepository repository = mock(ObClientEscalationRepository.class);
    private final ObCommunicationService communications = mock(ObCommunicationService.class);
    private final ObOutboxEnqueuer outbox = mock(ObOutboxEnqueuer.class);
    private final ObClientEscalationService service =
            new ObClientEscalationService(repository, communications, outbox);

    // ── list ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a caller with no onboarding standing sees an empty list, never the database")
    void deniedScopeNeverQueries() {
        var page = service.list(NO_STANDING, null, null, null, null, null);

        assertThat(page.data()).isEmpty();
        verify(repository, never()).list(any(), any(), any(), any(), any(), anyInt());
    }

    // ── raise ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("raising notifies the manager and the owner, on both EMAIL and WHATSAPP, and mirrors the comment")
    void raiseNotifiesManagerAndOwnerAndMirrors() {
        when(repository.findOpenByStep(STEP_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(100L, "It's down", NOW, null, null, null)));
        when(repository.insert(OB_CLIENT_ID, JOURNEY_ID, STEP_ID, CONTACT_ID, "It's down", NOW)).thenReturn(100L);
        when(repository.resolveOnboardingManager()).thenReturn(MANAGER_ID);
        when(outbox.enqueue(any())).thenReturn(OptionalLong.of(1L));

        var result = service.raise(new ObClientEscalationService.RaiseCommand(
                OB_CLIENT_ID, "Acme", JOURNEY_ID, STEP_ID, "Data migration", "Payroll",
                OWNER_ID, CONTACT_ID, "It's down", NOW));

        assertThat(result.isNew()).isTrue();
        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.comment()).isEqualTo("It's down");

        verify(communications).recordEscalationRaised(STEP_ID, JOURNEY_ID, OB_CLIENT_ID, CONTACT_ID, "It's down", NOW);

        ArgumentCaptor<ObNotification> sent = ArgumentCaptor.forClass(ObNotification.class);
        verify(outbox, times(4)).enqueue(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(n -> n.recipient().type() + ":" + n.channel())
                .containsExactlyInAnyOrder("STAFF:EMAIL", "STAFF:WHATSAPP", "STAFF:EMAIL", "STAFF:WHATSAPP");
        assertThat(sent.getAllValues()).allSatisfy(n ->
                assertThat(n.eventKey()).isEqualTo("CLIENT_ESCALATION_RAISED"));
    }

    @Test
    @DisplayName("no manager and no owner resolved: raised and mirrored, nothing queued")
    void raiseWithNobodyToTellStillRaises() {
        when(repository.findOpenByStep(STEP_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(100L, "It's down", NOW, null, null, null)));
        when(repository.insert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(100L);
        when(repository.resolveOnboardingManager()).thenReturn(null);

        var result = service.raise(new ObClientEscalationService.RaiseCommand(
                OB_CLIENT_ID, "Acme", JOURNEY_ID, STEP_ID, "Data migration", "Payroll",
                null, CONTACT_ID, "It's down", NOW));

        assertThat(result.isNew()).isTrue();
        verify(outbox, never()).enqueue(any());
        verify(communications).recordEscalationRaised(STEP_ID, JOURNEY_ID, OB_CLIENT_ID, CONTACT_ID, "It's down", NOW);
    }

    @Test
    @DisplayName("an already-open escalation on the step is returned unchanged, not raised again")
    void raiseIsIdempotentWhenAlreadyOpen() {
        when(repository.findOpenByStep(STEP_ID))
                .thenReturn(Optional.of(row(55L, "Already open", NOW.minusSeconds(60), null, null, null)));

        var result = service.raise(new ObClientEscalationService.RaiseCommand(
                OB_CLIENT_ID, "Acme", JOURNEY_ID, STEP_ID, "Data migration", "Payroll",
                OWNER_ID, CONTACT_ID, "Second click", NOW));

        assertThat(result.isNew()).isFalse();
        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.comment()).isEqualTo("Already open");
        verify(repository, never()).insert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(communications, never()).recordEscalationRaised(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    @DisplayName("a lost race on the unique-open index is treated the same as finding it open first")
    void raiseHandlesLostRaceOnUniqueIndex() {
        when(repository.findOpenByStep(STEP_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row(55L, "Somebody else's click", NOW.minusSeconds(5), null, null, null)));
        when(repository.insert(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("uq_ob_client_escalations_open"));

        var result = service.raise(new ObClientEscalationService.RaiseCommand(
                OB_CLIENT_ID, "Acme", JOURNEY_ID, STEP_ID, "Data migration", "Payroll",
                OWNER_ID, CONTACT_ID, "My click", NOW));

        assertThat(result.isNew()).isFalse();
        assertThat(result.id()).isEqualTo(55L);
        verify(communications, never()).recordEscalationRaised(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(any());
    }

    // ── resolve ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no such row, or one out of scope, is not found")
    void resolveMissingIsNotFound() {
        when(repository.findById(ADMIN, 5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(ADMIN, 5, STAFF_ID, "fixed it"))
                .isInstanceOf(ObClientEscalationNotFoundException.class);
    }

    @Test
    @DisplayName("an already-resolved escalation cannot be resolved again")
    void resolveResolvedIsRefused() {
        when(repository.findById(ADMIN, 5))
                .thenReturn(Optional.of(row(5, "It's down", NOW.minusSeconds(600), STAFF_ID, "Fixed", NOW)));

        assertThatThrownBy(() -> service.resolve(ADMIN, 5, STAFF_ID, "fixed it"))
                .isInstanceOf(ObClientEscalationAlreadyResolvedException.class);
        verify(repository, never()).resolve(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("losing the guarded UPDATE race is the same 422 as reading it already resolved")
    void resolveLostRaceIsAlreadyResolved() {
        when(repository.findById(ADMIN, 5))
                .thenReturn(Optional.of(row(5, "It's down", NOW.minusSeconds(600), null, null, null)));
        when(repository.resolve(eq(5L), eq(STAFF_ID), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.resolve(ADMIN, 5, STAFF_ID, "fixed it"))
                .isInstanceOf(ObClientEscalationAlreadyResolvedException.class);
        verify(communications, never()).recordEscalationResolved(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    @DisplayName("resolving mirrors the note to the timeline and acknowledges the client contact by EMAIL")
    void resolveMirrorsAndNotifiesClient() {
        var open = row(5, "It's down", NOW.minusSeconds(600), null, null, null);
        when(repository.findById(ADMIN, 5))
                .thenReturn(Optional.of(open))
                .thenReturn(Optional.of(row(5, "It's down", NOW.minusSeconds(600), STAFF_ID, "Fixed it", NOW)));
        when(repository.resolve(eq(5L), eq(STAFF_ID), eq("Fixed it"), any())).thenReturn(true);
        when(outbox.enqueue(any())).thenReturn(OptionalLong.of(1L));

        var result = service.resolve(ADMIN, 5, STAFF_ID, "Fixed it");

        assertThat(result.resolutionNote()).isEqualTo("Fixed it");
        verify(communications).recordEscalationResolved(eq(STEP_ID), eq(JOURNEY_ID), eq(OB_CLIENT_ID), eq(STAFF_ID), eq("Fixed it"), any());

        ArgumentCaptor<ObNotification> sent = ArgumentCaptor.forClass(ObNotification.class);
        verify(outbox, times(1)).enqueue(sent.capture());
        assertThat(sent.getValue().eventKey()).isEqualTo("CLIENT_ESCALATION_RESOLVED");
        assertThat(sent.getValue().channel()).isEqualTo(ObChannel.EMAIL);
        assertThat(sent.getValue().recipient().type()).isEqualTo("CLIENT");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static ObClientEscalationRepository.Row row(long id, String comment, Instant raisedAt,
                                                         Long resolvedBy, String resolutionNote, Instant resolvedAt) {
        return new ObClientEscalationRepository.Row(
                id, OB_CLIENT_ID, "Acme", JOURNEY_ID, STEP_ID, "Data migration",
                CONTACT_ID, "Priya SPOC", "Ops Lead", "priya@acme.test", "+91-9000000000",
                true, NOW.minusSeconds(86400), "OPT_IN_FORM", true, true,
                comment, raisedAt,
                resolvedBy, resolvedBy == null ? null : "Staffer", resolvedAt, resolutionNote);
    }
}
