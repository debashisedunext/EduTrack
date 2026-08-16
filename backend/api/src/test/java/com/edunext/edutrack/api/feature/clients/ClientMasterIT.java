package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.CursorPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-025 · S-32 against a real MySQL.
 *
 * <p>{@code ClientServiceTest} proves the decisions against mocks. This proves
 * the half a mock cannot: that the keyset really does traverse every row exactly
 * once under the database's own collation, that the {@code statuses.is_terminal}
 * join counts what S-32 calls open, and that the five filters compose.
 *
 * <p>Fixture rows are prefixed {@code ITCL} so nothing collides with another
 * suite and the cleanup can be exact. Nothing is seeded into {@code clients} by
 * any migration, so every row here is this suite's own.
 */
@SpringBootTest
@Testcontainers
class ClientMasterIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_client_master_it")
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
    ClientService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearFixtureRows() {
        // Children first — every one of these is a foreign key into `clients`,
        // which is the whole reason the master deactivates rather than deletes.
        jdbc.update("DELETE FROM tickets WHERE ticket_code LIKE 'ITCL-%'");
        jdbc.update("DELETE FROM client_contacts WHERE client_id IN"
                + " (SELECT id FROM clients WHERE client_code LIKE 'ITCL%')");
        jdbc.update("DELETE FROM client_projects WHERE client_id IN"
                + " (SELECT id FROM clients WHERE client_code LIKE 'ITCL%')");
        jdbc.update("DELETE FROM clients WHERE client_code LIKE 'ITCL%'");
    }

    // ------------------------------------------------------------------
    // The traversal
    // ------------------------------------------------------------------

    /**
     * The property A-053 exists to protect, asserted end to end against MySQL.
     *
     * <p>Walks 23 clients at a limit of 5 and asserts every row is visited
     * exactly once — no skips, no repeats. The failure modes this catches are
     * all silent: an off-by-one in the trim shows up as one client missing from
     * a list nobody counts.
     */
    @Test
    @DisplayName("paging visits every client exactly once")
    void keysetTraversalIsComplete() {
        for (int i = 1; i <= 23; i++) {
            insertClient("ITCL%02d".formatted(i), "IT Client %02d".formatted(i), "ACTIVE");
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ClientDtos.Client> page = service.list(filterOn("IT Client"), cursor, 5);
            page.data().forEach(c -> seen.add(c.clientCode()));
            cursor = page.meta() == null ? null : page.meta().nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(23);
        assertThat(new HashSet<>(seen)).hasSize(23);
        assertThat(seen).isSorted();
    }

    /**
     * The tiebreak is why the traversal above is complete.
     *
     * <p>{@code client_code} is unique and the name is not — after a bulk import
     * duplicate names are ordinary. A keyset over {@code name} alone skips every
     * row after the first at each collision.
     */
    @Test
    @DisplayName("clients sharing a name are all returned, not collapsed")
    void duplicateNamesDoNotSkipRows() {
        insertClient("ITCL_A", "IT Same Name", "ACTIVE");
        insertClient("ITCL_B", "IT Same Name", "ACTIVE");
        insertClient("ITCL_C", "IT Same Name", "ACTIVE");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ClientDtos.Client> page = service.list(filterOn("IT Same Name"), cursor, 1);
            page.data().forEach(c -> seen.add(c.clientCode()));
            cursor = page.meta() == null ? null : page.meta().nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrder("ITCL_A", "ITCL_B", "ITCL_C");
    }

    // ------------------------------------------------------------------
    // The derived columns
    // ------------------------------------------------------------------

    /**
     * "Open" is {@code statuses.is_terminal = 0}, joined rather than compared
     * against a hardcoded {@code 'CLOSED'} — so a second terminal status added
     * through S-13 is counted correctly without this query changing.
     */
    @Test
    @DisplayName("open tickets exclude every terminal status, not just CLOSED")
    void openCountUsesTheStatusVocabulary() {
        long clientId = insertClient("ITCL_T", "IT Ticketed", "ACTIVE");
        long projectId = anyProjectId();
        insertTicket("ITCL-1", projectId, clientId, "NEW");
        insertTicket("ITCL-2", projectId, clientId, "IN_PROGRESS");
        insertTicket("ITCL-3", projectId, clientId, "CLOSED");

        ClientDtos.Client client = only(service.list(filterOn("IT Ticketed"), null, 50));

        assertThat(client.openTicketCount()).isEqualTo(2);
        assertThat(client.lastTicketDate()).isNotNull();
    }

    /**
     * The stored instant comes back as the stored instant.
     *
     * <p>B-023 lost a day to the mirror of this — 09:30 written, 15:00 read —
     * and the failure mode is silent: every Last Ticket date off by the
     * deployment's offset, on a column nobody reconciles. Asserted against a
     * literal rather than against "not null", because a shifted timestamp is
     * still a timestamp.
     */
    @Test
    @DisplayName("the last ticket date survives the round trip in UTC")
    void lastTicketDateIsNotShiftedByTheJvmZone() {
        long clientId = insertClient("ITCL_TZ", "IT Timezone", "ACTIVE");
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level,"
                        + " status, client_id, date_reported)"
                        + " VALUES ('ITCL-TZ', ?, 'Fixture', 'MEDIUM', 'MEDIUM', 'NEW', ?,"
                        + " '2026-08-01 09:15:00.000000')",
                anyProjectId(), clientId);

        ClientDtos.Client client = only(service.list(filterOn("IT Timezone"), null, 50));

        assertThat(client.lastTicketDate()).isEqualTo(Instant.parse("2026-08-01T09:15:00Z"));
    }

    /** A real state, and not a zero — the client nothing has been raised against. */
    @Test
    @DisplayName("a client with no tickets is zero open and no last date")
    void clientWithNoTicketsHasNoDate() {
        insertClient("ITCL_N", "IT Untouched", "ACTIVE");

        ClientDtos.Client client = only(service.list(filterOn("IT Untouched"), null, 50));

        assertThat(client.openTicketCount()).isZero();
        assertThat(client.lastTicketDate()).isNull();
    }

    /**
     * A client on three projects appears once, with three projects — not three
     * times. The join is an {@code EXISTS} for exactly this reason.
     */
    @Test
    @DisplayName("a client mapped to several projects is one row carrying all of them")
    void projectMappingDoesNotFanOutRows() {
        long clientId = insertClient("ITCL_P", "IT Mapped", "ACTIVE");
        List<Long> projectIds = allProjectIds();
        projectIds.forEach(p -> jdbc.update(
                "INSERT INTO client_projects (client_id, project_id, is_default) VALUES (?, ?, 0)",
                clientId, p));

        CursorPage<ClientDtos.Client> page = service.list(filterOn("IT Mapped"), null, 50);

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().getFirst().projects()).hasSize(projectIds.size());
    }

    @Test
    @DisplayName("filtering by project returns each matching client once")
    void projectFilterDoesNotDuplicate() {
        long clientId = insertClient("ITCL_F", "IT Filtered", "ACTIVE");
        long projectId = anyProjectId();
        jdbc.update("INSERT INTO client_projects (client_id, project_id, is_default)"
                + " VALUES (?, ?, 0)", clientId, projectId);

        CursorPage<ClientDtos.Client> page = service.list(
                new ClientQueryRepository.Filter("IT Filtered", null, projectId, null, null),
                null, 50);

        assertThat(page.data()).extracting(ClientDtos.Client::clientCode)
                .containsExactly("ITCL_F");
    }

    /**
     * "At most one primary" is a service-layer rule the schema cannot assert, so
     * the read must survive the state the database permits rather than throw on
     * it.
     */
    @Test
    @DisplayName("two primary contacts is a readable row, not an exception")
    void twoPrimaryContactsDoesNotBreakTheRead() {
        long clientId = insertClient("ITCL_C2", "IT Two Primaries", "ACTIVE");
        insertContact(clientId, "First Primary", true);
        insertContact(clientId, "Second Primary", true);

        ClientDtos.Client client = only(service.list(filterOn("IT Two Primaries"), null, 50));

        assertThat(client.primaryContact()).isNotNull();
        assertThat(service.contactsOf(clientId)).get().asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(ClientDtos.Contact.class))
                .hasSize(2);
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the free-text filter matches name, code and domain")
    void freeTextMatchesAllThree() {
        insertClient("ITCL_Q1", "IT Findable Alpha", "ACTIVE");

        assertThat(service.list(filterOn("Findable Alpha"), null, 50).data()).hasSize(1);
        assertThat(service.list(filterOn("ITCL_Q1"), null, 50).data()).hasSize(1);
        assertThat(service.list(filterOn("itcl_q1.example"), null, 50).data()).hasSize(1);
    }

    /**
     * Absent means unfiltered, and that is deliberate: a ticket raised against a
     * since-deactivated client still has to render that client's name.
     */
    @Test
    @DisplayName("omitting isActive returns deactivated clients too")
    void absentIsActiveReturnsEveryone() {
        insertClient("ITCL_ON", "IT Status Live", "ACTIVE");
        insertClient("ITCL_OFF", "IT Status Retired", "INACTIVE");

        assertThat(service.list(filterOn("IT Status"), null, 50).data()).hasSize(2);
        assertThat(service.list(
                new ClientQueryRepository.Filter("IT Status", true, null, null, null), null, 50)
                .data())
                .extracting(ClientDtos.Client::clientCode).containsExactly("ITCL_ON");
        assertThat(service.list(
                new ClientQueryRepository.Filter("IT Status", false, null, null, null), null, 50)
                .data())
                .extracting(ClientDtos.Client::clientCode).containsExactly("ITCL_OFF");
    }

    /** {@code utf8mb4_0900_ai_ci} already matches case-insensitively. */
    @Test
    @DisplayName("the support plan filter is case-insensitive through the collation")
    void supportPlanFilterIsCaseInsensitive() {
        insertClient("ITCL_SP", "IT Planned", "ACTIVE");
        jdbc.update("UPDATE clients SET support_plan = 'Premium' WHERE client_code = 'ITCL_SP'");

        assertThat(service.list(
                new ClientQueryRepository.Filter("IT Planned", null, null, "premium", null),
                null, 50).data())
                .extracting(ClientDtos.Client::clientCode).containsExactly("ITCL_SP");
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Blueprint §4B.2's rule, proven where it matters: the tickets are still
     * there after the client is deactivated.
     */
    @Test
    @DisplayName("deactivating a client hides none of its history")
    void deactivationNeverHidesHistory() {
        long clientId = insertClient("ITCL_D", "IT Deactivated", "ACTIVE");
        long projectId = anyProjectId();
        insertTicket("ITCL-9", projectId, clientId, "NEW");

        service.setStatus(clientId, false);

        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE client_id = ?", Integer.class, clientId);
        assertThat(stillThere).isEqualTo(1);

        ClientDtos.Client after = service.find(clientId).orElseThrow();
        assertThat(after.isActive()).isFalse();
        assertThat(after.openTicketCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bulk deactivate writes every client in the batch")
    void bulkStatusWritesAllOfThem() {
        long a = insertClient("ITCL_B1", "IT Bulk One", "ACTIVE");
        long b = insertClient("ITCL_B2", "IT Bulk Two", "ACTIVE");

        List<ClientDtos.Client> changed = service.setStatusBulk(List.of(a, b), false);

        assertThat(changed).extracting(ClientDtos.Client::isActive).containsExactly(false, false);
        assertThat(service.find(a).orElseThrow().isActive()).isFalse();
        assertThat(service.find(b).orElseThrow().isActive()).isFalse();
    }

    /**
     * The transaction rolls back, so a batch naming one bad id changes nothing —
     * the property the service's unit test asserts against a mock and this one
     * asserts against a database that could have committed the first write.
     */
    @Test
    @DisplayName("an unknown id in the batch leaves every other client untouched")
    void bulkStatusIsAllOrNothing() {
        long a = insertClient("ITCL_R1", "IT Rollback One", "ACTIVE");

        assertThatThrownBy(() -> service.setStatusBulk(List.of(a, 9_999_999L), false))
                .isInstanceOf(ClientService.UnknownClientException.class);

        assertThat(service.find(a).orElseThrow().isActive()).isTrue();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ClientQueryRepository.Filter filterOn(String q) {
        return new ClientQueryRepository.Filter(q, null, null, null, null);
    }

    private static ClientDtos.Client only(CursorPage<ClientDtos.Client> page) {
        assertThat(page.data()).hasSize(1);
        return page.data().getFirst();
    }

    private long insertClient(String code, String name, String status) {
        jdbc.update("INSERT INTO clients (client_code, name, status, website_domain, timezone)"
                        + " VALUES (?, ?, ?, ?, 'Asia/Kolkata')",
                code, name, status, code.toLowerCase(java.util.Locale.ROOT) + ".example");
        return jdbc.queryForObject(
                "SELECT id FROM clients WHERE client_code = ?", Long.class, code);
    }

    private void insertContact(long clientId, String name, boolean primary) {
        jdbc.update("INSERT INTO client_contacts (client_id, name, email, is_primary,"
                        + " receives_mail, portal_access, is_active) VALUES (?, ?, ?, ?, 1, 0, 1)",
                clientId, name, name.replace(' ', '.') + "@itcl.example", primary ? 1 : 0);
    }

    private void insertTicket(String code, long projectId, long clientId, String status) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level,"
                        + " status, client_id, date_reported)"
                        + " VALUES (?, ?, ?, 'MEDIUM', 'MEDIUM', ?, ?, UTC_TIMESTAMP(6))",
                code, projectId, "Fixture " + code, status, clientId);
    }

    /** Any seeded project — this suite is about clients, not about which one. */
    private long anyProjectId() {
        return allProjectIds().getFirst();
    }

    private List<Long> allProjectIds() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM projects ORDER BY id", Long.class);
        assertThat(ids)
                .as("the fixture corpus seeds projects; a ticket needs one to hang off")
                .isNotEmpty();
        return ids;
    }
}
