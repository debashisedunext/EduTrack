package com.edunext.edutrack.api.arch;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PostAuthorize;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-037 · the rule {@link ScopedTickets} has been waiting for.
 *
 * <p>Its javadoc, {@code TicketRepository}'s javadoc and {@code api/security/README.md}
 * all say the same thing in words — do not autowire {@code TicketRepository},
 * go through the door — and until now all three were convention. A-034 recorded
 * why that is not enough, and the sentence is worth repeating because it is the
 * whole argument for this file: <em>"{@code ScopeResolver} can produce a
 * perfect specification and the system still leaks the first time somebody
 * writes {@code ticketRepository.findAll()} in a service, because nothing about
 * that line looks wrong."</em>
 *
 * <p>It does not look wrong in review either. That is what makes it a rule for
 * the build rather than a note for a reviewer.
 *
 * <h2>Scope: the {@code api} module</h2>
 *
 * <p>Row scope is a property of a request. The {@code worker} SLA scanners read
 * tickets with no caller at all, so the question this rule asks cannot be put
 * to them — see {@link ProductionClasses} for why they are outside the rule
 * rather than exempted from it. Within {@code api} the three classes that
 * genuinely have no caller declare it with {@link UnscopedAccess}.
 */
class ScopeGuardRulesTest {

    private static final String API = "com.edunext.edutrack.api..";
    private static final String SCOPE_PACKAGE = "com.edunext.edutrack.api.security.scope..";

    @Test
    void ticketsAreReadThroughTheScopeGuardAndNotThroughTheRepository() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(API)
                .and().resideOutsideOfPackage(SCOPE_PACKAGE)
                .and().areNotAnnotatedWith(UnscopedAccess.class)
                .should().dependOnClassesThat().areAssignableTo(TicketRepository.class)
                .because("""
                        an out-of-scope ticket must be indistinguishable from one that does \
                        not exist, and TicketRepository answers both truthfully. Call \
                        ScopedTickets — pass your own filter as the criteria argument, which \
                        is AND-ed onto the caller's scope and cannot replace it. If this \
                        class really has no caller to scope by, say so with \
                        @UnscopedAccess and the reason.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * A-112 · the same rule for the second module.
     *
     * <p>Its own test rather than a second {@code areAssignableTo} on the rule
     * above, so a failure names which guard was bypassed. The two modules have
     * separate resolvers, separate doors and separate 404s, and a combined
     * message reading "ScopedTickets or ScopedJourneys" is one a developer has
     * to decode before they can act on it.
     *
     * <p>{@link UnscopedAccess} exempts here too. It has no onboarding
     * declarations today and that is the expected state — the ticketing
     * exemptions are an inbound mail webhook and a fixture generator, and
     * onboarding has no equivalent yet. If one appears it must carry a reason,
     * which {@link #everyBypassStatesItsReason} already enforces for both
     * modules at once.
     */
    @Test
    void journeysAreReadThroughTheScopeGuardAndNotThroughTheRepository() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(API)
                .and().resideOutsideOfPackage(SCOPE_PACKAGE)
                .and().areNotAnnotatedWith(UnscopedAccess.class)
                .should().dependOnClassesThat().areAssignableTo(ObJourneyRepository.class)
                .because("""
                        an out-of-scope journey must be indistinguishable from one that does                         not exist, and ObJourneyRepository answers both truthfully. Call                         ScopedJourneys — pass your own filter as the criteria argument, which                         is AND-ed onto the caller's scope and cannot replace it. If this                         class really has no caller to scope by, say so with                         @UnscopedAccess and the reason.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * {@code @UnscopedAccess("")} would be an exemption list with extra steps.
     * The reason is the only part of the annotation that does any work: it is
     * what a reviewer reads instead of reconstructing the argument, and what
     * makes a bypass that stopped being true findable later.
     */
    @Test
    void everyBypassStatesItsReason() {
        List<JavaClass> bypasses = ProductionClasses.get().stream()
                .filter(c -> c.isAnnotatedWith(UnscopedAccess.class))
                .toList();

        assertThat(bypasses)
                .as("if nothing declares a bypass any more, delete @UnscopedAccess "
                        + "rather than leaving an unused door in security.scope")
                .isNotEmpty();

        for (JavaClass bypass : bypasses) {
            assertThat(bypass.getAnnotationOfType(UnscopedAccess.class).value())
                    .as("%s bypasses the scope guard without saying why", bypass.getSimpleName())
                    .isNotBlank();
        }
    }

    /**
     * The 403 that puts the existence leak back.
     *
     * <p>{@code @PostAuthorize} runs the handler, gets the row, evaluates the
     * expression against it and — on failure — answers <b>403</b>. On a ticket
     * route that is the exact leak A-035 exists to prevent, arrived at by an
     * annotation that reads like the careful thing to do. Nothing in the
     * codebase uses it today, which is the cheapest possible moment to close
     * it.
     *
     * <p>Banned outright rather than only on ticket routes: a rule that has to
     * decide which handlers are "ticket routes" is a rule that gets that
     * judgement wrong on the route added next year. Capability checks belong in
     * {@code @PreAuthorize}, which runs before the handler and knows nothing
     * about any row; row visibility belongs to {@link ScopedTickets#require},
     * which has no branch to write and therefore none to write wrongly.
     */
    @Test
    void noHandlerDecidesVisibilityAfterReadingTheRow() {
        ArchRule rule = noMethods()
                .should().beAnnotatedWith(PostAuthorize.class)
                .because("""
                        @PostAuthorize reads the row and then refuses with 403, which \
                        confirms the row exists — the existence leak A-035 exists to \
                        prevent. Use @PreAuthorize for the capability and \
                        ScopedTickets.require for the row.""");

        rule.check(ProductionClasses.get());
    }
}
