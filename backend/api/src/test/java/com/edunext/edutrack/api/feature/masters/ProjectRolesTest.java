package com.edunext.edutrack.api.feature.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-017 · the seam that turns the next divergence in the project-role
 * vocabulary into a red build.
 *
 * <p>The set is stated in four places and only one of them can be the authority:
 * {@code ck_project_members_role} in {@code V20260811_1520}, the contract's
 * {@code ProjectRoleCode} enum, {@link ProjectRoles#REGEX} (which both DTOs now
 * point at) and {@link ProjectRoles#CODES}. The database and the contract are
 * checked by {@code ProjectTeamIT} and by the generated client; these are the
 * two that live in the same file and could still disagree with each other, which
 * is the divergence nothing else would notice — a seventh code added to the
 * regex and not to the set means one screen accepts a role another refuses, and
 * nothing fails until somebody asks why.
 *
 * <p>B-013 made this argument about §10.3 being written down three times in
 * Java. The fix there and here is the same: put the rule in one place, and put a
 * test on the other side of the seam.
 */
class ProjectRolesTest {

    /**
     * The six of blueprint §7.4 S-10, spelled out.
     *
     * <p>Deliberately a literal rather than a reference to {@link ProjectRoles}:
     * a test that derives its expectation from the thing under test asserts only
     * that the code equals itself. This is the copy that has to be edited by
     * hand when the vocabulary really does change, which is the point at which
     * somebody has to think about the migration.
     */
    private static final Set<String> BLUEPRINT =
            Set.of("PM", "DEVELOPER", "SUPPORT", "QA", "DEPLOYMENT", "VIEWER");

    @Test
    @DisplayName("CODES is the six of blueprint S-10")
    void codesAreTheBlueprintSix() {
        assertThat(ProjectRoles.CODES).isEqualTo(BLUEPRINT);
    }

    @Test
    @DisplayName("REGEX and CODES cannot drift apart")
    void theRegexAndTheSetAgree() {
        assertThat(Set.copyOf(Arrays.asList(ProjectRoles.REGEX.split("\\|"))))
                .as("the @Pattern alternation and the decision set are the same vocabulary")
                .isEqualTo(ProjectRoles.CODES);
    }

    @Test
    @DisplayName("every code matches the regex, and ADMIN does not")
    void adminIsNotAProjectRole() {
        for (String code : ProjectRoles.CODES) {
            assertThat(code.matches(ProjectRoles.REGEX)).as("%s", code).isTrue();
        }
        // The contract's first draft of addProjectMember typed this field as
        // RoleCode, which contains ADMIN — a value ck_project_members_role
        // refuses, so a well-formed request could only ever have arrived as a
        // 500. An Admin already sees every project through ScopeResolver, so the
        // membership would have been a grant that changes nothing.
        assertThat("ADMIN".matches(ProjectRoles.REGEX)).isFalse();
    }

    @Test
    @DisplayName("VIEWER is a project role, and there is no global one")
    void viewerIsAProjectRoleOnly() {
        // The other direction of the same mismatch. Read-only access to one
        // project is a per-project grant; a global viewer would mean read-only
        // access to everything, which is the opposite thing.
        assertThat("VIEWER".matches(ProjectRoles.REGEX)).isTrue();
    }

    @Test
    @DisplayName("blank normalises to null — the column's 'same as their global role'")
    void blankIsNotAValue() {
        assertThat(ProjectRoles.normalise(null)).isNull();
        assertThat(ProjectRoles.normalise("")).isNull();
        assertThat(ProjectRoles.normalise("   ")).isNull();
    }

    @Test
    @DisplayName("normalise upper-cases and trims")
    void normaliseIsCaseInsensitive() {
        assertThat(ProjectRoles.normalise(" developer ")).isEqualTo("DEVELOPER");
    }
}
