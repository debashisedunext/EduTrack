package com.edunext.edutrack.api.feature.onboarding.communications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-112 · paging, scope short-circuiting, the author shapes and the one
 * default that cannot be got wrong — without a database.
 *
 * <p>{@code ObCommunicationTimelineIT} covers the SQL, the two orderings and
 * the append itself against real MySQL.
 */
class ObCommunicationServiceTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-05T09:00:00Z");
    private static final long ME = 7;
    private static final long STEP = 42;
    private static final long CLIENT = 3;

    private final ObCommunicationRepository repository = mock(ObCommunicationRepository.class);
    private final ObCommunicationService service = new ObCommunicationService(repository);

    private static final ObCommunicationScope ADMIN = new ObCommunicationScope("OB_ADMIN", ME);
    private static final ObCommunicationScope NO_STANDING = new ObCommunicationScope("", ME);

    // ── the append-only guarantee, stated as a test ───────────────────────

    /**
     * CLAUDE.md layer 1. A method named {@code update*} or {@code delete*} on
     * either the service or the repository is the design having gone wrong,
     * and it is worth failing a build over rather than a review catching it.
     */
    @Test
    @DisplayName("neither the service nor the repository exposes a way to change an entry")
    void thereIsNoMutationPath() {
        Stream.of(ObCommunicationService.class, ObCommunicationRepository.class).forEach(type -> {
            List<String> mutators = Stream.of(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("update") || name.startsWith("delete")
                            || name.startsWith("edit") || name.startsWith("remove"))
                    .toList();
            assertThat(mutators)
                    .as("""
                            ob_step_communications is append-only. A correction is a new \
                            compensating row (is_correction, corrects_entry_id), not an edit — \
                            CLAUDE.md's append-only rule, and this class is layer 1 of the four \
                            that hold it. If a task seems to need mutation, the design is wrong.""")
                    .isEmpty();
        });
    }

    // ── scope ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a caller with no onboarding standing sees an empty step timeline, never the database")
    void deniedScopeNeverQueriesTheStepTimeline() {
        var page = service.listForStep(NO_STANDING, STEP, null, 10);

        assertThat(page.data()).isEmpty();
        assertThat(page.meta().hasMore()).isFalse();
        verify(repository, never()).listForStep(any(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("a caller with no onboarding standing sees an empty stitched view, never the database")
    void deniedScopeNeverQueriesTheStitchedView() {
        var page = service.listForClient(NO_STANDING, CLIENT, null, null, null, 10);

        assertThat(page.data()).isEmpty();
        verify(repository, never()).listForClient(any(), anyLong(), any(), anyBoolean(), any(), anyInt());
    }

    /**
     * A 404, not a 403 and not a silent no-op. The write has no honest empty
     * answer the way a list does — see {@code CommunicationStepNotFoundException}.
     */
    @Test
    @DisplayName("a caller with no onboarding standing cannot append, and is told 'no such service'")
    void deniedScopeCannotAppend() {
        assertThatThrownBy(() -> service.record(NO_STANDING, STEP, ME, request(null)))
                .isInstanceOf(CommunicationStepNotFoundException.class)
                .hasMessageContaining("no service " + STEP);
        verify(repository, never()).insert(any(), any(), any(), anyLong(), anyBoolean(), any());
    }

    @Test
    @DisplayName("an out-of-scope or unknown step answers the same 404, and nothing is written")
    void anUnresolvableStepIsNeverWrittenTo() {
        when(repository.stepContext(ADMIN, STEP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(ADMIN, STEP, ME, request(null)))
                .isInstanceOf(CommunicationStepNotFoundException.class);
        verify(repository, never()).insert(any(), any(), any(), anyLong(), anyBoolean(), any());
    }

    // ── paging ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the extra probe row is asked for, and never sent")
    void theProbeRowIsStripped() {
        when(repository.listForStep(eq(ADMIN), eq(STEP), any(), eq(11)))
                .thenReturn(rows(11));

        var page = service.listForStep(ADMIN, STEP, null, 10);

        assertThat(page.data()).hasSize(10);
        assertThat(page.meta().hasMore()).isTrue();
        assertThat(page.meta().nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("the stitched view pages on occurredAt, which is when it happened, not when it was typed")
    void theStitchedCursorNamesTheConversation() {
        when(repository.listForClient(eq(ADMIN), eq(CLIENT), any(), eq(false), any(), eq(11)))
                .thenReturn(rows(11));

        var page = service.listForClient(ADMIN, CLIENT, null, null, null, 10);

        assertThat(page.data()).hasSize(10);
        // `Cursor.encode` is `id|sortKey`, and the sort key is occurredAt —
        // when the conversation happened, never when the row was typed.
        assertThat(new String(java.util.Base64.getUrlDecoder().decode(page.meta().nextCursor())))
                .isEqualTo("10|" + OCCURRED);
    }

    @Test
    @DisplayName("clientVisibleOnly is absent-means-false, never absent-means-unfiltered-then-null")
    void theVisibilityFilterDefaultsToShowingEverything() {
        when(repository.listForClient(eq(ADMIN), eq(CLIENT), any(), eq(false), any(), anyInt()))
                .thenReturn(List.of());

        service.listForClient(ADMIN, CLIENT, null, null, null, 10);

        verify(repository).listForClient(ADMIN, CLIENT, null, false, null, 11);
    }

    // ── the append ────────────────────────────────────────────────────────

    /**
     * The DDL's own unrecoverable failure: an internal note that reaches the
     * portal because a default went the other way cannot be un-read.
     */
    @Test
    @DisplayName("an entry with no isClientVisible is written internal, not client-visible")
    void absentVisibilityIsInternal() {
        givenAWritableStep();

        service.record(ADMIN, STEP, ME, request(null));

        verify(repository).insert(any(), eq("CALL"), eq("Spoke to the SPOC"), eq(ME), eq(false), eq(OCCURRED));
    }

    @Test
    @DisplayName("an explicit true is honoured — the default is a default, not a ceiling")
    void anExplicitPublishIsHonoured() {
        givenAWritableStep();

        service.record(ADMIN, STEP, ME, request(true));

        verify(repository).insert(any(), any(), any(), anyLong(), eq(true), any());
    }

    /**
     * The journey and the client come from the step lookup, never from the
     * caller — so no request can file a communication against another client's
     * journey by naming it.
     */
    @Test
    @DisplayName("the journey and client are taken from the step, not from the request")
    void theContextIsResolvedServerSide() {
        givenAWritableStep();

        service.record(ADMIN, STEP, ME, request(null));

        verify(repository).insert(
                eq(new ObCommunicationRepository.StepContext(STEP, 9, CLIENT)),
                any(), any(), anyLong(), anyBoolean(), any());
    }

    // ── the three author shapes ───────────────────────────────────────────

    @Test
    @DisplayName("a staff entry renders the user's name and carries the staff ref")
    void staffAuthor() {
        var dto = ObCommunicationDtos.ObStepCommunication.of(
                row(1, "STAFF", 5L, "Ravi Kumar", null));

        assertThat(dto.authorName()).isEqualTo("Ravi Kumar");
        assertThat(dto.recordedBy()).isEqualTo(new ObCommunicationDtos.UserRef(5, "Ravi Kumar"));
    }

    @Test
    @DisplayName("a portal comment renders the contact's name and carries no staff ref")
    void clientAuthor() {
        var dto = ObCommunicationDtos.ObStepCommunication.of(
                row(2, "CLIENT", null, null, "Sanjay Bose"));

        assertThat(dto.authorName()).isEqualTo("Sanjay Bose");
        assertThat(dto.recordedBy()).isNull();
    }

    /**
     * Neither author column is set, which is {@code ck_ob_comms_author}'s
     * third valid shape and the reason the check is not "one of two is not
     * null". The contract makes {@code authorName} required, so it falls back
     * to a word rather than to null.
     */
    @Test
    @DisplayName("a system entry still has a name to render")
    void systemAuthor() {
        var dto = ObCommunicationDtos.ObStepCommunication.of(
                row(3, "SYSTEM", null, null, null));

        assertThat(dto.authorName()).isEqualTo("System");
        assertThat(dto.recordedBy()).isNull();
    }

    @Test
    @DisplayName("the stitched row carries where the entry came from, which is the whole point of it")
    void theStitchedRowNamesItsService() {
        var dto = ObCommunicationDtos.ObClientCommunication.of(
                row(4, "STAFF", 5L, "Ravi Kumar", null));

        assertThat(dto.productName()).isEqualTo("EduTrack ERP");
        assertThat(dto.stepName()).isEqualTo("Data Migration");
        assertThat(dto.stepSequence()).isEqualTo(3);
        assertThat(dto.journeyId()).isEqualTo(9);
        assertThat(dto.obClientId()).isEqualTo(CLIENT);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private void givenAWritableStep() {
        when(repository.stepContext(ADMIN, STEP))
                .thenReturn(Optional.of(new ObCommunicationRepository.StepContext(STEP, 9, CLIENT)));
        when(repository.insert(any(), any(), any(), anyLong(), anyBoolean(), any())).thenReturn(101L);
        when(repository.findById(ADMIN, 101L))
                .thenReturn(Optional.of(row(101, "STAFF", ME, "Ravi Kumar", null)));
    }

    private static ObCommunicationDtos.ObStepCommunicationCreateRequest request(Boolean visible) {
        return new ObCommunicationDtos.ObStepCommunicationCreateRequest(
                "CALL", OCCURRED, "Spoke to the SPOC", visible);
    }

    private static List<ObCommunicationRepository.Row> rows(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> row(i, "STAFF", 5L, "Ravi Kumar", null))
                .toList();
    }

    private static ObCommunicationRepository.Row row(
            long id, String authorType, Long authorUserId, String authorUserName, String contactName) {
        return new ObCommunicationRepository.Row(
                id, STEP, 9, CLIENT,
                "Data Migration", 3, "EduTrack ERP",
                "CALL", "Spoke to the SPOC",
                authorType, authorUserId, authorUserName,
                contactName == null ? null : 11L, contactName,
                false, OCCURRED, false, null, OCCURRED);
    }
}
