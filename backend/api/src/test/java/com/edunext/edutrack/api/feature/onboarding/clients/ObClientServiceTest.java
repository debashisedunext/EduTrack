package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.pan.PanService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read assembly, without a database — chiefly {@code currentStep}, the
 * field OB-03's caption and OB-02's RAG columns read.
 *
 * <p>The null cases the contract lists — primary journey gate-locked, held
 * behind a sibling, or finished — are decided by
 * {@code ObClientReadRepository.currentStepsOf}'s {@code WHERE}, which a unit
 * test cannot execute. What <em>is</em> decidable here, and asserted below, is
 * the whole of the Java half: a repository row maps onto the DTO field for
 * field, and the absence of a row maps onto null rather than onto a zeroed
 * object.
 */
class ObClientServiceTest {

    private static final long CLIENT = 42L;
    private static final long OTHER_CLIENT = 43L;

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, 7L);
    private static final Instant DUE_AT = Instant.parse("2026-09-15T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-01T09:00:00Z");

    private ObClientReadRepository reads;
    private ObClientService service;

    @BeforeEach
    void setUp() {
        reads = mock(ObClientReadRepository.class);
        service = new ObClientService(reads, mock(PanService.class),
                mock(ObJourneyStepRepository.class), mock(ObJourneyStepRagService.class));
    }

    // ── the list ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a client whose primary journey is running carries its current step")
    void aRunningPrimaryJourneyCarriesItsCurrentStep() {
        when(reads.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(listRow(CLIENT)));
        when(reads.currentStepsOf(List.of(CLIENT)))
                .thenReturn(List.of(new ObClientReadRepository.CurrentStepRow(
                        CLIENT, 5L, "ERP", "ERP Suite", "Data migration", DUE_AT, 4, 8)));

        ObClientDtos.ObClientSummary row = service
                .list(ADMIN, null, null, null, null, null, null, null, null, null)
                .data().getFirst();

        assertThat(row.startedAt()).isEqualTo(STARTED_AT);

        ObClientDtos.ObClientCurrentStep step = row.currentStep();
        assertThat(step).isNotNull();
        assertThat(step.name()).isEqualTo("Data migration");
        assertThat(step.dueAt()).isEqualTo(DUE_AT);
        assertThat(step.stepIndex()).isEqualTo(4);
        assertThat(step.stepTotal()).isEqualTo(8);
        assertThat(step.product())
                .isEqualTo(new ObClientDtos.ObProductRef(5L, "ERP", "ERP Suite"));
    }

    /**
     * The repository answers no row for a gate-locked, held or finished primary
     * journey — and no row must become null, never a zeroed
     * {@code ObClientCurrentStep}, which would render as "step 0/0".
     */
    @Test
    @DisplayName("a client with no running primary journey — locked, held or finished — is null")
    void aClientWithNoRunningPrimaryJourneyIsNull() {
        when(reads.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(listRow(CLIENT), listRow(OTHER_CLIENT)));
        // Only OTHER_CLIENT has anything running; CLIENT's primary journey is
        // behind its gate, behind a sibling, or done — SQL's call, no row here.
        when(reads.currentStepsOf(List.of(CLIENT, OTHER_CLIENT)))
                .thenReturn(List.of(new ObClientReadRepository.CurrentStepRow(
                        OTHER_CLIENT, 5L, "ERP", "ERP Suite", "Kickoff", DUE_AT, 1, 8)));

        List<ObClientDtos.ObClientSummary> page = service
                .list(ADMIN, null, null, null, null, null, null, null, null, null)
                .data();

        assertThat(page.getFirst().currentStep()).isNull();
        assertThat(page.getLast().currentStep()).isNotNull();
    }

    // ── the detail ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the detail restates the list's currentStep, through the same batch read")
    void theDetailRestatesCurrentStep() {
        when(reads.findDetail(any(), anyLong())).thenReturn(Optional.of(detailRow(CLIENT)));
        when(reads.currentStepsOf(List.of(CLIENT)))
                .thenReturn(List.of(new ObClientReadRepository.CurrentStepRow(
                        CLIENT, 5L, "ERP", "ERP Suite", "Data migration", DUE_AT, 4, 8)));

        Optional<ObClientDtos.ObClientDetail> detail = service.findDetail(ADMIN, CLIENT);

        assertThat(detail).isPresent();
        assertThat(detail.get().currentStep()).isEqualTo(new ObClientDtos.ObClientCurrentStep(
                new ObClientDtos.ObProductRef(5L, "ERP", "ERP Suite"), "Data migration", DUE_AT, 4, 8));
        // The singleton list, not a second query shape that can drift.
        verify(reads).currentStepsOf(eq(List.of(CLIENT)));
    }

    @Test
    @DisplayName("a detail whose primary journey has nothing running is null too")
    void aDetailWithNothingRunningIsNull() {
        when(reads.findDetail(any(), anyLong())).thenReturn(Optional.of(detailRow(CLIENT)));
        when(reads.currentStepsOf(List.of(CLIENT))).thenReturn(List.of());

        Optional<ObClientDtos.ObClientDetail> detail = service.findDetail(ADMIN, CLIENT);

        assertThat(detail).isPresent();
        assertThat(detail.get().currentStep()).isNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObClientReadRepository.ListRow listRow(long id) {
        return new ObClientReadRepository.ListRow(
                id, "Acme", LocalDate.of(2026, 9, 7), "ONBOARDING", null,
                null, null, null, "OPEN", 2, 0, false, STARTED_AT);
    }

    private static ObClientReadRepository.DetailRow detailRow(long id) {
        return new ObClientReadRepository.DetailRow(
                listRow(id), null, null, null, null, null, null, null, null, null);
    }
}
