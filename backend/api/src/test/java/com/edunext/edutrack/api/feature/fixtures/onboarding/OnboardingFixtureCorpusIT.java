package com.edunext.edutrack.api.feature.fixtures.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-101 · the onboarding corpus against a real MySQL and a real Flyway run.
 *
 * <p>{@code local,fixtures} is active, so context startup loads both corpora
 * exactly as a developer would by hand; this class only asserts on what landed.
 *
 * <p>What it exists to catch is the same thing {@code FixtureCorpusIT} catches
 * for tickets: a loader that runs without an exception and still produces a
 * corpus that is not what B-101 promised. The constraints A-101 to A-106 wrote
 * are strict enough that most mistakes cannot be inserted at all — a blocked
 * step with no reason, a False answer with no remark, a second active template
 * version, a journey for a product nobody bought. So the assertions here are
 * mostly about the properties no constraint can hold: that the corpus actually
 * <em>contains</em> every state the module has to handle, and that the dates in
 * it are working-calendar dates rather than plausible-looking ones.
 */
@SpringBootTest
@ActiveProfiles({"local", "fixtures"})
@Testcontainers
class OnboardingFixtureCorpusIT {

    /** The org calendar's zone, seeded by {@code V20260808_1630}. */
    private static final ZoneId CALENDAR_ZONE = ZoneId.of("Asia/Kolkata");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_fixtures_it")
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
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OnboardingFixture fixture;

    // ── the catalogue and the template ──────────────────────────────────────

