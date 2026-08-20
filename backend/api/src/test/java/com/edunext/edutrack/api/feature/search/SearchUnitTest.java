package com.edunext.edutrack.api.feature.search;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-072 · the three decisions global search makes before it touches a database.
 */
class SearchUnitTest {

    @Nested
    @DisplayName("recognising a pasted ticket code")
    class Codes {

        @Test
        @DisplayName("a bare code, whatever case it arrives in")
        void bareCode() {
            assertThat(TicketCode.from("CRM-26-00347")).contains("CRM-26-00347");
            // Mail clients lower-case links often enough to matter.
            assertThat(TicketCode.from("crm-26-00347")).contains("CRM-26-00347");
            assertThat(TicketCode.from("  CRM-26-00347  ")).contains("CRM-26-00347");
        }

        /**
         * The inputs this feature was built to accept. Gap item 9 is about
         * codes arriving out of email, and D-031 puts brackets round every one
         * of them in a subject line — so refusing the bracketed form would
         * refuse the most common paste there is.
         */
        @Test
        @DisplayName("a code pasted with the noise it came with")
        void pastedWithNoise() {
            assertThat(TicketCode.from("[CRM-26-00347]")).contains("CRM-26-00347");
            assertThat(TicketCode.from("CRM-26-00347.")).contains("CRM-26-00347");
            assertThat(TicketCode.from("(CRM-26-00347)")).contains("CRM-26-00347");
        }

        @Test
        @DisplayName("a whole ticket URL out of an address bar")
        void pastedUrl() {
            assertThat(TicketCode.from("http://localhost:5173/tickets/CRM-26-00347"))
                    .contains("CRM-26-00347");
            // A query string is not part of the code.
            assertThat(TicketCode.from("https://edutrack.example/tickets/CRM-26-00347?tab=effort"))
                    .contains("CRM-26-00347");
        }

        /**
         * Five digits is a minimum width, not a maximum — `projects.ticket_seq`
         * does not reset at year rollover (PLAN.md §3.2, deviation D-8), so a
         * long-lived project reaches six.
         */
        @Test
        @DisplayName("a six-digit sequence, because the counter does not reset")
        void longSequence() {
            assertThat(TicketCode.from("CRM-30-100000")).contains("CRM-30-100000");
        }

        @Test
        @DisplayName("anything that is not code-shaped is not a code")
        void notCodes() {
            assertThat(TicketCode.from("login fails on safari")).isEmpty();
            assertThat(TicketCode.from("CRM-26-0034")).as("four digits").isEmpty();
            assertThat(TicketCode.from("CRM-2026-00347")).as("four-digit year").isEmpty();
            assertThat(TicketCode.from("")).isEmpty();
            assertThat(TicketCode.from(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("building the full-text query")
    class BooleanQuery {

        @Test
        @DisplayName("words get a trailing wildcard, so a half-typed word still matches")
        void prefixMatching() {
            assertThat(SearchService.booleanQuery("login error")).contains("login* error*");
        }

        /**
         * 🔴 The FTS-injection boundary, which is real and separate from SQL
         * injection — `:q` is a bound parameter and cannot be injected.
         *
         * <p>A leading `-` in boolean mode means <em>exclude</em>. Left in
         * place, searching "login -error" quietly returns tickets that do not
         * mention error: a confidently wrong result, with nothing on screen to
         * suggest the query was reinterpreted.
         */
        @Test
        @DisplayName("operator characters are stripped, so a hyphen cannot invert the query")
        void operatorsAreStripped() {
            assertThat(SearchService.booleanQuery("login -error")).contains("login* error*");
            // "C++" is a parse error in boolean mode, not a search.
            assertThat(SearchService.booleanQuery("C++ crash")).contains("crash*");
            assertThat(SearchService.booleanQuery("\"quoted phrase\""))
                    .contains("quoted* phrase*");
        }

        /**
         * Below `innodb_ft_min_token_size` nothing is indexed, so those terms
         * can never match. Dropping them is right; dropping all of them and
         * then searching for the empty string would match every row in the
         * table, which is why this answers empty instead.
         */
        @Test
        @DisplayName("a query of only short words is empty, never a match-everything")
        void shortTermsCannotMatchEverything() {
            assertThat(SearchService.booleanQuery("QA")).isEmpty();
            assertThat(SearchService.booleanQuery("UI QA")).isEmpty();
            assertThat(SearchService.booleanQuery("+-\"")).isEmpty();
            // One usable word among short ones still searches.
            assertThat(SearchService.booleanQuery("QA login")).contains("login*");
        }
    }

    @Nested
    @DisplayName("🔴 the row scope, restated for SQL")
    class Scope {

        @Test
        @DisplayName("Admin is unrestricted, and says so with a predicate rather than nothing")
        void admin() {
            SearchScope scope = SearchScope.of(new CallerIdentity(1L, "ADMIN", List.of()));

            assertThat(scope.sql()).isEqualTo("1 = 1");
            assertThat(scope.bindsProjects()).isFalse();
        }

        @Test
        @DisplayName("PM and Support are bounded by their projects")
        void managers() {
            for (String role : List.of("PM", "SUPPORT")) {
                SearchScope scope = SearchScope.of(new CallerIdentity(2L, role, List.of(4L, 9L)));

                assertThat(scope.sql()).as(role).contains("t.project_id IN (:projectIds)");
                assertThat(scope.projectIds()).as(role).containsExactly(4L, 9L);
            }
        }

        @Test
        @DisplayName("delivery roles are bounded to their own work")
        void deliveryRoles() {
            for (String role : List.of("DEVELOPER", "QA", "DEPLOYMENT")) {
                SearchScope scope = SearchScope.of(new CallerIdentity(8L, role, List.of(4L)));

                assertThat(scope.sql()).as(role).isEqualTo("t.assigned_to = :scopeUserId");
                assertThat(scope.userId()).as(role).isEqualTo(8L);
            }
        }

        /**
         * 🔴 `ScopeResolver`'s central warning, restated because SQL makes it
         * easier to get wrong.
         *
         * <p>A PM belonging to no projects must see nothing. `project_id IN ()`
         * is not valid SQL, and the natural defence — drop the predicate when
         * the list is empty — turns that PM into an Admin with no error, no log
         * line and no failing test.
         */
        @Test
        @DisplayName("a PM with no projects is denied everything, never unrestricted")
        void emptyProjectsDenies() {
            SearchScope scope = SearchScope.of(new CallerIdentity(2L, "PM", List.of()));

            assertThat(scope.sql()).isEqualTo("1 = 0");
            assertThat(scope.sql()).isNotEqualTo("1 = 1");
            assertThat(scope.bindsProjects()).as("nothing to bind, and nothing to widen").isFalse();
        }

        @Test
        @DisplayName("an unrecognised role denies, rather than falling through")
        void unknownRoleDenies() {
            assertThat(SearchScope.of(new CallerIdentity(3L, "AUDITOR", List.of(1L))).sql())
                    .isEqualTo("1 = 0");
        }
    }
}
