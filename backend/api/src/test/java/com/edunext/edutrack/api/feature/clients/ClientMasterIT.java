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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
 *
 * <h2>B-026 · five of these tests were failing, and the reason was one helper</h2>
 *
 * <p>They took their project id from {@code allProjectIds()}, whose assertion
 * message read "the fixture corpus seeds projects" — and it does not, not here.
 * B-007's corpus is a profile-gated {@code ApplicationRunner} (`local,fixtures`),
 * no migration inserts a {@code projects} row, and no test in this module
 * activates either profile, so the table is empty for every run of this suite and
 * the helper's own assertion failed before the test it served could say anything.
 * Not intermittent and not environmental: five tests that could never have
 * passed, in a file whose other eighteen always did, which is exactly the shape a
 * suite hides.
 *
 * <p>Repaired rather than reported, because the fix is {@code insertProject} and
 * this is the same stream's file. A test that needs a project now makes one and
 * cleans it up, which also makes each of them independent of what any other
 * suite left behind.
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
    ClientWriteService writes;

    /** B-027 · the child grid's own service. */
    @Autowired
    ClientContactService contacts;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Saves spelling out the factory in a dozen assertions.
     *
     * <p>The raw {@code List} is AssertJ's own declaration
     * ({@code InstanceOfAssertFactories.list} returns
     * {@code InstanceOfAssertFactory<List, ListAssert<ELEMENT>>}), not a
     * shortcut taken here — parameterising it does not compile.
     */
    @SuppressWarnings("rawtypes")
    private static final org.assertj.core.api.InstanceOfAssertFactory<
            java.util.List, org.assertj.core.api.ListAssert<ClientDtos.Contact>> CONTACTS =
            org.assertj.core.api.InstanceOfAssertFactories.list(ClientDtos.Contact.class);

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
        jdbc.update("DELETE FROM client_projects WHERE project_id IN"
                + " (SELECT id FROM projects WHERE project_code LIKE 'ITP%')");
        jdbc.update("DELETE FROM projects WHERE project_code LIKE 'ITP%'");
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
        long projectId = insertProject("ITPT", "IT Ticket Host");
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
                insertProject("ITPZ", "IT Zone Host"), clientId);

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
        List<Long> projectIds = List.of(
                insertProject("ITPA", "IT Fan-out A"),
                insertProject("ITPB", "IT Fan-out B"),
                insertProject("ITPC", "IT Fan-out C"));
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
        long projectId = insertProject("ITPF", "IT Filter Target");
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
        assertThat(contacts.list(clientId, false)).get().asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(ClientDtos.Contact.class))
                .hasSize(2);
    }

    // ------------------------------------------------------------------
    // B-027 · the client_contacts child grid
    // ------------------------------------------------------------------

    /**
     * The whole point of the parameter, against the real predicate.
     *
     * <p>A removed contact must disappear from the picker's read and stay in the
     * grid's. Getting this backwards in either direction is invisible in a unit
     * test — a mocked repository answers whatever it was told — and the
     * consequences are opposite: the wrong default offers a departed contact on
     * every new ticket, and the wrong grid read makes them vanish with no way to
     * tell "removed" from "never existed".
     */
    @Test
    @DisplayName("includeInactive is what separates the grid's read from the picker's")
    void includeInactiveSeparatesTheTwoReads() {
        long clientId = insertClient("ITCL_K1", "IT Contact Visibility", "ACTIVE");
        long stays = addContact(clientId, "Live Person", "live@itcl.example", false);
        long goes = addContact(clientId, "Departed Person", "gone@itcl.example", false);

        assertThat(contacts.remove(clientId, goes)).isTrue();

        assertThat(contacts.list(clientId, false)).get().asInstanceOf(CONTACTS)
                .extracting(ClientDtos.Contact::id)
                .as("the picker must stop offering somebody who has left the client")
                .containsExactly(stays);

        assertThat(contacts.list(clientId, true)).get().asInstanceOf(CONTACTS)
                .extracting(ClientDtos.Contact::id, ClientDtos.Contact::isActive)
                .as("the grid renders them, greyed — live rows first")
                .containsExactly(tuple(stays, true), tuple(goes, false));
    }

    /**
     * The row survives the removal, and that is the entire reason removal is a
     * deactivation.
     *
     * <p>{@code tickets.client_contact_id} is a foreign key into
     * {@code client_contacts} <b>without</b> a cascade. A real {@code DELETE}
     * fails as a constraint violation naming a MySQL index; "fixing" that with a
     * cascade would rewrite who a historical ticket says reported it. Asserted
     * against {@code information_schema} rather than left in a comment, the way
     * B-020 asserted the task-type foreign keys.
     */
    @Test
    @DisplayName("a removed contact's row survives, and the FK is why")
    void removalDeactivatesBecauseTheForeignKeyIsRestrictive() {
        long clientId = insertClient("ITCL_K2", "IT Contact FK", "ACTIVE");
        long contactId = addContact(clientId, "Reporter", "reporter@itcl.example", true);

        assertThat(contacts.remove(clientId, contactId)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_contacts WHERE id = ?", Integer.class, contactId))
                .as("the row is still there — a ticket may point at it")
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND CONSTRAINT_NAME = 'fk_tickets_client_contact'
                """, String.class))
                .as("a cascade here would silently rewrite a historical ticket's reporter")
                .isEqualTo("NO ACTION");
    }

    /**
     * The removal clears {@code is_primary} in the same statement, and it has to.
     *
     * <p>{@code primaryContacts} filters on {@code is_active = 1} while
     * {@code demoteOtherPrimaries} does not, so a removed contact keeping its
     * flag would leave the grid showing no primary while a later promotion still
     * had a row to demote — two answers to "who is the primary" that disagree.
     */
    @Test
    @DisplayName("removing the primary clears the flag as well as the row's activity")
    void removingThePrimaryClearsTheFlag() {
        long clientId = insertClient("ITCL_K3", "IT Primary Removal", "ACTIVE");
        long contactId = addContact(clientId, "Only Primary", "only@itcl.example", true);

        assertThat(contacts.remove(clientId, contactId)).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT is_primary FROM client_contacts WHERE id = ?", Boolean.class, contactId))
                .isFalse();

        // B-028's gate, reported not enforced: the client is now unselectable and
        // the save that got it here was allowed.
        assertThat(service.findDetail(clientId)).get()
                .extracting(ClientDtos.ClientDetail::hasPrimaryContact)
                .isEqualTo(false);
    }

    /**
     * Promoting demotes, in one transaction — the single-writer rule the schema
     * cannot assert, because MySQL has no partial unique index.
     */
    @Test
    @DisplayName("a new primary demotes the previous one")
    void promotingDemotesThePrevious() {
        long clientId = insertClient("ITCL_K4", "IT Primary Handover", "ACTIVE");
        long first = addContact(clientId, "First Primary", "first@itcl.example", true);
        long second = addContact(clientId, "Second Person", "second@itcl.example", false);

        contacts.edit(clientId, second, request("Second Person", "second@itcl.example", true));

        assertThat(contacts.list(clientId, false)).get().asInstanceOf(CONTACTS)
                .filteredOn(ClientDtos.Contact::isPrimary)
                .extracting(ClientDtos.Contact::id)
                .as("exactly one primary, and it is the one just promoted")
                .containsExactly(second);
        assertThat(jdbc.queryForObject(
                "SELECT is_primary FROM client_contacts WHERE id = ?", Boolean.class, first))
                .isFalse();
    }

    /**
     * <b>An edit cannot resurrect a removed contact.</b>
     *
     * <p>{@code is_active} is deliberately not in {@code update}'s statement,
     * and nothing about reading either file establishes that — which is why this
     * exists. B-017 had to pin exactly this between {@code project_members}' two
     * writers with two named regression tests; this is the same claim for the
     * two writers of {@code client_contacts}, and it fails if anybody widens the
     * {@code UPDATE}.
     */
    @Test
    @DisplayName("editing a removed contact corrects it without bringing them back")
    void anEditDoesNotReactivate() {
        long clientId = insertClient("ITCL_K5", "IT No Resurrection", "ACTIVE");
        long contactId = addContact(clientId, "Misspelt Nmae", "typo@itcl.example", false);
        assertThat(contacts.remove(clientId, contactId)).isTrue();

        Optional<ClientDtos.Contact> edited =
                contacts.edit(clientId, contactId, request("Correct Name", "typo@itcl.example", false));

        assertThat(edited).get()
                .extracting(ClientDtos.Contact::name, ClientDtos.Contact::isActive)
                .as("the correction lands; somebody who returns to the client is added again")
                .containsExactly("Correct Name", false);
    }

    /**
     * The uniqueness check agrees with {@code utf8mb4_0900_ai_ci}, which is a
     * claim about the collation and cannot be made against a mock.
     *
     * <p>B-013 made the same assertion for the resource form's username check and
     * gave the reason: a case-sensitive check in Java would pass the row to the
     * index instead, and the index refuses with a constraint name rather than the
     * field-keyed message the form displays on the input. Here there is no index
     * at all — {@code ix_client_contacts_email} is deliberately non-unique — so
     * the service is the <em>only</em> thing refusing it.
     */
    @Test
    @DisplayName("a duplicate email is refused case-insensitively, within the client only")
    void duplicateEmailIsRefusedWithinTheClient() {
        long clientId = insertClient("ITCL_K6", "IT Duplicate Email", "ACTIVE");
        long other = insertClient("ITCL_K7", "IT Other Client", "ACTIVE");
        addContact(clientId, "Sara Kapoor", "sara@itcl.example", true);

        assertThatThrownBy(() -> contacts.add(clientId,
                request("Sara K", "SARA@ITCL.EXAMPLE", false)))
                .isInstanceOf(ClientContactService.ContactValidationException.class)
                .hasMessageContaining("Sara Kapoor");

        // The same address at a *different* client is legitimate — a consultant
        // retained by both. `ix_client_contacts_email` is non-unique precisely so
        // D-039 can take the set and disambiguate on `website_domain`.
        assertThat(contacts.add(other, request("Sara Kapoor", "sara@itcl.example", true)))
                .isPresent();
    }

    /**
     * A removal frees the address, which is the ordinary case rather than an
     * edge: somebody leaves, and the person who replaces them inherits the
     * mailbox.
     */
    @Test
    @DisplayName("a removed contact's email can be used again")
    void aRemovedContactsEmailIsFreed() {
        long clientId = insertClient("ITCL_K8", "IT Recycled Email", "ACTIVE");
        long first = addContact(clientId, "Leaver", "desk@itcl.example", false);
        assertThat(contacts.remove(clientId, first)).isTrue();

        assertThat(contacts.add(clientId, request("Successor", "desk@itcl.example", false)))
                .as("refusing this would burn the address forever on a removal")
                .isPresent();
    }

    /**
     * The nesting is the 404, and it is a real predicate rather than a comment.
     *
     * <p>Without {@code client_id} on the read, an edit aimed at a contact id
     * belonging to another client would land on that client's row — a write
     * against a resource the path never named.
     */
    @Test
    @DisplayName("a contact id under the wrong client is 404 for every verb")
    void contactsAreScopedToTheirClient() {
        long owner = insertClient("ITCL_K9", "IT Contact Owner", "ACTIVE");
        long stranger = insertClient("ITCL_KA", "IT Contact Stranger", "ACTIVE");
        long contactId = addContact(owner, "Owned", "owned@itcl.example", false);

        assertThat(contacts.edit(stranger, contactId, request("Hijacked", "x@itcl.example", false)))
                .isEmpty();
        assertThat(contacts.remove(stranger, contactId)).isFalse();

        assertThat(contacts.list(owner, false)).get().asInstanceOf(CONTACTS)
                .extracting(ClientDtos.Contact::name)
                .as("neither call touched the row")
                .containsExactly("Owned");
    }

    /**
     * Every contact write moves the client's {@code ETag}, and the S-33 form
     * depends on knowing it.
     *
     * <p>{@code contactCount} and {@code hasPrimaryContact} are fields of
     * {@code ClientDetail} and the tag is that record's {@code hashCode}. If the
     * frontend did not invalidate the client after a contact write, the admin's
     * next Save on the Identity tab would come back 412 about a change they made
     * themselves seconds earlier — which is why `contactQueries.ts` invalidates
     * both.
     */
    @Test
    @DisplayName("adding a contact changes the client's detail, and so its ETag")
    void aContactWriteMovesTheClientsTag() {
        long clientId = insertClient("ITCL_KB", "IT Tag Movement", "ACTIVE");
        ClientDtos.ClientDetail before = service.findDetail(clientId).orElseThrow();

        addContact(clientId, "New Contact", "tag@itcl.example", true);

        ClientDtos.ClientDetail after = service.findDetail(clientId).orElseThrow();
        assertThat(after.contactCount()).isEqualTo(before.contactCount() + 1);
        assertThat(after.hasPrimaryContact()).isTrue();
        assertThat(after.hashCode()).isNotEqualTo(before.hashCode());
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
        long projectId = insertProject("ITPD", "IT History Host");
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
    // B-026 · S-33's create and edit
    // ------------------------------------------------------------------

    /**
     * The claim a mocked repository cannot make: that every S-33 field survives
     * the round trip through real columns.
     *
     * <p>Twenty-five fields, four of which only exist because
     * {@code V20260816_1030} added them. A column missed from the {@code INSERT},
     * a type MySQL widens or narrows, a {@code JSON} array Hibernate cannot
     * serialise — none of those fail a unit test that never leaves the JVM.
     */
    @Test
    @DisplayName("every S-33 field round-trips through real columns")
    void everyFieldSurvivesTheRoundTrip() {
        long id = writes.create(fullRequest("ITCL_FULL", "IT Full Client")).id();

        ClientDtos.ClientDetail saved = service.findDetail(id).orElseThrow();

        assertThat(saved.clientCode()).isEqualTo("ITCL_FULL");
        assertThat(saved.shortName()).isEqualTo("Full");
        assertThat(saved.logoUrl()).isEqualTo("https://full.example/logo.png");
        assertThat(saved.industry()).isEqualTo("Retail");
        assertThat(saved.status()).isEqualTo("PROSPECT");
        assertThat(saved.domain()).isEqualTo("full.example");
        assertThat(saved.primaryEmail()).isEqualTo("hello@full.example");
        assertThat(saved.supportEmail()).isEqualTo("support@full.example");
        assertThat(saved.phone()).isEqualTo("+91 98200 11111");
        assertThat(saved.addressLine1()).isEqualTo("14 Linking Road");
        assertThat(saved.addressLine2()).isEqualTo("Bandra West");
        assertThat(saved.city()).isEqualTo("Mumbai");
        assertThat(saved.state()).isEqualTo("Maharashtra");
        assertThat(saved.country()).isEqualTo("India");
        assertThat(saved.postalCode()).isEqualTo("400050");
        assertThat(saved.timezone()).isEqualTo("Europe/London");
        assertThat(saved.contractStart()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(saved.contractEnd()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(saved.supportPlan()).isEqualTo("ENTERPRISE");
        assertThat(saved.billingReference()).isEqualTo("PO-2025-0142");
        assertThat(saved.billingEmail()).isEqualTo("accounts@full.example");
        assertThat(saved.notes()).isEqualTo("Quarterly review every January.");
        assertThat(saved.tags()).containsExactly("retail", "strategic");
    }

    /**
     * <b>The named regression test for the defect the round-trip above found.</b>
     *
     * <p>{@code contract_start = '2025-04-01'} written through JPA and read back
     * through JPA came out as <b>2025-03-31</b>, with the JVM at UTC, the
     * connection at UTC, the MySQL session at {@code +00:00} and the raw column
     * holding the right value the whole time — so it is not a time-zone
     * misconfiguration, it is the {@code getDate}/{@code Calendar} path
     * {@code hibernate.jdbc.time_zone} forces Hibernate onto.
     *
     * <p>This asserts the two readings against each other on one connection, so
     * the claim in {@code ClientQueryRepository.contractDates}' javadoc is
     * measured rather than remembered — and so that the day the underlying
     * setting is fixed for everybody, the discrepancy disappearing here is what
     * says the workaround can go.
     *
     * <p><b>{@code Holiday.holidayDate} has the same bug and is not fixed here.</b>
     * An org holiday a day out means {@code WorkingHoursService} treats the wrong
     * day as non-working, and every SLA crossing it is wrong. Flagged for B-023's
     * follow-up and for Stream A, who own the property.
     */
    @Test
    @DisplayName("a DATE survives the read — getDate loses a day where getObject does not")
    void contractDatesAreNotReadADayEarly() {
        long id = writes.create(fullRequest("ITCL_DT", "IT Dates")).id();

        assertThat(jdbc.queryForObject(
                "SELECT CAST(contract_start AS CHAR) FROM clients WHERE id = ?", String.class, id))
                .as("the write is not the problem; the stored value is correct")
                .isEqualTo("2025-04-01");

        assertThat(jdbc.queryForObject(
                "SELECT contract_start FROM clients WHERE id = ?", LocalDate.class, id))
                .as("getObject(LocalDate.class) is exact — this is the path "
                        + "ClientQueryRepository.contractDates takes")
                .isEqualTo(LocalDate.of(2025, 4, 1));

        assertThat(service.findDetail(id).orElseThrow().contractStart())
                .as("and the detail read reports the same day the contract does")
                .isEqualTo(LocalDate.of(2025, 4, 1));
    }

    /**
     * {@code tags} is a MySQL {@code JSON} column mapped through Hibernate's own
     * JSON type, and {@code ck_clients_tags} constrains its <em>shape</em>. Both
     * halves are database behaviour, so both are asserted here rather than
     * against a mock that would echo back whatever it was handed.
     */
    @Test
    @DisplayName("tags are stored as a real JSON array, and the CHECK holds the shape")
    void tagsAreStoredAsJson() {
        long id = writes.create(request("ITCL_TAG", "IT Tagged",
                b -> b.tags(List.of("retail", "vip")))).id();

        assertThat(jdbc.queryForObject(
                "SELECT JSON_TYPE(tags) FROM clients WHERE id = ?", String.class, id))
                .isEqualTo("ARRAY");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE clients SET tags = '{\"a\":1}' WHERE id = ?", id))
                .as("ck_clients_tags is the backstop for the writers that do not go "
                        + "through the service — B-035's import, and a hand-run UPDATE")
                .isInstanceOf(Exception.class);
    }

    /**
     * {@code ck_clients_status} lands at the column's first writer of the third
     * value — B-016's rule. The service refuses a bad status before the database
     * sees it, so this asserts the constraint directly: a hand-run UPDATE is the
     * writer it exists for.
     */
    @Test
    @DisplayName("ck_clients_status permits exactly the three §4B.2 states")
    void statusVocabularyIsHeldByTheDatabase() {
        long id = insertClient("ITCL_ST", "IT Status", "ACTIVE");

        for (String status : ClientStatus.CODES) {
            jdbc.update("UPDATE clients SET status = ? WHERE id = ?", status, id);
        }

        assertThatThrownBy(() ->
                jdbc.update("UPDATE clients SET status = 'ARCHIVED' WHERE id = ?", id))
                .as("a fourth value means a migration and a contract change, "
                        + "not an UPDATE somebody runs")
                .isInstanceOf(Exception.class);
    }

    /**
     * The claim {@code ClientWriteRepository.findConflictingCode} makes about the
     * collation: {@code client_code} collates {@code utf8mb4_0900_ai_ci}, so
     * MySQL already matches {@code itcl_dup} against {@code ITCL_DUP} — and a
     * case-sensitive check in Java would have passed the second one to the index,
     * which refuses with a constraint name rather than the field-keyed 409 the
     * form displays on the input. B-013 asserted the same thing for the resource
     * form.
     */
    @Test
    @DisplayName("a duplicate code is refused case-insensitively, agreeing with the collation")
    void duplicateCodeAgreesWithTheCollation() {
        writes.create(request("ITCL_DUP", "IT Duplicate", b -> b));

        assertThatThrownBy(() -> writes.create(request("itcl_dup", "IT Duplicate Again", b -> b)))
                .isInstanceOf(ClientWriteService.ClientValidationException.class)
                .satisfies(e -> assertThat(
                        ((ClientWriteService.ClientValidationException) e).errors())
                        .containsOnlyKeys("clientCode"));
    }

    /**
     * S-33 submits the whole form on every save, so without {@code id <> ?} in
     * the conflict query every ordinary edit would 409 on the code the client
     * already holds — the mirror of the {@code u.id <> ?} B-013 documents on the
     * resource form, and the sort of thing that is right in a mock and wrong in
     * SQL.
     */
    @Test
    @DisplayName("re-sending a client's own code is not a conflict")
    void ownCodeIsNotAConflict() {
        long id = writes.create(request("ITCL_OWN", "IT Own Code", b -> b)).id();

        ClientDtos.ClientDetail saved = writes
                .update(id, request("ITCL_OWN", "IT Own Code Renamed", b -> b))
                .orElseThrow();

        assertThat(saved.name()).isEqualTo("IT Own Code Renamed");
    }

    /**
     * The mapping is a wholesale replace, and {@code is_default} is set in the
     * same pass — so a client cannot be left with a default pointing at a project
     * it is no longer mapped to.
     */
    @Test
    @DisplayName("the project mapping is replaced, default and all")
    void projectMappingIsReplaced() {
        // This suite's own projects rather than the corpus's. `projects` is
        // seeded by no migration — B-007's fixture corpus is profile-gated to
        // `local,fixtures` and no test activates it — so a claim about the
        // mapping has to bring the rows it maps to.
        long first = insertProject("ITP1", "IT Project One");
        long second = insertProject("ITP2", "IT Project Two");

        long id = writes.create(request("ITCL_MAP", "IT Mapped",
                b -> b.projectIds(List.of(first)).defaultProjectId(first))).id();

        assertThat(service.findDetail(id).orElseThrow().defaultProjectId()).isEqualTo(first);

        writes.update(id, request("ITCL_MAP", "IT Mapped",
                b -> b.projectIds(List.of(second)).defaultProjectId(second)));

        ClientDtos.ClientDetail after = service.findDetail(id).orElseThrow();
        assertThat(after.projects()).extracting(ClientDtos.ProjectRef::id).containsExactly(second);
        assertThat(after.defaultProjectId()).isEqualTo(second);
    }

    /**
     * B-035's import writes client rows and never touches project associations.
     * A null read as "unmap" would have every import silently detach every client
     * it updated from every project — surfacing much later as a ticket form whose
     * client dropdown has gone empty.
     */
    @Test
    @DisplayName("an absent projectIds leaves the mapping alone; an empty one clears it")
    void absentAndEmptyMappingsDiffer() {
        long project = insertProject("ITP3", "IT Project Three");
        long id = writes.create(request("ITCL_ABS", "IT Absent",
                b -> b.projectIds(List.of(project)))).id();

        writes.update(id, request("ITCL_ABS", "IT Absent", b -> b.projectIds(null)));
        assertThat(service.findDetail(id).orElseThrow().projects()).hasSize(1);

        writes.update(id, request("ITCL_ABS", "IT Absent", b -> b.projectIds(List.of())));
        assertThat(service.findDetail(id).orElseThrow().projects()).isEmpty();
    }

    /**
     * B-028's gate, reported off the detail read. Enforcing it belongs on the
     * ticket create path, where a caller can act on it; what this task owes that
     * decision is an answer the form can state.
     */
    @Test
    @DisplayName("hasPrimaryContact tracks the live primary contact")
    void primaryContactIsReported() {
        long id = writes.create(request("ITCL_PC", "IT Primary", b -> b)).id();

        assertThat(service.findDetail(id).orElseThrow().hasPrimaryContact()).isFalse();

        insertContact(id, "Sara Kapoor", false);
        assertThat(service.findDetail(id).orElseThrow().hasPrimaryContact()).isFalse();
        assertThat(service.findDetail(id).orElseThrow().contactCount()).isEqualTo(1);

        insertContact(id, "Dev Patel", true);
        assertThat(service.findDetail(id).orElseThrow().hasPrimaryContact()).isTrue();
        assertThat(service.findDetail(id).orElseThrow().contactCount()).isEqualTo(2);
    }

    /**
     * The consequence of {@code isActive} deriving as "not INACTIVE": setting the
     * state a prospect already holds must not rewrite it to {@code ACTIVE}, or
     * S-32's bulk Activate turns a shortlist of prospects into contracted clients
     * with nothing recording that it happened.
     */
    @Test
    @DisplayName("activating a prospect through the status setter leaves it a prospect")
    void activatingAProspectLeavesItAProspect() {
        long id = insertClient("ITCL_PR", "IT Prospect", "PROSPECT");

        service.setStatus(id, true);
        assertThat(storedStatus(id)).isEqualTo("PROSPECT");

        service.setStatusBulk(List.of(id), true);
        assertThat(storedStatus(id)).isEqualTo("PROSPECT");

        service.setStatus(id, false);
        assertThat(storedStatus(id)).isEqualTo("INACTIVE");
    }

    /**
     * The reference check reaching a real table.
     *
     * <p>An unknown id rather than a deactivated one, and the substitution is
     * forced rather than chosen: nothing seeds {@code users} either, so there is
     * no resource here to deactivate. The "has left" half is
     * {@code ClientWriteServiceTest.deactivatedAccountManager}, which is about
     * the service's reading of {@code is_active} and needs no database to be
     * true. What only a database can show is that the lookup finds nothing when
     * there is nothing to find — a mocked repository would answer whatever it
     * was told.
     */
    @Test
    @DisplayName("an account manager who does not exist is refused")
    void unknownAccountManagerIsRefused() {
        assertThatThrownBy(() -> writes.create(request("ITCL_AM", "IT Manager",
                b -> b.accountManagerId(9_999_999L))))
                .isInstanceOf(ClientWriteService.ClientValidationException.class)
                .satisfies(e -> assertThat(
                        ((ClientWriteService.ClientValidationException) e).errors())
                        .containsKey("accountManagerId"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private String storedStatus(long clientId) {
        return jdbc.queryForObject(
                "SELECT status FROM clients WHERE id = ?", String.class, clientId);
    }

    /** Every S-33 field populated, so the round-trip test has something to lose. */
    private static ClientDtos.ClientWriteRequest fullRequest(String code, String name) {
        return new ClientDtos.ClientWriteRequest(
                code, name, "Full", "https://full.example/logo.png", "Retail", "PROSPECT",
                "https://www.Full.Example/support",
                "hello@full.example", "support@full.example", "+91 98200 11111",
                "14 Linking Road", "Bandra West", "Mumbai", "Maharashtra", "India", "400050",
                "Europe/London",
                null, LocalDate.of(2025, 4, 1), LocalDate.of(2027, 3, 31), "enterprise",
                "PO-2025-0142", "accounts@full.example",
                "Quarterly review every January.", List.of("retail", "strategic"),
                null, null, null);
    }

    private static ClientDtos.ClientWriteRequest request(
            String code, String name, java.util.function.UnaryOperator<RequestBuilder> customise) {

        return customise.apply(new RequestBuilder(code, name)).build();
    }

    /** Enough of a builder for the fields these tests vary; the rest stay null. */
    private static final class RequestBuilder {
        private final String code;
        private final String name;
        private Long accountManagerId;
        private List<String> tags;
        private List<Long> projectIds;
        private Long defaultProjectId;

        RequestBuilder(String code, String name) {
            this.code = code;
            this.name = name;
        }

        RequestBuilder accountManagerId(Long v) {
            this.accountManagerId = v;
            return this;
        }

        RequestBuilder tags(List<String> v) {
            this.tags = v;
            return this;
        }

        RequestBuilder projectIds(List<Long> v) {
            this.projectIds = v;
            return this;
        }

        RequestBuilder defaultProjectId(Long v) {
            this.defaultProjectId = v;
            return this;
        }

        ClientDtos.ClientWriteRequest build() {
            return new ClientDtos.ClientWriteRequest(
                    code, name, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    accountManagerId, null, null, null, null, null, null, tags,
                    projectIds, defaultProjectId, null);
        }
    }

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

    /**
     * B-027 · adds a contact <b>through the service</b> and returns its id.
     *
     * <p>Deliberately not raw SQL, unlike {@link #insertContact} beside it. These
     * tests are about what the service does — the demotion, the uniqueness check,
     * the deactivation — so a fixture that bypassed it would be setting up the
     * state under test with the code under test's competitor.
     */
    private long addContact(long clientId, String name, String email, boolean primary) {
        return contacts.add(clientId, request(name, email, primary))
                .orElseThrow(() -> new AssertionError(
                        "the fixture contact was not created for client " + clientId))
                .id();
    }

    private static ClientDtos.ContactWriteRequest request(String name,
                                                          String email,
                                                          boolean primary) {
        return new ClientDtos.ContactWriteRequest(name, null, email, null, primary, null, null);
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

    /**
     * B-026 · a project this suite owns.
     *
     * <p>Nothing seeds {@code projects}: A-003 creates the table and no migration
     * inserts a row, and B-007's corpus is a profile-gated {@code ApplicationRunner}
     * (`local,fixtures`) that no test activates. A test that needs a project has
     * to make one.
     */
    private long insertProject(String code, String name) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code, name);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE project_code = ?", Long.class, code);
    }

}
