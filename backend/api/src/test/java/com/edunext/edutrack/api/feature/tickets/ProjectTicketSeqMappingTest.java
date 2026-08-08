package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.identity.Project;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-011 · {@code projects.ticket_seq} must stay unwritable through JPA.
 *
 * <p>An odd place for a test — the field belongs to Stream B's entity, and this
 * suite is Stream C's. It sits here because the <i>reason</i> the mapping matters
 * lives here: {@link TicketSequenceRepository} moves that column with raw SQL on
 * the transaction's connection, deliberately outside Hibernate's view. Stream B
 * has no way to see that from their side, and a reviewer looking only at
 * {@code Project} would read {@code updatable = false} as a tidy detail worth
 * removing.
 *
 * <p>{@code TicketIdGenerationIT.jpaFlushCannotRevertTheAllocation} proves the
 * behaviour against a real database. This one is the fast, no-infrastructure
 * tripwire that names the consequence — it runs in every {@code mvn test}, while
 * the IT needs Docker and only runs at {@code verify}. Deleting the mapping
 * should fail the build in seconds, not in whichever CI stage has a container.
 */
class ProjectTicketSeqMappingTest {

    @Test
    @DisplayName("ticket_seq is mapped updatable = false, so no flush can write it")
    void ticketSeqIsNotUpdatable() throws NoSuchFieldException {
        Column column = Project.class.getDeclaredField("ticketSeq").getAnnotation(Column.class);

        assertThat(column).as("@Column on Project.ticketSeq").isNotNull();
        assertThat(column.name()).isEqualTo("ticket_seq");
        assertThat(column.updatable())
                .as("""
                        Project.ticketSeq must stay updatable = false.

                        Hibernate writes EVERY updatable column of a dirty entity, not just the
                        ones that changed. C-010 loads a Project, edits a field, and allocates a
                        ticket code in one transaction; with this true, the commit flushes the
                        ticket_seq that was read BEFORE the raw LAST_INSERT_ID increment, undoing
                        the allocation. The next ticket then reuses the ID and fails on
                        uq_tickets_code — in a later request, with nothing pointing back here.

                        Allocate through TicketSequenceRepository. Never through JPA.""")
                .isFalse();
    }

    @Test
    @DisplayName("no setter exists — the counter is not something application code assigns")
    void ticketSeqHasNoSetter() {
        // With updatable = false a setter would be worse than useless: it would
        // compile, run, mutate the in-memory object, and never reach the
        // database — the kind of silent no-op that costs an afternoon.
        assertThat(Arrays.stream(Project.class.getMethods()).map(m -> m.getName()))
                .doesNotContain("setTicketSeq");
    }

    @Test
    @DisplayName("the field is still readable — nothing here asks Stream B to drop the mapping")
    void ticketSeqRemainsMapped() throws NoSuchFieldException {
        // Mapped and readable is correct: ddl-auto=validate needs the column
        // present, and reporting may want it. What must not happen is a write.
        Field field = Project.class.getDeclaredField("ticketSeq");
        assertThat(field.getType()).isEqualTo(Long.class);
        assertThat(Arrays.stream(Project.class.getMethods()).map(java.lang.reflect.Method::getName))
                .contains("getTicketSeq");
    }
}
