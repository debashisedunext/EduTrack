package com.edunext.edutrack.api.arch;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.security.PasswordHashing;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-037 · the test that makes the other rules mean something.
 *
 * <p>Every rule in this package is of the form "no class does X". Checked
 * against an empty import, all of them pass — instantly, quietly, and in the
 * build log indistinguishably from a codebase with no violations. A suite whose
 * failure mode is *silent success* needs its own canary, so this asserts the
 * import found real classes from each of the three modules before any rule is
 * allowed to claim anything.
 *
 * <p>The three canaries are named types rather than a count, because a count
 * drifts and a threshold gets lowered. {@link PasswordHashing} is the whole of
 * {@code common} today, so it is also the check that a single-class module has
 * not quietly dropped off the classpath.
 */
class ArchImportSanityTest {

    @Test
    void theImportFoundClassesInEveryModuleTheRulesCover() {
        assertThat(ProductionClasses.get())
                .as("an empty import passes every rule in this package without checking anything")
                .isNotEmpty();

        assertThat(ProductionClasses.get().stream().map(c -> c.getName()))
                .as("api, domain and common must all be on the classpath being analysed")
                .contains(ScopedTickets.class.getName(),
                        TicketHistoryRepository.class.getName(),
                        PasswordHashing.class.getName());
    }

    /**
     * Test classes must stay out. They break the rules on purpose — a test
     * fixture reaching straight for {@code TicketRepository} to seed rows is
     * correct, and if it were analysed the honest fix would be to weaken the
     * rule for production code too.
     */
    @Test
    void testClassesAreNotAnalysed() {
        assertThat(ProductionClasses.get().stream().map(c -> c.getName()))
                .doesNotContain(ArchImportSanityTest.class.getName());
    }
}
