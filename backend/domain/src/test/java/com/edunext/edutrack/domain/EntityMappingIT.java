package com.edunext.edutrack.domain;

import com.edunext.edutrack.domain.identity.Project;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-005 · proves the entity model matches the schema Stream A migrated.
 *
 * <h2>Why this test is mostly its own setup</h2>
 *
 * {@code spring.jpa.hibernate.ddl-auto=validate} makes Hibernate compare every
 * mapped property against the real column at context refresh — wrong column
 * name, wrong type, wrong nullability, missing table — and refuse to start. So
 * for the ~40 entities in this module, <b>the context loading is the
 * assertion</b>. Getting it to load against a real MySQL 8.4 with all
 * migrations applied is the whole cost, and it is why this is an {@code IT}
 * running under failsafe rather than a unit test.
 *
 * <p>That coverage is the reason B-005 could be written against DDL rather than
 * against a running database. Without it, the first person to find a typo'd
 * column would be whoever booted the API next.
 *
 * <p>The container flags mirror {@code docker-compose.yml} exactly, including
 * {@code --default-time-zone=+00:00} and {@code connectionTimeZone=UTC} —
 * storage is UTC everywhere (PLAN.md §3.1), and a mismatch here would let an
 * {@code Instant} round-trip through a local offset and still look correct.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        // The point of the whole class.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @PersistenceContext
    EntityManager em;

    @Autowired
    ProjectRepository projects;

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketHistoryRepository history;

    @Autowired
    TicketStageTransitionRepository transitions;

    /**
     * If this runs at all, {@code validate} passed for every entity Hibernate
     * found. The count assertion is a guard against the failure that would
     * otherwise pass silently: an entity-scan misconfiguration finding nothing,
     * validating nothing, and reporting success.
     */
    @Test
    void everyEntityValidatesAgainstTheMigratedSchema() {
        assertThat(em.getMetamodel().getEntities())
                .as("entity scan found the domain model")
                .hasSizeGreaterThanOrEqualTo(35);
    }

    // ------------------------------------------------------------------
    // The append-only guarantee, from the ORM side
    // ------------------------------------------------------------------

    /**
     * A-008 already proves the database rejects an {@code UPDATE}
     * (SchemaIntegrationIT). This proves the ORM never sends one — which is the
     * failure PLAN.md §3.6 is actually worried about, because a dirty-checked
     * entity emits an {@code UPDATE} touching every column and nobody wrote a
     * line of code that looks like a mutation.
     */
    @Test
    void immutableEntitiesAreNeverDirtyChecked() {
        Ticket ticket = seedTicket("IT-IMM-001");
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setEventType("CREATED");
        entry.setNewValue("original");
        history.insert(entry);
        em.flush();
        em.clear();

        TicketHistory loaded = history.findById(entry.getId()).orElseThrow();
        loaded.setNewValue("tampered");
        em.flush();   // would SIGNAL 45000 if Hibernate emitted the UPDATE
        em.clear();

        assertThat(history.findById(entry.getId()).orElseThrow().getNewValue())
                .as("@Immutable suppressed the flush, so the trigger never had to")
                .isEqualTo("original");
    }

    /**
     * The one permitted mutation, and the guard that stops it happening twice —
     * a second seal would rewrite the duration the entire ribbon reports.
     */
    @Test
    void sealSucceedsOnceAndIsThenANoOp() {
        Ticket ticket = seedTicket("IT-SEAL-001");
        TicketStageTransition hop = new TicketStageTransition();
        hop.setTicketId(ticket.getId());
        hop.setCycleNo((short) 1);
        hop.setSeqNo(1);
        hop.setToStage("DEV");
        hop.setActionCode("FORWARD");
        hop.setEnteredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        transitions.insert(hop);
        em.flush();
        em.clear();

        assertThat(transitions.seal(hop.getId(), Instant.now().truncatedTo(ChronoUnit.MICROS), 90))
                .as("the open stage seals")
                .isEqualTo(1);
        em.clear();

        assertThat(transitions.seal(hop.getId(), Instant.now().truncatedTo(ChronoUnit.MICROS), 1))
                .as("a second seal matches no open row rather than rewriting the duration")
                .isZero();
        em.clear();

        TicketStageTransition sealed = transitions.findById(hop.getId()).orElseThrow();
        assertThat(sealed.getDurationMins()).isEqualTo(90);
        assertThat(sealed.isCurrent()).isFalse();
        assertThat(sealed.getCurrentTicketId())
                .as("A-009's generated column clears once the stage is sealed")
                .isNull();
    }

    /** {@code findByCurrentTicketId} is the "where is this ticket now" read. */
    @Test
    void currentStageIsFoundThroughTheGeneratedColumn() {
        Ticket ticket = seedTicket("IT-CUR-001");
        TicketStageTransition hop = new TicketStageTransition();
        hop.setTicketId(ticket.getId());
        hop.setCycleNo((short) 1);
        hop.setSeqNo(1);
        hop.setToStage("QA");
        hop.setActionCode("FORWARD");
        hop.setEnteredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        transitions.insert(hop);
        em.flush();
        em.clear();

        assertThat(transitions.findByCurrentTicketId(ticket.getId()))
                .get()
                .extracting(TicketStageTransition::getToStage)
                .isEqualTo("QA");
    }

    /**
     * A fresh project per ticket. {@code project_code} is {@code VARCHAR(10)}
     * and unique, and B-001..B-004 seed real codes into this same schema, so
     * the fixtures use a prefix no seed will ever claim.
     */
    private static final AtomicInteger PROBE_SEQ = new AtomicInteger();

    private Ticket seedTicket(String code) {
        Project project = new Project();
        project.setProjectCode("ITP" + PROBE_SEQ.incrementAndGet());
        project.setName("Mapping IT project");
        projects.saveAndFlush(project);

        Ticket ticket = new Ticket();
        ticket.setTicketCode(code);
        ticket.setProjectId(project.getId());
        ticket.setTitle("mapping probe");
        ticket.setLevel("LOW");
        ticket.setOriginalLevel("LOW");
        ticket.setDateReported(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return tickets.saveAndFlush(ticket);
    }
}
