package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-071 · the read side's decisions, none of which are about SQL.
 */
class AuditServiceTest {

    private static final Instant WHEN = Instant.parse("2026-08-18T09:15:00Z");

    private AuditQueryRepository repository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditQueryRepository.class);
        service = new AuditService(repository);
    }

    @Nested
    @DisplayName("the actor")
    class Actors {

        @Test
        void isNamedWhenTheUserStillExists() {
            given(row(1L, 7L, "Ravi Kumar", "ADMIN"));

            AuditDtos.Entry entry = firstEntry();

            assertThat(entry.actor()).isNotNull();
            assertThat(entry.actor().displayName()).isEqualTo("Ravi Kumar");
            assertThat(entry.actor().role()).isEqualTo("ADMIN");
        }

        /**
         * Null actor is SYSTEM, and it is left null rather than filled in with a
         * synthetic {@code {id: 0, displayName: "System"}} — the client renders
         * the word, and no row is made to claim a user id the database does not
         * have.
         */
        @Test
        void isAbsentForASystemAction() {
            given(row(1L, null, null, null));

            assertThat(firstEntry().actor()).isNull();
        }

        /**
         * The case the {@code LEFT JOIN} exists for. An audit row outlives its
         * actor, and returning a null actor here would make a removed account
         * indistinguishable from a scanner — which is the difference between
         * "somebody did this" and "nobody did".
         */
        @Test
        @DisplayName("survives the user being deleted, and says so")
        void isReportedAsDeletedWhenTheUserIsGone() {
            given(row(1L, 42L, null, null));

            AuditDtos.Entry entry = firstEntry();
            assertThat(entry.actor()).isNotNull();
            assertThat(entry.actor().id()).isEqualTo(42L);
            assertThat(entry.actor().displayName()).contains("42");
        }
    }

    @Nested
    @DisplayName("the subject")
    class Subjects {

        @Test
        void isTheNumericIdWhereThereIsOne() {
            given(rowWithSubject(4L, null));

            assertThat(firstEntry().entityId()).isEqualTo("4");
        }

        @Test
        void isTheReferenceWhereThereIsNot() {
            given(rowWithSubject(null, "CRM-26-00347"));

            assertThat(firstEntry().entityId()).isEqualTo("CRM-26-00347");
        }

        @Test
        void isAbsentWhereTheRouteNamedNoRecord() {
            given(rowWithSubject(null, null));

            assertThat(firstEntry().entityId()).isNull();
        }
    }

    @Nested
    @DisplayName("the detail")
    class Details {

        /**
         * Absent, not {@code {}}. An empty object renders as a detail panel that
         * appears to have loaded and found the change to be nothing; absent
         * renders as "nothing was recorded", which is what actually happened for
         * every row the interceptor wrote.
         */
        @Test
        void isNullWhenNeitherValueWasRecorded() {
            given(row(1L, 7L, "Ravi Kumar", "ADMIN"));

            assertThat(firstEntry().detail()).isNull();
        }

        @Test
        void carriesWhicheverHalfExists() {
            given(new AuditQueryRepository.Row(1L, 7L, "Ravi Kumar", "ADMIN",
                    "LOGIN_FAILED", "users", null, null,
                    null, "jsmith", "203.0.113.9", "curl/8", WHEN));

            assertThat(firstEntry().detail()).containsExactly(java.util.Map.entry("new", "jsmith"));
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        /**
         * The fetch-one-extra boundary lives in {@code CursorPage.of} and this
         * asserts the service asks for the extra row rather than reimplementing
         * the decision. Getting this wrong does not throw — it silently drops the
         * last row of every page, which on an audit log is the failure that looks
         * like nothing happened.
         */
        @Test
        void asksForOneMoreRowThanItReturns() {
            given(rows(51));

            service.page(noFilters(), null, 50);

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(repository).page(any(), any(), any(), any(), any(), any(), any(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(PageLimit.fetchSize(50));
        }

        @Test
        void reportsMoreWhenTheExtraRowCameBack() {
            given(rows(51));

            CursorPage<AuditDtos.Entry> page = service.page(noFilters(), null, 50);

            assertThat(page.data()).hasSize(50);
            assertThat(page.meta().hasMore()).isTrue();
            assertThat(page.meta().nextCursor()).isNotBlank();
        }

        @Test
        void reportsTheLastPageWhenItDidNot() {
            given(rows(3));

            CursorPage<AuditDtos.Entry> page = service.page(noFilters(), null, 50);

            assertThat(page.data()).hasSize(3);
            assertThat(page.meta().hasMore()).isFalse();
        }

        @Test
        @DisplayName("a cursor is decoded into both halves of the keyset")
        void bothHalvesOfTheCursorReachTheQuery() {
            given(rows(1));

            service.page(noFilters(), new Cursor(WHEN.toString(), 900L).encode(), 50);

            verify(repository).page(any(), any(), any(), any(), any(), eq(WHEN), eq(900L), anyInt());
        }

        /**
         * A cursor is ours and opaque, so a broken one is a truncated
         * copy-paste, not an attack to report. Restarting from the top is
         * visible and recoverable; a 400 in the middle of paging is neither.
         */
        @Test
        void anUnreadableCursorRestartsRatherThanFailing() {
            given(rows(1));

            service.page(noFilters(), "not-a-cursor", 50);

            verify(repository).page(any(), any(), any(), any(), any(), eq(null), eq(null), anyInt());
        }

        @Test
        void anOversizedLimitIsClampedRatherThanHonoured() {
            given(rows(1));

            service.page(noFilters(), null, 5_000);

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(repository).page(any(), any(), any(), any(), any(), any(), any(), limit.capture());
            assertThat(limit.getValue()).isEqualTo(PageLimit.fetchSize(PageLimit.MAX));
        }
    }

    @Nested
    @DisplayName("filters")
    class Filters {

        /**
         * {@code ?action=} from an emptied select means "no filter". Passed
         * through as the empty string it would match nothing and read on screen
         * as an audit log with no entries — the most alarming possible way to
         * render a cleared dropdown.
         */
        @Test
        void blankValuesAreNoFilterRatherThanAnImpossibleOne() {
            AuditService.Filters filters = AuditService.Filters.of(null, "  ", "", null, null);

            assertThat(filters.action()).isNull();
            assertThat(filters.entityType()).isNull();
        }

        @Test
        void anIsoInstantIsRead() {
            AuditService.Filters filters =
                    AuditService.Filters.of(null, null, null, "2026-08-18T09:15:00Z", null);

            assertThat(filters.from()).isEqualTo(WHEN);
        }

        /** What a bare {@code <input type="date">} sends. */
        @Test
        void aBareDateIsReadAsMidnightUtc() {
            AuditService.Filters filters =
                    AuditService.Filters.of(null, null, null, "2026-08-18", null);

            assertThat(filters.from()).isEqualTo(Instant.parse("2026-08-18T00:00:00Z"));
        }

        /**
         * Half-typed dates arrive on every keystroke of a date field. A 400
         * mid-typing is worse than a list that has not narrowed yet.
         */
        @Test
        void anUnparseableDateIsIgnoredRatherThanRejected() {
            AuditService.Filters filters =
                    AuditService.Filters.of(null, null, null, "2026-08-3", null);

            assertThat(filters.from()).isNull();
        }
    }

    @Nested
    @DisplayName("export")
    class Export {

        @Test
        void takesTheCapAndNoCursor() {
            given(rows(3));

            service.forExport(noFilters());

            verify(repository).page(any(), any(), any(), any(), any(),
                    eq(null), eq(null), eq(AuditService.EXPORT_MAX));
        }

        /**
         * The cap has to be stated on the file, or a truncated extract is
         * indistinguishable from a complete one — and somebody will quote it as
         * complete.
         */
        @Test
        void aTruncatedExportSaysSoOnTheSheet() {
            String described = AuditExportService.describe(noFilters(), AuditService.EXPORT_MAX);

            assertThat(described).containsIgnoringCase("truncated");
        }

        @Test
        void anUntruncatedExportDoesNot() {
            assertThat(AuditExportService.describe(noFilters(), 12))
                    .doesNotContainIgnoringCase("truncated")
                    .contains("no filters");
        }

        @Test
        void theAppliedFiltersAreNamedOnTheSheet() {
            String described = AuditExportService.describe(
                    AuditService.Filters.of(7L, "LOGIN_FAILED", "users", "2026-08-18", null), 4);

            assertThat(described).contains("actor 7").contains("LOGIN_FAILED").contains("users");
        }


        /**
         * A null actor means two different things and the sheet must not
         * conflate them. A failed sign-in written as "System" would put a
         * spreadsheet into circulation saying the mail engine tried to log in
         * as somebody — and unlike the screen, a file gets forwarded.
         */
        @Test
        void aFailedSignInIsNotAttributedToTheSystem() {
            AuditDtos.Entry attempt = new AuditDtos.Entry(1L, null, "LOGIN_FAILED", "users",
                    null, "198.51.100.4", "curl/8",
                    AuditDtos.detailOf(null, "jsmith"), WHEN);

            assertThat(AuditExportService.whoFor(attempt)).isEqualTo("jsmith (not signed in)");
        }

        @Test
        void butAScannerStillIs() {
            AuditDtos.Entry scanner = new AuditDtos.Entry(1L, null, "CHAIN_VERIFIED", "tickets",
                    null, null, null, null, WHEN);

            assertThat(AuditExportService.whoFor(scanner)).isEqualTo("System");
        }

        /**
         * ACCESS_DENIED is a refusal and always has an actor — {@code
         * @PreAuthorize} only refuses somebody who authenticated — so it must
         * not take the "not signed in" branch even though it is a refusal.
         */
        @Test
        void andARefusalWithAnActorIsNamed() {
            AuditDtos.Entry denied = new AuditDtos.Entry(1L,
                    new AuditDtos.UserRef(3L, "Neha Sharma", "DEVELOPER"),
                    "ACCESS_DENIED", "audit_logs", null, null, null, null, WHEN);

            assertThat(AuditExportService.whoFor(denied)).isEqualTo("Neha Sharma");
        }

        /** PDF is offered on reports and deliberately not here. */
        @Test
        void pdfIsNotOffered() {
            assertThat(AuditExportService.formatOf("pdf")).isEmpty();
            assertThat(AuditExportService.formatOf("xlsx")).isPresent();
            assertThat(AuditExportService.formatOf("csv")).isPresent();
        }
    }

    // --- helpers -----------------------------------------------------------

    private static AuditService.Filters noFilters() {
        return AuditService.Filters.of(null, null, null, null, null);
    }

    private void given(AuditQueryRepository.Row... rows) {
        given(List.of(rows));
    }

    private void given(List<AuditQueryRepository.Row> rows) {
        when(repository.page(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(rows);
    }

    private AuditDtos.Entry firstEntry() {
        return service.page(noFilters(), null, 50).data().get(0);
    }

    private static List<AuditQueryRepository.Row> rows(int count) {
        List<AuditQueryRepository.Row> rows = new ArrayList<>();
        IntStream.rangeClosed(1, count)
                .forEach(i -> rows.add(row(i, 7L, "Ravi Kumar", "ADMIN")));
        return rows;
    }

    private static AuditQueryRepository.Row row(long id, Long actorId, String name, String role) {
        return new AuditQueryRepository.Row(id, actorId, name, role,
                "TICKETS_CREATED", "tickets", null, "CRM-26-00347",
                null, null, "203.0.113.9", "Mozilla/5.0", WHEN);
    }

    private static AuditQueryRepository.Row rowWithSubject(Long entityId, String entityRef) {
        return new AuditQueryRepository.Row(1L, 7L, "Ravi Kumar", "ADMIN",
                "ROLES_UPDATED", "masters", entityId, entityRef,
                null, null, "203.0.113.9", "Mozilla/5.0", WHEN);
    }
}
