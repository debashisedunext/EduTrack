package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-062 · the frontend's list of delivery roles still names the same three
 * {@link DashboardScope} does.
 *
 * <h2>The duplication this exists to police</h2>
 *
 * <p>§S-05's developer dashboard is a <em>smaller screen</em>, not a
 * differently-filtered one: widgets 1–6, 9 and 12, and nothing else. Only the
 * client can decide how many boxes to draw, so
 * {@code useDashboardVariant.ts} holds its own list of the three roles that get
 * the short layout — a second copy of a rule the server already states, and
 * knowingly so.
 *
 * <p><b>The copy is narrow on purpose.</b> It decides how many boxes to draw;
 * the server decides every number inside them, and refuses the widgets a
 * delivery role has no table for regardless of what the client asked. So the
 * worst outcome of the two lists disagreeing is a Developer shown three panels
 * of explanatory sentences, or a QA missing two charts they could have had —
 * not anybody else's figures. That is why this is a test and not a redesign.
 *
 * <p>Without it, adding a fourth delivery role to {@code RolePermissions} and
 * to {@code DashboardScope} would leave that role on the organisation layout
 * silently, and the symptom — half a dashboard replaced by prose — reads as a
 * bug in the widgets rather than as a missing line in a TypeScript array.
 *
 * <p><b>A unit test, no container.</b> Both sides are knowable statically: one
 * is a pure function of a role code, the other is a literal in a source file.
 * {@code DrillDownContractTest} established this shape for the same reason.
 */
class DashboardVariantTest {

    private static final Path VARIANT_HOOK =
            Path.of("../../frontend/src/features/dashboard/useDashboardVariant.ts");

    @Test
    @DisplayName("the client's delivery-role list matches the server's own-work scope")
    void bothSidesNameTheSameRoles() throws Exception {
        assertThat(clientRoles())
                .as("useDashboardVariant.ts decides who gets §S-05's short layout; "
                        + "DashboardScope decides whose rows answer. A role in one and not "
                        + "the other gets a dashboard that half-refuses itself")
                .isEqualTo(serverRoles());
    }

    /**
     * Named separately from the equality above, because the equality would also
     * pass if <em>both</em> lists emptied — a plausible outcome of a refactor
     * that renamed the role constants, and one that would put every delivery
     * role on the full organisation dashboard with nothing failing.
     */
    @Test
    @DisplayName("the three delivery roles are the ones §2 names")
    void theListIsNotAccidentallyEmpty() throws Exception {
        assertThat(serverRoles()).containsExactly("DEPLOYMENT", "DEVELOPER", "QA");
        assertThat(clientRoles()).containsExactly("DEPLOYMENT", "DEVELOPER", "QA");
    }

    /**
     * Asked of {@link DashboardScope} itself rather than read from its source.
     * The rule is a function of the role code and calling it is the only way to
     * be sure the answer under test is the one the dashboard uses.
     */
    private static Set<String> serverRoles() {
        Set<String> ownWork = new TreeSet<>();
        for (String role : RolePermissions.ROLE_CODES) {
            if (DashboardScope.of(new CallerIdentity(1L, role, List.of())).ownWorkOnly()) {
                ownWork.add(role);
            }
        }
        return ownWork;
    }

    /**
     * The role codes inside the hook's {@code OWN_WORK_ONLY} array.
     *
     * <p>Matched as {@code RoleCode.X} entries between that identifier and its
     * closing bracket, so an unrelated {@code RoleCode.ADMIN} elsewhere in the
     * file cannot leak in. A failure to find the array at all reads as an
     * assertion error naming the file rather than as an empty set quietly
     * equalling nothing.
     */
    private static Set<String> clientRoles() throws Exception {
        assertThat(VARIANT_HOOK)
                .as("the dashboard variant hook has moved — this test compares against its source")
                .exists();

        String source = Files.readString(VARIANT_HOOK);
        Matcher array = Pattern.compile("OWN_WORK_ONLY[^=]*=\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(source);
        assertThat(array.find())
                .as("no OWN_WORK_ONLY array in %s", VARIANT_HOOK)
                .isTrue();

        Set<String> roles = new TreeSet<>();
        Matcher entries = Pattern.compile("RoleCode\\.([A-Z_]+)").matcher(array.group(1));
        while (entries.find()) {
            roles.add(entries.group(1));
        }
        return roles;
    }
}
