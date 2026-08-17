package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-063 · blueprint §2's "Reports section" row, asserted role by role.
 *
 * <p>The case worth the most care is not what a Developer <i>sees</i> — it is
 * what they cannot reach by asking. {@code ?resourceId=} is the whole of it:
 * honoured, it turns "Own perf." into "anyone's perf." with a guessed integer.
 */
@DisplayName("report scope")
class ReportScopeTest {

    private static CallerIdentity caller(String role, long userId, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    @ParameterizedTest(name = "{0} is confined to their own work")
    @ValueSource(strings = {"DEVELOPER", "QA", "DEPLOYMENT"})
    @DisplayName("the three delivery roles get their own work only")
    void deliveryRolesAreOwnWorkOnly(String role) {
        ReportScope scope = ReportScope.of(caller(role, 8L, List.of(1L, 2L)));

        assertThat(scope.ownWorkOnly()).isTrue();
        assertThat(scope.note()).isEqualTo("These reports cover your own work only.");
    }

    /**
     * The leak this class exists to close.
     */
    @ParameterizedTest(name = "{0} cannot read a colleague by asking for them")
    @ValueSource(strings = {"DEVELOPER", "QA", "DEPLOYMENT"})
    @DisplayName("a delivery role's resourceId is overruled, not honoured")
    void resourceIdIsIgnoredForDeliveryRoles(String role) {
        ReportScope scope = ReportScope.of(caller(role, 8L, List.of(1L)));

        // 99 is a colleague. The answer must be 8 whatever was asked for.
        assertThat(scope.resourceSubject(99L)).isEqualTo(8L);
        assertThat(scope.resourceSubject(null)).isEqualTo(8L);
    }

    @Test
    @DisplayName("a PM may legitimately ask about one of their people")
    void pmMayChooseAResource() {
        // §S-05 gives PM and Admin a Resource filter and it has to work — their
        // own project scope still bounds what it can reach.
        ReportScope scope = ReportScope.of(caller("PM", 3L, List.of(1L, 2L)));

        assertThat(scope.ownWorkOnly()).isFalse();
        assertThat(scope.resourceSubject(99L)).isEqualTo(99L);
        assertThat(scope.note()).isEqualTo("These reports cover your projects only.");
    }

    /**
     * The regression this suite missed first time round.
     *
     * <p>Naming no resource is the ordinary case — it is what the viewer sends
     * before anyone touches the filter — and it threw
     * {@code NullPointerException} for every non-delivery role, because
     * {@code ownWorkOnly ? userId : requested} mixes {@code long} with
     * {@code Long} and the conditional operator unboxes both branches.
     *
     * <p>The original tests all passed: the delivery-role cases never evaluate
     * the null branch, and the PM case supplied a resource. {@code ReportsIT}
     * found it on the first run against a real caller.
     */
    @ParameterizedTest(name = "{0} naming no resource gets null, not an exception")
    @ValueSource(strings = {"ADMIN", "PM", "SUPPORT"})
    @DisplayName("no resource filter means no resource filter, for every role that has the choice")
    void noResourceRequestedIsNotAnError(String role) {
        ReportScope scope = ReportScope.of(caller(role, 1L, List.of(1L)));

        assertThat(scope.resourceSubject(null)).isNull();
    }

    @Test
    @DisplayName("Admin is unrestricted, and empty projects mean unrestricted — not deny-all")
    void adminIsUnscoped() {
        // The convention is ScopeResolver's and is matched deliberately.
        // Inverting it here is how "empty" comes to mean allow-all in one file
        // and deny-all in another.
        ReportScope scope = ReportScope.of(caller("ADMIN", 1L, List.of(4L)));

        assertThat(scope.projectIds()).isEmpty();
        assertThat(scope.ownWorkOnly()).isFalse();
        assertThat(scope.note()).as("'you can see everything' is not information").isNull();
    }

    @Test
    @DisplayName("Support is bounded by its projects, like a PM")
    void supportIsProjectScoped() {
        // §2 words Support as "Limited" against PM's tick. That difference is
        // about which reports are worth showing them, not which rows they may
        // see — the row rule is the ticket list's, and it is the same one.
        ReportScope scope = ReportScope.of(caller("SUPPORT", 5L, List.of(2L)));

        assertThat(scope.ownWorkOnly()).isFalse();
        assertThat(scope.projectIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("an out-of-scope project narrows to your own rather than widening")
    void outOfScopeProjectIsNarrowed() {
        ReportScope scope = ReportScope.of(caller("PM", 3L, List.of(1L, 2L)));

        // Asking for project 9, which is not theirs, must not return project 9.
        assertThat(scope.projectFilter(9L)).containsExactly(1L, 2L);
        assertThat(scope.projectFilter(2L)).containsExactly(2L);
        assertThat(scope.projectFilter(null)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Admin asking for one project gets that project, not everything")
    void adminMayNarrow() {
        ReportScope scope = ReportScope.of(caller("ADMIN", 1L, List.of()));

        assertThat(scope.projectFilter(9L)).containsExactly(9L);
        assertThat(scope.projectFilter(null)).isEmpty();
    }
}
