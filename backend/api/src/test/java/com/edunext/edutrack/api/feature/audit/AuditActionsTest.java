package com.edunext.edutrack.api.feature.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-071 · the derivation, which is the whole vocabulary.
 *
 * <p>Worth testing directly rather than only through the interceptor, because
 * every term in {@code audit_logs} comes out of these three methods and a
 * mistake in one of them is not a crash — it is a table full of rows filed
 * under a name nobody thinks to filter for.
 */
class AuditActionsTest {

    @Nested
    @DisplayName("the action names the leaf resource and the verb")
    class Actions {

        @Test
        void createReadsAsCreated() {
            assertThat(AuditActions.actionFor("POST", "/api/v1/tickets"))
                    .contains("TICKETS_CREATED");
        }

        /**
         * The case A-071's line calls out first: "every permission change". A
         * route whose leaf is {@code permissions} must be findable by searching
         * for permissions, not buried under a term naming roles or masters.
         */
        @Test
        void aPermissionChangeSaysPermissions() {
            assertThat(AuditActions.actionFor("PATCH", "/api/v1/masters/roles/{roleId}/permissions"))
                    .contains("PERMISSIONS_UPDATED");
        }

        @Test
        @DisplayName("PUT and PATCH are the same event — one changed a record")
        void bothUpdateVerbsCollapse() {
            assertThat(AuditActions.actionFor("PUT", "/api/v1/masters/holidays/{holidayId}"))
                    .isEqualTo(AuditActions.actionFor("PATCH", "/api/v1/masters/holidays/{holidayId}"));
        }

        @Test
        void hyphensBecomeUnderscoresSoTheTermIsOneWord() {
            assertThat(AuditActions.actionFor("POST", "/api/v1/masters/task-types"))
                    .contains("TASK_TYPES_CREATED");
        }

        /**
         * A GET has no term. The interceptor still records a refused read, but
         * as {@code ACCESS_DENIED} — deriving {@code TICKETS_READ} here would
         * put a verb in the vocabulary that nothing ever writes on success.
         */
        @Test
        void aReadHasNoTerm() {
            assertThat(AuditActions.actionFor("GET", "/api/v1/tickets")).isEmpty();
        }

        @Test
        void aPathOfNothingButPlaceholdersHasNoTerm() {
            assertThat(AuditActions.actionFor("POST", "/api/v1/{anything}")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the module is the first static segment, not the last")
    class Modules {

        /**
         * The distinction the whole filter rests on. Reading the module off the
         * leaf would file this row under {@code comments}, and "everything that
         * happened to this ticket" would then have to know every leaf resource a
         * ticket can carry.
         */
        @Test
        void aNestedResourceBelongsToItsParentModule() {
            assertThat(AuditActions.moduleFor("/api/v1/tickets/{ticketId}/comments"))
                    .contains("tickets");
        }

        @Test
        void everyMasterIsOneModule() {
            assertThat(AuditActions.moduleFor("/api/v1/masters/roles/{roleId}"))
                    .contains("masters");
        }

        @Test
        void hyphensFoldSoTheValueLooksLikeATableName() {
            assertThat(AuditActions.moduleFor("/api/v1/import-batches/{batchId}"))
                    .contains("import_batches");
        }

        @Test
        void aWildcardIsAMatcherAndNotAResource() {
            assertThat(AuditActions.moduleFor("/api/v1/webhooks/**")).contains("webhooks");
        }
    }

    @Nested
    @DisplayName("the subject is the first path variable")
    class Subjects {

        /**
         * First rather than last. The last variable here is the comment, which
         * cannot be looked up once the DELETE has run and which no screen can
         * render; the first is the ticket, which is the record a reader opens.
         */
        @Test
        void theParentIdWinsOverTheChildId() {
            assertThat(AuditActions.subjectVariable(
                    "/api/v1/tickets/{ticketId}/comments/{commentId}"))
                    .contains("ticketId");
        }

        @Test
        void aCollectionRouteHasNoSubject() {
            assertThat(AuditActions.subjectVariable("/api/v1/tickets")).isEmpty();
        }

        @Test
        @DisplayName("a regex-constrained variable yields the name, not the regex")
        void theRegexSuffixIsStripped() {
            assertThat(AuditActions.subjectVariable("/api/v1/projects/{projectId:[0-9]+}"))
                    .contains("projectId");
        }
    }

    @Nested
    class Verbs {

        @Test
        void theFourWritingVerbsAreMutating() {
            assertThat(AuditActions.isMutating("POST")).isTrue();
            assertThat(AuditActions.isMutating("PUT")).isTrue();
            assertThat(AuditActions.isMutating("PATCH")).isTrue();
            assertThat(AuditActions.isMutating("DELETE")).isTrue();
        }

        @Test
        void readsAndPreflightAreNot() {
            assertThat(AuditActions.isMutating("GET")).isFalse();
            assertThat(AuditActions.isMutating("HEAD")).isFalse();
            assertThat(AuditActions.isMutating("OPTIONS")).isFalse();
            assertThat(AuditActions.isMutating(null)).isFalse();
        }
    }
}
