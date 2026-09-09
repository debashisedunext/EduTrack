package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.upload.UploadPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-118 · the go-live flip against real MySQL and a real multi-journey
 * client — what {@code ObSignoffAcceptServiceTest} (mocks
 * {@code ObClientGoLiveService} itself) cannot show:
 *
 * <ol>
 *   <li><b>The flip is a fact about the client's other journeys</b>, read
 *       with a real query against real {@code ob_journeys}/{@code
 *       ob_signoffs} rows — a mocked collaborator cannot prove the "every
 *       other live journey" condition is actually what the SQL says.</li>
 *   <li><b>It is the same transaction as the acceptance</b>: driven through
 *       the real {@link ObSignoffAcceptService#accept}, not called
 *       directly.</li>
 *   <li><b>The handover note and the {@code GO_LIVE} notification are real
 *       side effects</b> — an upload call and outbox rows, not assertions
 *       about whether a mock was invoked.</li>
 * </ol>
 *
 * <p>{@link ObSignoffSessions} and {@link UploadPipeline} are mocked, on
 * {@code ObSignoffCertificateIT}'s own precedent: Redis and a real bucket
 * would each prove their own client rather than anything about this feature.
 */
@SpringBootTest
@Testcontainers
class ObSignoffGoLiveIT {

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
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T09:00:00Z");
    private static final String SESSION_A = "session-journey-a";
    private static final String SESSION_B = "session-journey-b";

    @Autowired
    ObSignoffAcceptService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ObSignoffSessions sessions;

    @MockitoBean
    UploadPipeline uploads;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long ownerA;
    private long ownerB;
    private long clientId;
    private long journeyAId;
    private long journeyBId;
    private long contactId;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        long salesperson = insertUser("it_golive_sales_" + run);
        ownerA = insertUser("it_golive_ownerA_" + run);
        ownerB = insertUser("it_golive_ownerB_" + run);

        long productA = insertProduct("ITGOA_" + run, "IT Go Live Product A " + run);
        long templateA = insertTemplate(productA, "IT Go Live Onboarding A " + run);
        long productB = insertProduct("ITGOB_" + run, "IT Go Live Product B " + run);
        long templateB = insertTemplate(productB, "IT Go Live Onboarding B " + run);

        clientId = insertClient("IT Go Live Client " + run, salesperson);
        insertApplication(clientId, productA);
        insertApplication(clientId, productB);
        journeyAId = insertJourney(clientId, productA, templateA);
        journeyBId = insertJourney(clientId, productB, templateB);
        insertStep(journeyAId, "Step A", ownerA);
        insertStep(journeyBId, "Step B", ownerB);
        contactId = insertContact(clientId, "Priya Raman", "priya" + run + "@client.example");
    }

    @Test
    @DisplayName("flips only once every one of the client's journeys carries a SIGNED GO_LIVE")
    void flipsOnlyOnTheLastJourney() {
        long signoffA = insertGoLiveSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_A)).thenReturn(OptionalLong.of(signoffA));

        PublicSignoffAcceptDtos.AcceptResult resultA =
                service.accept(SESSION_A, "Priya Raman", null, null);

        assertThat(resultA.clientWentLive()).isFalse();
        Map<String, Object> clientAfterA = jdbc.queryForMap(
                "SELECT overall_status, live_at FROM ob_clients WHERE id = ?", clientId);
        assertThat(clientAfterA.get("overall_status")).isEqualTo("ONBOARDING");
        assertThat(clientAfterA.get("live_at")).isNull();
        // The certificate for A's own acceptance is archived either way — what
        // must not have happened yet is the client-wide handover note.
        verify(uploads, never()).put(eq(ObGoLiveHandoverKey.mint(clientId)), any(), any());
        assertThat(jdbc.queryForList(
                "SELECT 1 FROM ob_notification_outbox WHERE ob_client_id = ? AND event_key = 'GO_LIVE'",
                clientId)).isEmpty();

        long signoffB = insertGoLiveSignoff(journeyBId, contactId);
        when(sessions.resolve(SESSION_B)).thenReturn(OptionalLong.of(signoffB));

        PublicSignoffAcceptDtos.AcceptResult resultB =
                service.accept(SESSION_B, "Priya Raman", null, null);

        assertThat(resultB.clientWentLive()).isTrue();
        Map<String, Object> clientAfterB = jdbc.queryForMap(
                "SELECT overall_status, live_at FROM ob_clients WHERE id = ?", clientId);
        assertThat(clientAfterB.get("overall_status")).isEqualTo("LIVE");
        assertThat(clientAfterB.get("live_at")).isNotNull();

        verify(uploads).put(eq(ObGoLiveHandoverKey.mint(clientId)), any(), eq("application/pdf"));

        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT event_key, channel, recipient_user_id, recipient_contact_id "
                        + "FROM ob_notification_outbox WHERE ob_client_id = ? AND event_key = 'GO_LIVE'",
                clientId);
        assertThat(outbox).isNotEmpty();
        assertThat(outbox).anySatisfy(row -> {
            assertThat(row.get("channel")).isEqualTo("EMAIL");
            assertThat(((Number) row.get("recipient_contact_id")).longValue()).isEqualTo(contactId);
        });
        assertThat(outbox)
                .filteredOn(row -> row.get("recipient_user_id") != null)
                .extracting(row -> ((Number) row.get("recipient_user_id")).longValue())
                .contains(ownerA, ownerB);
    }

    @Test
    @DisplayName("an archived journey with no sign-off at all does not block the flip")
    void archivedJourneyIsExcluded() {
        long productX = insertProduct("ITGOX_" + RUN.get(), "IT Go Live Product X " + RUN.get());
        long templateX = insertTemplate(productX, "IT Go Live Onboarding X " + RUN.get());
        insertApplication(clientId, productX);
        long journeyXId = insertJourney(clientId, productX, templateX);
        jdbc.update("UPDATE ob_journeys SET archived_at = ? WHERE id = ?",
                Timestamp.from(REQUESTED_AT), journeyXId);

        long signoffA = insertGoLiveSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_A)).thenReturn(OptionalLong.of(signoffA));
        service.accept(SESSION_A, "Priya Raman", null, null);

        long signoffB = insertGoLiveSignoff(journeyBId, contactId);
        when(sessions.resolve(SESSION_B)).thenReturn(OptionalLong.of(signoffB));
        PublicSignoffAcceptDtos.AcceptResult resultB =
                service.accept(SESSION_B, "Priya Raman", null, null);

        assertThat(resultB.clientWentLive())
                .as("journey X is archived and never signed, and must not hold the flip back")
                .isTrue();
    }

    @Test
    @DisplayName("a second acceptance can never re-earn a client that is already LIVE")
    void alreadyLiveClientNeverFlipsTwice() {
        long signoffA = insertGoLiveSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_A)).thenReturn(OptionalLong.of(signoffA));
        service.accept(SESSION_A, "Priya Raman", null, null);

        long signoffB = insertGoLiveSignoff(journeyBId, contactId);
        when(sessions.resolve(SESSION_B)).thenReturn(OptionalLong.of(signoffB));
        service.accept(SESSION_B, "Priya Raman", null, null);

        Instant liveAtAfterFirstFlip = (Instant) jdbc.queryForObject(
                "SELECT live_at FROM ob_clients WHERE id = ?",
                (rs, row) -> rs.getTimestamp("live_at").toInstant(), clientId);

        // A third journey, purchased and signed after go-live — the contract's
        // own "at most once in a client's life" claim, exercised rather than
        // only asserted in prose.
        long productC = insertProduct("ITGOC_" + RUN.get(), "IT Go Live Product C " + RUN.get());
        long templateC = insertTemplate(productC, "IT Go Live Onboarding C " + RUN.get());
        insertApplication(clientId, productC);
        long journeyCId = insertJourney(clientId, productC, templateC);
        long signoffC = insertGoLiveSignoff(journeyCId, contactId);
        when(sessions.resolve("session-journey-c")).thenReturn(OptionalLong.of(signoffC));

        PublicSignoffAcceptDtos.AcceptResult resultC =
                service.accept("session-journey-c", "Priya Raman", null, null);

        assertThat(resultC.clientWentLive())
                .as("a client already LIVE cannot be flipped a second time")
                .isFalse();
        Instant liveAtAfterThirdJourney = (Instant) jdbc.queryForObject(
                "SELECT live_at FROM ob_clients WHERE id = ?",
                (rs, row) -> rs.getTimestamp("live_at").toInstant(), clientId);
        assertThat(liveAtAfterThirdJourney).isEqualTo(liveAtAfterFirstFlip);
    }

    // ------------------------------------------------------------------
    // fixtures — ObSignoffObjectServiceIT's own pattern, one file over
    // ------------------------------------------------------------------

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)", code, name);
        return lastId();
    }

    private long insertTemplate(long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, productId, name);
        return lastId();
    }

    private long insertClient(String name, long createdBy) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, sales_person_id, created_by)
                VALUES (?, '2026-09-01', ?, ?)
                """, name, createdBy, createdBy);
        return lastId();
    }

    private void insertApplication(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    private long insertJourney(long clientId, long productId, long templateId) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status)
                VALUES (?, ?, ?, 'OPEN')
                """, clientId, productId, templateId);
        return lastId();
    }

    private long insertContact(long clientId, String name, String email) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, ?, ?, 1)
                """, clientId, name, email);
        return lastId();
    }

    private void insertStep(long journeyId, String name, long ownerId) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                               status, started_at, due_at)
                VALUES (?, 1, ?, 2, ?, 'DONE', ?, ?)
                """, journeyId, name, ownerId,
                Timestamp.from(REQUESTED_AT), Timestamp.from(REQUESTED_AT.plusSeconds(2 * 86_400)));
    }

    private long insertGoLiveSignoff(long journeyId, long contactId) {
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at, sent_to_contact_id)
                VALUES (?, ?, NULL, 'GO_LIVE', 'PENDING', ?, ?, ?, ?)
                """, clientId, journeyId,
                String.format("%064d", RUN.incrementAndGet()),
                Timestamp.from(REQUESTED_AT.plusSeconds(86_400)),
                Timestamp.from(REQUESTED_AT), contactId);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
