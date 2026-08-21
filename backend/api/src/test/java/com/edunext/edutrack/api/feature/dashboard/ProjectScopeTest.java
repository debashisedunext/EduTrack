package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-077 · {@code DashboardScope.coversProject}, the rule the project dashboard
 * is built on.
 *
 * <h2>Why this is a unit test and not only an IT</h2>
 *
 * <p>The behaviour it protects is an <em>absence</em>: six cards that must not
 * appear and ten charts that must not be drawn. An integration test asserting
 * "the response has no rows" passes just as well when the rule is deleted,
 * because the queries return nothing either way — the scope predicate and the
 * project predicate are ANDed, so an out-of-scope project matched nothing before
 * this rule existed too.
 *
 * <p>That is the whole point of A-077's change and the reason it is easy to get
 * wrong: it is not a leak being closed, it is a <b>false statement</b> being
 * stopped. The difference between the two is invisible in the row count and
 * visible only in whether {@code unavailableReason} is set — so the assertion
 * has to be about the verdict, not about the data.
 */
class ProjectScopeTest {

    /** Admin — empty projectIds means unrestricted, ScopeResolver's convention. */
    private static final DashboardScope ADMIN = new DashboardScope(false, 1L, List.of());

    /** A PM on projects 1 and 2. */
    private static final DashboardScope PM = new DashboardScope(false, 2L, List.of(1L, 2L));

    /** A Developer on project 1 — own work only, and scoped to one project. */
    private static final DashboardScope DEVELOPER = new DashboardScope(true, 8L, List.of(1L));

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 21);

    @Nested
    @DisplayName("coversProject")
    class Covers {

        @Test
        @DisplayName("no project asked for is always answerable")
        void nullIsAlwaysCovered() {
            // The unfiltered dashboard asks for the caller's whole scope, which
            // is by definition what they may see. Refusing here would refuse
            // S-05 itself.
            assertThat(ADMIN.coversProject(null)).isTrue();
            assertThat(PM.coversProject(null)).isTrue();
            assertThat(DEVELOPER.coversProject(null)).isTrue();
        }

        @Test
        @DisplayName("an Admin covers every project, including ones with no rows")
        void adminCoversEverything() {
            // Empty projectIds is unrestricted, not deny-all. Inverting that
            // convention here is the mistake DashboardScope's own javadoc warns
            // about — "how 'empty scope' comes to mean deny-all in one file and
            // allow-all in another" — and it would refuse an Admin their own
            // dashboard.
            assertThat(ADMIN.coversProject(1L)).isTrue();
            assertThat(ADMIN.coversProject(999L)).isTrue();
        }

        @Test
        @DisplayName("a PM covers their own projects and not a colleague's")
        void pmCoversOwnProjectsOnly() {
            assertThat(PM.coversProject(1L)).isTrue();
            assertThat(PM.coversProject(2L)).isTrue();
            assertThat(PM.coversProject(3L)).isFalse();
        }

        @Test
        @DisplayName("a delivery role is judged on membership, not on own-work")
        void deliveryRoleIsJudgedOnMembership() {
            // ownWorkOnly narrows *which tickets* inside a project they see; it
            // is not a second answer to *which projects*. A Developer on
            // project 1 gets project 1's page scoped to their own tickets — that
            // is S-05's existing behaviour and this must not refuse it.
            assertThat(DEVELOPER.coversProject(1L)).isTrue();
            assertThat(DEVELOPER.coversProject(2L)).isFalse();
        }
    }

    @Nested
    @DisplayName("the services actually call it")
    class CallSites {

        /**
         * The assertion the other tests cannot make.
         *
         * <p>{@code coversProject} being correct is worth nothing if nobody
         * consults it, and deleting the call from {@link WidgetService#render}
         * would leave every test above green while the dashboard went back to
         * drawing empty charts for other people's projects. These two pin the
         * call, not the rule.
         *
         * <p>They also pin that no query ran: a refusal that still hit the
         * database would be a refusal built on top of a read, and the next person
         * to move the check would have no signal that they had moved it below
         * the thing it guards.
         */
        @Test
        @DisplayName("a widget for somebody else's project is refused, and reads nothing")
        void widgetRefusesOutOfScopeProject() {
            WidgetRepository widgets = mock(WidgetRepository.class);
            DashboardRepository summaries = mock(DashboardRepository.class);
            when(summaries.computedAt(any(), any())).thenReturn(Optional.empty());

            WidgetService service = new WidgetService(widgets, summaries);

            Optional<WidgetService.Rendered> rendered = service.widget(
                    pmCaller(), "type-donut", 3L, FROM, TO);

            assertThat(rendered).isPresent();
            assertThat(rendered.get().widget().unavailableReason())
                    .isEqualTo(WidgetService.NOT_YOUR_PROJECT);
            assertThat(rendered.get().widget().series()).isEmpty();
            verifyNoInteractions(widgets);
        }

        @Test
        @DisplayName("a widget for a project they are on is not refused")
        void widgetServesOwnProject() {
            // The complement, because a check that refuses everything would pass
            // the test above and break the product.
            WidgetRepository widgets = mock(WidgetRepository.class);
            DashboardRepository summaries = mock(DashboardRepository.class);
            when(summaries.computedAt(any(), any())).thenReturn(Optional.empty());
            when(widgets.openByTaskType(any(), any(), any(), any())).thenReturn(Map.of());
            when(widgets.taskTypeNames()).thenReturn(Map.of());

            WidgetService service = new WidgetService(widgets, summaries);

            Optional<WidgetService.Rendered> rendered = service.widget(
                    pmCaller(), "type-donut", 1L, FROM, TO);

            assertThat(rendered).isPresent();
            assertThat(rendered.get().widget().unavailableReason()).isNull();
        }

        private static CallerIdentity pmCaller() {
            // A PM on projects 1 and 2, matching the PM scope above.
            return new CallerIdentity(2L, RolePermissions.PM, List.of(1L, 2L));
        }
    }

    @Nested
    @DisplayName("what the caller is told")
    class Wording {

        @Test
        @DisplayName("the refusal names membership, and never how much is behind it")
        void refusalRevealsNothingAboutTheProject() {
            // The withheld fact is how much work the project has. A sentence
            // that said "this project's 500 tickets are not shown" would refuse
            // the figures and disclose them in the same breath, and a sentence
            // that said "no data" would be the false statement this whole change
            // exists to prevent.
            assertThat(WidgetService.NOT_YOUR_PROJECT)
                    .contains("member")
                    .doesNotContainIgnoringCase("no data")
                    .doesNotContainIgnoringCase("no tickets")
                    .doesNotContainIgnoringCase("empty")
                    .doesNotContainIgnoringCase("0");
        }

        @Test
        @DisplayName("it is worded as membership, not as a permission denial")
        void notPhrasedAsPermissionDenial() {
            // A-056's distinction, and it decides who the reader goes to. "Not
            // permitted" sends them to an administrator for a permission that
            // would not help; naming membership sends them to whoever adds
            // people to projects, which is the thing that would.
            assertThat(WidgetService.NOT_YOUR_PROJECT)
                    .doesNotContainIgnoringCase("forbidden")
                    .doesNotContainIgnoringCase("not permitted")
                    .doesNotContainIgnoringCase("denied")
                    .doesNotContainIgnoringCase("unauthorised");
        }
    }
}