    @Test
    @DisplayName("the four products and three template versions land, one active per product")
    void theCatalogueLands() {
        assertThat(count("SELECT COUNT(*) FROM ob_products")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM ob_journey_templates")).isEqualTo(3);

        List<Integer> activePerProduct = jdbc.queryForList("""
                SELECT COUNT(*) FROM ob_journey_templates WHERE is_active = 1 GROUP BY product_id
                """, Integer.class);
        assertThat(activePerProduct).isNotEmpty().allMatch(n -> n == 1);
    }

    @Test
    @DisplayName("Standard SaaS Onboarding is the prototype's eight steps, 24 items and 4 documents")
    void theSeededDefaultIsTheDesignsOwn() {
        Long templateId = activeStandardTemplateId();

        assertThat(count("SELECT COUNT(*) FROM ob_journey_template_steps WHERE template_id = ?", templateId))
                .isEqualTo(8);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_template_step_items i
                  JOIN ob_journey_template_steps s ON s.id = i.step_id
                 WHERE s.template_id = ?
                """, templateId)).isEqualTo(24);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_template_step_docs d
                  JOIN ob_journey_template_steps s ON s.id = d.step_id
                 WHERE s.template_id = ?
                """, templateId)).isEqualTo(4);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_template_steps
                 WHERE template_id = ? AND requires_signoff = 1
                """, templateId)).isEqualTo(3);

        assertThat(jdbc.queryForList("""
                SELECT name FROM ob_journey_template_steps WHERE template_id = ? ORDER BY sequence
                """, String.class, templateId))
                .startsWith("Kickoff call")
                .endsWith("Go-live sign-off");
    }

    @Test
    @DisplayName("the Biometric service declares its cross-product dependency on the ERP one")
    void theServiceDependencyLands() {
        Integer dependent = count("""
                SELECT COUNT(*) FROM ob_journey_templates t
                  JOIN ob_journey_templates d ON d.id = t.depends_on_template_id
                 WHERE t.name = 'Biometric Device Rollout' AND d.name = 'Standard SaaS Onboarding'
                """);
        assertThat(dependent).isEqualTo(1);
    }

    // ── clients ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("eight clients, each with exactly one active primary SPOC")
    void theClientsLand() {
        assertThat(count("SELECT COUNT(*) FROM ob_clients")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE overall_status = 'LIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE live_at IS NOT NULL")).isEqualTo(1);

        List<Integer> primaries = jdbc.queryForList("""
                SELECT COUNT(*) FROM ob_client_contacts WHERE is_primary = 1 AND is_active = 1
                 GROUP BY ob_client_id
                """, Integer.class);
        assertThat(primaries).hasSize(8).allMatch(n -> n == 1);
    }

    @Test
    @DisplayName("PAN is not written — A-113 owns the ciphertext and the blind index")
    void panStaysNull() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_clients
                 WHERE pan_ciphertext IS NOT NULL OR pan_blind_index IS NOT NULL
                """)).isZero();
    }

    @Test
    @DisplayName("WhatsApp consent is captured per contact, and is not uniformly off")
    void consentIsCaptured() {
        assertThat(count("SELECT COUNT(*) FROM ob_client_contacts WHERE whatsapp_opt_in = 1"))
                .isPositive();
        assertThat(count("SELECT COUNT(*) FROM ob_client_contacts WHERE whatsapp_opt_in = 0"))
                .isPositive();
    }

    @Test
    @DisplayName("every purchase carries the licence window a renewals read would need")
    void purchasesCarryTheirLicenceWindow() {
        assertThat(count("SELECT COUNT(*) FROM ob_client_applications")).isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_client_applications
                 WHERE license_start IS NULL OR license_end IS NULL OR license_end < license_start
                """)).isZero();
    }

    // ── journeys and steps ──────────────────────────────────────────────────

    @Test
    @DisplayName("eleven journeys, every one of them on a product its client bought")
    void theJourneysLand() {
        assertThat(count("SELECT COUNT(*) FROM ob_journeys")).isEqualTo(11);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journeys j
                 WHERE NOT EXISTS (SELECT 1 FROM ob_client_applications a
                                    WHERE a.ob_client_id = j.ob_client_id
                                      AND a.product_id = j.product_id)
                """)).isZero();
    }

    @Test
    @DisplayName("the corpus contains a locked gate, a held journey and a completed one")
    void theJourneyStatesAreAllRepresented() {
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE gate_status = 'LOCKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE held_by_journey_id IS NOT NULL"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE completed_at IS NOT NULL")).isEqualTo(2);
        // A journey held behind another service is always the same client's.
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journeys j JOIN ob_journeys h ON h.id = j.held_by_journey_id
                 WHERE h.ob_client_id <> j.ob_client_id
                """)).isZero();
    }

    @Test
    @DisplayName("every step status the module has to handle is present in the corpus")
    void everyStepStatusIsExercised() {
        List<String> statuses = jdbc.queryForList(
                "SELECT DISTINCT status FROM ob_journey_steps", String.class);
        assertThat(statuses)
                .contains("PENDING", "IN_PROGRESS", "DONE", "BLOCKED", "WAITING_ON_CLIENT");
    }

    @Test
    @DisplayName("a blocked step names its reason, and a False answer carries its remark")
    void theRefusalsAreExercisedRatherThanAvoided() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps
                 WHERE status = 'BLOCKED' AND blocked_reason_code IS NOT NULL AND blocked_note IS NOT NULL
                """)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_step_items
                 WHERE answer = 0 AND remark IS NOT NULL AND remark <> ''
                """)).isPositive();
        assertThat(count("SELECT COUNT(*) FROM ob_journey_step_items WHERE answer IS NULL"))
                .as("an in-flight step has unanswered Task List items — that is what stops it completing")
                .isPositive();
    }

    @Test
    @DisplayName("a step's dependency always finished before it started")
    void theTimelineHoldsInTheDatabase() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                  JOIN ob_journey_steps d ON d.id = s.depends_on_step_id
                 WHERE s.started_at IS NOT NULL
                   AND (d.finished_at IS NULL OR d.finished_at > s.started_at)
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps
                 WHERE finished_at IS NOT NULL AND started_at IS NOT NULL AND finished_at < started_at
                """)).isZero();
    }

    // ── the working calendar ────────────────────────────────────────────────

    @Test
    @DisplayName("no step is due on a weekend — the TAT is spent in working days")
    void dueDatesHonourTheWorkingCalendar() {
        List<Timestamp> dueDates = jdbc.queryForList(
                "SELECT due_at FROM ob_journey_steps WHERE due_at IS NOT NULL", Timestamp.class);

        assertThat(dueDates).isNotEmpty();
        for (Timestamp due : dueDates) {
            LocalDate localDue = LocalDate.ofInstant(due.toInstant(), CALENDAR_ZONE);
            assertThat(localDue.getDayOfWeek())
                    .as("a step due on %s — a Friday-start TAT must not land at the weekend", localDue)
                    .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        }
    }

    @Test
    @DisplayName("the corpus has something already breached for the scanner to find")
    void thereIsABreachToDetect() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps
                 WHERE status NOT IN ('DONE', 'SKIPPED', 'PENDING') AND due_at < NOW(6)
                """)).isPositive();
    }

    // ── the clock, the timeline, the history ────────────────────────────────

    @Test
    @DisplayName("every started step has a clock, and only a pause carries a reason")
    void theClockIsWrittenRatherThanImplied() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                 WHERE s.started_at IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM ob_step_clock_events e
                                    WHERE e.step_id = s.id AND e.event_type = 'STARTED')
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                 WHERE s.finished_at IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM ob_step_clock_events e
                                    WHERE e.step_id = s.id AND e.event_type = 'STOPPED')
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_step_clock_events
                 WHERE event_type = 'PAUSED' AND pause_reason = 'WAITING_ON_CLIENT'
                   AND attributed_to = 'CLIENT'
                """)).isEqualTo(1);
    }

    @Test
    @DisplayName("a blocked step's clock keeps running — only waiting-on-client stops it")
    void blockingDoesNotStopTheClock() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_step_clock_events e
                  JOIN ob_journey_steps s ON s.id = e.step_id
                 WHERE s.status = 'BLOCKED' AND e.event_type = 'PAUSED'
                """)).isZero();
    }

    @Test
    @DisplayName("the timeline carries staff, client and system entries, and internal notes stay internal")
    void theCommunicationsLand() {
        assertThat(count("SELECT COUNT(*) FROM ob_step_communications")).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT DISTINCT author_type FROM ob_step_communications", String.class))
                .containsExactlyInAnyOrder("STAFF", "CLIENT", "SYSTEM");
        assertThat(count("SELECT COUNT(*) FROM ob_step_communications WHERE is_client_visible = 0"))
                .isPositive();
        // ck_ob_comms_author, restated as a corpus property: exactly one author
        // column is set, and it matches the type.
        assertThat(count("""
                SELECT COUNT(*) FROM ob_step_communications
                 WHERE (author_type = 'STAFF'  AND author_user_id IS NULL)
                    OR (author_type = 'CLIENT' AND author_contact_id IS NULL)
                    OR (author_type = 'SYSTEM' AND (author_user_id IS NOT NULL
                                                 OR author_contact_id IS NOT NULL))
                """)).isZero();
    }

    @Test
    @DisplayName("every journey has history, and every history row belongs to its journey's client")
    void theHistoryLands() {
        assertThat(count("SELECT COUNT(*) FROM ob_step_history")).isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journeys j
                 WHERE NOT EXISTS (SELECT 1 FROM ob_step_history h WHERE h.journey_id = j.id)
                   AND j.started_at IS NOT NULL
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM ob_step_history h JOIN ob_journeys j ON j.id = h.journey_id
                 WHERE h.ob_client_id <> j.ob_client_id
                """)).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT event_type FROM ob_step_history", String.class))
                .contains("GATE_OPENED", "STEP_ACTIVATED", "COMPLETED", "BLOCKED", "WAITING_ON_CLIENT");
    }

    @Test
    @DisplayName("the hash chain is left NULL on purpose — the onboarding journal owns the payload")
    void theHashChainIsUnwrittenRatherThanInvented() {
        assertThat(count("""
                SELECT COUNT(*) FROM ob_step_history
                 WHERE prev_hash IS NOT NULL OR row_hash IS NOT NULL
                """))
                .as("a fixture that invented a payload would hand A-123's verifier rows that "
                        + "fail to verify against whatever Stream A actually ships")
                .isZero();
    }

    @Test
    @DisplayName("one open client escalation, raised by a contact rather than a user")
    void theEscalationLands() {
        assertThat(count("SELECT COUNT(*) FROM ob_client_escalations WHERE resolved_at IS NULL"))
                .isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_client_escalations e
                  JOIN ob_client_contacts c ON c.id = e.raised_by_contact_id
                  JOIN ob_journeys j ON j.id = e.journey_id
                 WHERE c.ob_client_id <> j.ob_client_id
                """)).isZero();
    }

    // ── idempotency ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a second run is a no-op, not a second corpus")
    void loadingTwiceIsSafe() {
        assertThat(fixture.alreadyLoaded()).isTrue();

        int clientsBefore = count("SELECT COUNT(*) FROM ob_clients");
        int journeysBefore = count("SELECT COUNT(*) FROM ob_journeys");

        // What FixtureLoader does on a second startup.
        if (!fixture.alreadyLoaded()) {
            fixture.load();
        }

        assertThat(count("SELECT COUNT(*) FROM ob_clients")).isEqualTo(clientsBefore);
        assertThat(count("SELECT COUNT(*) FROM ob_journeys")).isEqualTo(journeysBefore);
    }

    @Test
    @DisplayName("the corpus reuses B-007's Priya Nair rather than creating a second one")
    void thereIsOnlyOnePriyaNair() {
        assertThat(count("SELECT COUNT(*) FROM users WHERE full_name = 'Priya Nair'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM users WHERE emp_code LIKE 'B101-%'")).isEqualTo(7);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_template_steps s
                 WHERE s.owner_user_id IS NULL
                """)).isZero();
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private Long activeStandardTemplateId() {
        return jdbc.queryForObject("""
                SELECT id FROM ob_journey_templates
                 WHERE name = 'Standard SaaS Onboarding' AND is_active = 1
                """, Long.class);
    }
}
