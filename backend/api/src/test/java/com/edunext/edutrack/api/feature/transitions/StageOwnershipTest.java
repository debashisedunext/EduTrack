package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-043 · the golden rule as a pure predicate, tested in isolation from both
 * of its callers ({@code TransitionService}, {@code TicketDetailService}) so
 * a change here is provably a change to one rule, not two.
 */
class StageOwnershipTest {

    private static final long ASSIGNEE = 55L;

    @Test
    void theAssigneeMayAdvance() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE, "DEVELOPER"), ticket())).isTrue();
    }

    @Test
    void someoneElseHoldingTheSameRoleMayNot() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE + 1, "DEVELOPER"), ticket())).isFalse();
    }

    @Test
    void qaMayNotAdvanceATicketAssignedToDev() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE + 1, "QA"), ticket())).isFalse();
    }

    @Test
    void deploymentMayNotAdvanceATicketAssignedToDev() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE + 1, "DEPLOYMENT"), ticket())).isFalse();
    }

    @Test
    void pmMayAlwaysAdvance() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE + 1, "PM"), ticket())).isTrue();
    }

    @Test
    void adminMayAlwaysAdvance() {
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE + 1, "ADMIN"), ticket())).isTrue();
    }

    @Test
    void anUnassignedTicketAdmitsOnlyPmAndAdmin() {
        Ticket unassigned = ticket();
        unassigned.setAssignedTo(null);

        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE, "DEVELOPER"), unassigned)).isFalse();
        assertThat(StageOwnership.mayAdvance(identity(ASSIGNEE, "PM"), unassigned)).isTrue();
    }

    private static CallerIdentity identity(long userId, String roleCode) {
        return new CallerIdentity(userId, roleCode, List.of());
    }

    private static Ticket ticket() {
        Ticket t = new Ticket();
        t.setAssignedTo(ASSIGNEE);
        return t;
    }
}
