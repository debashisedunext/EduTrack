package com.edunext.edutrack.domain.appendonly;

import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-005 · the append-only rule, asserted rather than trusted.
 *
 * <p>CLAUDE.md: "This is the guarantee that erodes first and is hardest to
 * restore." It erodes in one specific way — someone needs a query, changes
 * {@code extends Repository} to {@code extends JpaRepository} to get
 * {@code findAll} for free, and silently publishes {@code save},
 * {@code delete}, {@code deleteAll} and {@code deleteAllInBatch} along with it.
 * The code still compiles, the tests still pass, and the A-008 trigger becomes
 * the only thing standing between a stray call and a rewritten audit trail.
 *
 * <p>This test fails at exactly that moment, and it costs no infrastructure to
 * run — it is reflection over an interface, so it runs in surefire on every
 * build rather than only where Docker exists.
 */
class AppendOnlyContractTest {

    /**
     * Every mutator {@code CrudRepository} and {@code JpaRepository} publish.
     * {@code insert} is deliberately absent: it is the one write that is
     * allowed.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteById", "deleteAll", "deleteAllById",
            "deleteAllInBatch", "deleteAllByIdInBatch");

    @ParameterizedTest
    @ValueSource(classes = {
            TicketHistoryRepository.class,
            TicketEffortLogRepository.class,
            TicketStageTransitionRepository.class
    })
    void appendOnlyRepositoriesPublishNoMutator(Class<?> repository) {
        assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                .as("%s must expose no update or delete — only insert", repository.getSimpleName())
                .doesNotContainAnyElementsOf(FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(classes = {
            TicketHistoryRepository.class,
            TicketEffortLogRepository.class,
            TicketStageTransitionRepository.class
    })
    void appendOnlyRepositoriesPublishInsert(Class<?> repository) {
        assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                .as("%s is append-only, so appending must be possible", repository.getSimpleName())
                .contains("insert");
    }

    /**
     * The ORM half. Without {@link Immutable}, loading one of these rows and
     * touching a field is enough for Hibernate to emit an {@code UPDATE} on
     * flush that nobody wrote (PLAN.md §3.6).
     */
    @ParameterizedTest
    @ValueSource(classes = {
            TicketHistory.class,
            TicketEffortLog.class,
            TicketStageTransition.class
    })
    void appendOnlyEntitiesAreImmutable(Class<?> entity) {
        assertThat(entity.isAnnotationPresent(Immutable.class))
                .as("%s must be @Immutable so Hibernate never dirty-checks it into an UPDATE",
                        entity.getSimpleName())
                .isTrue();
    }

    /**
     * Sealing a stage transition is the single exception the A-008 trigger
     * permits, so it must exist here — and only here.
     */
    @Test
    void onlyStageTransitionsCanBeSealed() {
        assertThat(Arrays.stream(TicketStageTransitionRepository.class.getMethods()).map(Method::getName))
                .contains("seal");
        assertThat(Arrays.stream(TicketHistoryRepository.class.getMethods()).map(Method::getName))
                .as("history has no sealing concept and must not grow one")
                .doesNotContain("seal");
        assertThat(Arrays.stream(TicketEffortLogRepository.class.getMethods()).map(Method::getName))
                .as("an effort log has no sealing concept and must not grow one")
                .doesNotContain("seal");
    }
}
