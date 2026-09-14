package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-102 · the client master against real MySQL.
 *
 * <p>What earns a container, given that {@code ObClientWriteServiceTest} covers
 * every decision made in Java:
 *
 * <ol>
 *   <li><b>The atomicity of the create.</b> Five tables and C-103's
 *       instantiation service in one transaction — the property the contract
 *       calls "the module's widest side effect" cannot be asserted against
 *       mocks.</li>
 *   <li><b>That {@code uq_ob_clients_pan_blind} fires.</b> The service check is
 *       the good error message; the index is the thing that is true under a
 *       race. A-113's {@code PanStorageIT} proves the constraint in isolation;
 *       this proves the feature actually writes the column it guards.</li>
 *   <li><b>The scope predicate in SQL.</b> A-112's rule is expressed twice —
 *       once as a JPA specification, once as the SQL in {@link ObClientScope} —
 *       and only one of them is exercised by this feature.</li>
 *   <li><b>That the SQL and Java RAG formulas agree.</b>
 *       {@code ObStepRag.worstOverSteps} exists because the list has to filter
 *       in the {@code WHERE} clause; its own javadoc names this test as the
 *       thing standing between that and two formulas that drift.</li>
 * </ol>
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, for
 * the reason {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObClientsIT {

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

    private static final LocalDate BOARDED = LocalDate.of(2026, 9, 7);

    @Autowired
    ObClientWriteService writes;

    @Autowired
    ObClientService reads;

    /**
     * The purchases panel's own service, used by {@link #board} rather than
     * `clientWrites` alone.
     *
     * <p>A client's products are no longer part of boarding — the Clients master
     * takes four fields — so a fixture that needs journeys has to buy something,
     * and this is the operation that records a purchase, provisions its project
     * and instantiates the journeys.
     */
    @Autowired
    ObApplicationService applications;

    @Autowired
    JdbcTemplate jdbc;

    private long ayush;
    private long divyansh;
    private long product;
    private long secondProduct;

    private ObClientScope admin;
    private ObClientScope salesAyush;
    private ObClientScope salesDivyansh;

    @BeforeEach
    void seed() {
        // Children first, then their parents — every one of these references
        // the row below it.
        jdbc.update("DELETE FROM ob_journey_steps WHERE journey_id IN "
                + "(SELECT id FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %'))");
        jdbc.update("DELETE FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        // V20260911_1800 · the engagement a purchase now provisions.
        // `ob_journeys.project_id` points at it and `fk_ob_projects_client` is
        // RESTRICT, so this sits between the journeys above and the clients
        // below — the same "children first" rule as the rest of this block.
        jdbc.update("DELETE FROM ob_projects WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_requirements WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        // The credential mail the portal-login tests queue. `fk_ob_outbox_client`
        // and `fk_ob_outbox_recipient_contact` are both RESTRICT, so this has to
        // go before the contacts and the clients below it.
        jdbc.update("DELETE FROM ob_notification_outbox WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        // The accounts those tests create. `fk_client_accounts_ob_client` is
        // RESTRICT too — deliberately, per V20260905_1630 — so a login left
        // behind by one test would refuse the next test's DELETE rather than
        // cascade quietly. `client_credential_tokens` cascades from here.
        jdbc.update("DELETE FROM client_accounts WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_contacts WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        // B-109 · a client's prerequisite snapshot is a child of ob_clients
        // (fk_ob_client_prereq_tasks_client is RESTRICT), so it has to go
        // before the DELETE below, not after — the same "children first"
        // rule this whole block is already following.
        jdbc.update("DELETE FROM ob_client_prereq_tasks WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_prereqs WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'IT %'");
        jdbc.update("DELETE FROM ob_journey_template_steps WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'IT %')");
        // V20260911_1630's stage groups sit between the steps and the template,
        // and `fk_ob_template_stages_template` is RESTRICT — so they go after
        // the steps that reference them and before the template they reference.
        jdbc.update("DELETE FROM ob_journey_template_stages WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'IT %'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_%'");
        // This class owns the org-wide prerequisites master exclusively for
        // the life of this container, so it is wiped and republished every
        // test rather than named-and-filtered like the rows above — there is
        // no owner column to filter it by. Safe now that no client-side row
        // still references a template task (both deletes above ran first).
        // Before the `users` delete below: `published_by`/`created_by` on a
        // version are FKs into it.
        jdbc.update("DELETE FROM ob_prereq_template_tasks");
        jdbc.update("DELETE FROM ob_prereq_template_versions");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obclient_%'");

        ayush = insertUser("it_obclient_ayush");
        divyansh = insertUser("it_obclient_divyansh");

        product = insertProduct("IT_ERP", "IT ERP");
        secondProduct = insertProduct("IT_LMS", "IT LMS");
        insertTemplate(product, "IT ERP Onboarding");
        insertTemplate(secondProduct, "IT LMS Onboarding");
        insertPrereqMaster();

        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);
        salesAyush = new ObClientScope(ObClientScope.OB_SALES, ayush);
        salesDivyansh = new ObClientScope(ObClientScope.OB_SALES, divyansh);
    }

    // ── the create, whole or not at all ─────────────────────────────────────

    @Test
    @DisplayName("one request boards the client, its SPOCs, its purchases, its requirements and a locked journey per product")
    void oneRequestWritesEverything() {
        ObClientDtos.ObClientDetail created = board("IT Horizon Academy", List.of(product, secondProduct));

        assertThat(created.name()).isEqualTo("IT Horizon Academy");
        assertThat(created.contacts()).hasSize(1);
        assertThat(created.applications()).hasSize(2);
        // B-106 · rows rather than strings, in the order they were entered,
        // each one having been through §3.9's allow-list on the way in.
        assertThat(created.requirements())
                .extracting(ObClientDtos.ObRequirement::bodyText)
                .containsExactly("Single sign-on", "Data migration");
        assertThat(created.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 1);
        assertThat(created.requirements()).allSatisfy(requirement -> {
            assertThat(requirement.isMet()).isFalse();
            assertThat(requirement.metAt()).isNull();
            assertThat(requirement.createdBy()).isNotNull();
        });
        assertThat(created.journeys()).hasSize(2);
        assertThat(created.journeys()).allSatisfy(journey ->
                assertThat(journey.gateStatus()).isEqualTo("LOCKED"));

        // Plan §5.2: steps visible from day one, clocks dead. Every step is
        // PENDING and no due date has been set, so nothing can breach before
        // the client has been asked for anything.
        assertThat(created.journeys().getFirst().steps()).hasSize(2);
        assertThat(created.journeys().getFirst().steps())
                .allSatisfy(step -> assertThat(step.status()).isEqualTo("PENDING"));
        assertThat(created.journeys().getFirst().percentComplete()).isZero();
        assertThat(created.journeys().getFirst().totalTatDays()).isEqualTo(5);
        // C-120: a dead clock has consumed nothing — genuinely 0.0, not null.
        assertThat(created.journeys().getFirst().utilizedHours()).isEqualTo(0.0);
    }

    /**
     * The atomicity claim, tested at the one point where it is not obvious:
     * the journeys are instantiated last, so a failure there has to unwind four
     * tables' worth of writes.
     */
    @Test
    @DisplayName("a product with no published template boards nothing at all")
    void productWithoutTemplateRollsEverythingBack() {
        jdbc.update("UPDATE ob_journey_templates SET is_active = 0 WHERE product_id = ?", product);

        assertThatThrownBy(() -> board("IT Rollback Academy", List.of(product)))
                .isInstanceOf(ProductWithoutTemplateException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE name = 'IT Rollback Academy'"))
                .isZero();
    }

    // ── B-109 · the prerequisites instance ──────────────────────────────────

    /**
     * The half of the atomic create that a mock cannot prove: two more tables
     * ({@code ob_client_prereqs}, {@code ob_client_prereq_tasks}), written by a
     * service in a different package, inside the one transaction {@code
     * ObClientWriteServiceTest} cannot open a container for.
     */
    @Test
    @DisplayName("boarding a client snapshots the active master onto the client's own checklist")
    void createSnapshotsThePrereqMaster() {
        ObClientDtos.ObClientDetail created = board("IT Checklist Academy", List.of(product));

        assertThat(count("SELECT COUNT(*) FROM ob_client_prereqs WHERE ob_client_id = "
                + created.id())).isEqualTo(1);

        List<Object[]> tasks = jdbc.query(
                "SELECT title, is_mandatory FROM ob_client_prereq_tasks "
                        + "WHERE ob_client_id = ? ORDER BY sequence",
                (rs, rowNum) -> new Object[]{rs.getString("title"), rs.getBoolean("is_mandatory")},
                created.id());
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0)[0]).isEqualTo("Signed MSA");
        assertThat(tasks.get(0)[1]).isEqualTo(true);
        assertThat(tasks.get(1)[0]).isEqualTo("Data export template");
        assertThat(tasks.get(1)[1]).isEqualTo(false);
    }

    /** The same rollback argument {@code productWithoutTemplateRollsEverythingBack} makes, one guard later. */
    @Test
    @DisplayName("nothing published on OB-14 boards no client at all")
    void noPrereqMasterRollsEverythingBack() {
        jdbc.update("UPDATE ob_prereq_template_versions SET is_active = 0");

        assertThatThrownBy(() -> board("IT No Checklist Academy", List.of(product)))
                .isInstanceOf(NoPublishedPrerequisitesException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE name = 'IT No Checklist Academy'"))
                .isZero();
    }

    @Test
    @DisplayName("a client is readable by the person who boarded it and by nobody else in Sales")
    void createdByIsTheSalesScope() {
        ObClientDtos.ObClientDetail created =
                writes.create(salesAyush, ayush, lean("IT Bluebell Schools", true)).detail();

        assertThat(reads.findDetail(salesAyush, created.id())).isPresent();
        // Not 403 and not an empty field — absent, which is what the 404 rule
        // needs the read to produce.
        assertThat(reads.findDetail(salesDivyansh, created.id())).isEmpty();
        assertThat(reads.list(salesDivyansh, null, null, null, null, null, null, null, null, 50).data())
                .noneSatisfy(row -> assertThat(row.name()).isEqualTo("IT Bluebell Schools"));
    }

    // ── the PAN guard ───────────────────────────────────────────────────────

    /*
      THE THREE PAN TESTS THAT STOOD HERE ARE GONE, AND THE COLUMN IS NOT.

      `pan_ciphertext`, `pan_blind_index` and `uq_ob_clients_pan_blind` are all
      still on `ob_clients`, still populated for every client boarded through
      the retired wizard, and still masked on every read. What no longer exists
      is a way to *write* one: the create takes four fields and none of them is
      a PAN, so "a second client with the same PAN is refused" has no operation
      left to exercise.

      They are deleted rather than disabled because a disabled test is a claim
      nobody is checking. `clientCode` is the exact duplicate guard now and
      `ObClientWriteServiceTest.CodeGuard` is where it is pinned; the index
      behind it belongs in this file the day somebody adds a PAN write back.
    */

    @Test
    @DisplayName("a near-duplicate name warns once and proceeds when acknowledged")
    void similarNamesWarnThenProceed() {
        writes.create(admin, ayush, lean("IT Acme Private Limited", false));

        assertThatThrownBy(() -> writes.create(admin, ayush, lean("IT Acme Pvt Ltd", false)))
                .isInstanceOf(SimilarClientNameException.class);

        assertThat(writes.create(admin, ayush, lean("IT Acme Pvt Ltd", true)).detail().name())
                .isEqualTo("IT Acme Pvt Ltd");
    }

    // ── the portal login the add dialog can issue ───────────────────────────

    /**
     * Three tables in one transaction, against a real database — the client,
     * its primary SPOC and the account. The unit tests pin the ordering and
     * the refusals; this pins that the rows actually land and that the
     * username is the code.
     */
    @Test
    @DisplayName("ticking the box writes the client, its primary SPOC and an account named after the code")
    void portalLoginIsIssuedAtBoarding() {
        ObClientDtos.ObClientCreateRequest request = withLogin("IT Little Flower School");

        ObClientWriteService.Created created = writes.create(admin, ayush, request);

        assertThat(created.login().username()).isEqualTo(request.clientCode());
        assertThat(created.detail().contacts())
                .singleElement()
                .satisfies(contact -> {
                    assertThat(contact.name()).isEqualTo("Arjun Singh");
                    assertThat(contact.isPrimary()).isTrue();
                    assertThat(contact.isActive()).isTrue();
                });

        assertThat(count("SELECT COUNT(*) FROM client_accounts WHERE username = '"
                + request.clientCode() + "' AND ob_client_id = " + created.detail().id()))
                .isOne();
    }

    /**
     * The atomicity the flag's whole design rests on, against a real
     * transaction rather than a mocked one.
     *
     * <p>Provoked with an email longer than {@code ob_client_contacts.email}
     * (VARCHAR(200)). That is deliberately a failure the <em>database</em>
     * raises, and it raises it at the contact insert — after this client's own
     * row is already written. Nothing short of a real rollback makes the
     * company disappear again.
     *
     * <p>Not a duplicate email, which was the first choice and is wrong:
     * {@code uq_ob_client_contacts_email} is {@code (ob_client_id, email)}, so
     * two clients sharing a SPOC address is legal and nothing would have been
     * refused. Not a stubbed issuer either — this class exists to exercise the
     * wiring the unit tests mock out.
     *
     * <p>The over-long value cannot arrive through the controller, where
     * {@code @Size(max = 200)} rejects it first. It is reachable here because
     * the IT calls the service directly, which is the seam that makes the
     * database's own guarantee observable.
     */
    @Test
    @DisplayName("a login that cannot be issued leaves no client behind")
    void aFailedLoginRollsBackTheClient() {
        String tooLong = "a".repeat(195) + "@example.com";
        ObClientDtos.ObClientCreateRequest doomed = new ObClientDtos.ObClientCreateRequest(
                "IT Rollback Beta", "IT-RB-BETA", null, null, true,
                true, "Arjun Singh", tooLong);

        assertThatThrownBy(() -> writes.create(admin, ayush, doomed))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE name = 'IT Rollback Beta'"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM client_accounts WHERE username = 'IT-RB-BETA'"))
                .isZero();
    }

    @Test
    @DisplayName("leaving the box unticked writes the company and nothing else")
    void noLoginWithoutTheFlag() {
        ObClientWriteService.Created created =
                writes.create(admin, ayush, lean("IT No Login Trust", true));

        assertThat(created.login()).isNull();
        assertThat(created.detail().contacts()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM client_accounts WHERE ob_client_id = "
                + created.detail().id())).isZero();
    }

    // ── the edit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIVE is refused, and a hold is recorded with its reason")
    void statusRules() {
        ObClientDtos.ObClientDetail created = board("IT Status Academy", List.of(product));

        ObClientUpdateRequest live = new ObClientUpdateRequest();
        live.setStatus("LIVE");
        assertThatThrownBy(() -> writes.update(admin, created.id(), live))
                .isInstanceOf(LiveStatusNotEarnedException.class);

        ObClientUpdateRequest hold = new ObClientUpdateRequest();
        hold.setStatus("ON_HOLD");
        hold.setStatusReason("Waiting on the client's data extract");
        ObClientDtos.ObClientDetail held = writes.update(admin, created.id(), hold);

        assertThat(held.status()).isEqualTo("ON_HOLD");
        assertThat(held.statusReason()).isEqualTo("Waiting on the client's data extract");

        // Lifting the hold clears the reason, or OB-05 would keep printing why
        // a client that is no longer held was held.
        ObClientUpdateRequest resume = new ObClientUpdateRequest();
        resume.setStatus("ONBOARDING");
        assertThat(writes.update(admin, created.id(), resume).statusReason()).isNull();
    }

    @Test
    @DisplayName("a client out of scope cannot be edited, and is not admitted to exist")
    void editingIsScoped() {
        ObClientDtos.ObClientDetail created =
                writes.create(salesAyush, ayush, lean("IT Scoped Academy", true)).detail();

        ObClientUpdateRequest rename = new ObClientUpdateRequest();
        rename.setName("IT Renamed By Somebody Else");

        assertThatThrownBy(() -> writes.update(salesDivyansh, created.id(), rename))
                .isInstanceOf(ObClientNotFoundException.class);
    }

    // ── RAG ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a client whose journeys are all locked has no colour — that is a gate state, not a colour")
    void lockedClientsHaveNoRag() {
        ObClientDtos.ObClientDetail created = board("IT Locked Academy", List.of(product));

        assertThat(created.rag()).isNull();
        assertThat(created.gateStatus()).isEqualTo("LOCKED");
    }

    /**
     * The assertion {@code ObStepRag.worstOverSteps}' own javadoc names.
     *
     * <p>Three steps, one per colour, each read back through the SQL formula
     * and compared against {@link ObStepRag#of} over the same values. Every
     * instant is minutes away from a boundary, so the two clocks — Java's
     * milliseconds and MySQL's microseconds — cannot disagree by rounding.
     */
    @Test
    @DisplayName("the SQL and Java RAG formulas answer the same for the same rows")
    void sqlAndJavaRagAgree() {
        ObClientDtos.ObClientDetail created = board("IT Colour Academy", List.of(product));
        long journeyId = created.journeys().getFirst().id();
        jdbc.update("UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6) WHERE id = ?",
                journeyId);

        Instant now = Instant.now();
        Instant green = now.plus(10, ChronoUnit.HOURS);
        Instant amber = now.plus(1, ChronoUnit.HOURS);
        Instant red = now.minus(2, ChronoUnit.HOURS);
        Instant startedTenHoursAgo = now.minus(10, ChronoUnit.HOURS);

        setStepClock(journeyId, 1, startedTenHoursAgo, green);
        setStepClock(journeyId, 2, startedTenHoursAgo, amber);

        assertThat(sqlColourOf(journeyId, 1)).isEqualTo(ObStepRag.of(startedTenHoursAgo, green, now));
        assertThat(sqlColourOf(journeyId, 2)).isEqualTo(ObStepRag.of(startedTenHoursAgo, amber, now));

        // Worst wins: one AMBER step among GREEN ones colours the client AMBER.
        assertThat(detailOf(created.id()).rag()).isEqualTo(ObStepRag.AMBER);

        setStepClock(journeyId, 1, startedTenHoursAgo, red);
        assertThat(sqlColourOf(journeyId, 1)).isEqualTo(ObStepRag.of(startedTenHoursAgo, red, now));
        assertThat(detailOf(created.id()).rag()).isEqualTo(ObStepRag.RED);
    }

    @Test
    @DisplayName("the list's rag filter and the row's own colour are the same answer")
    void ragFilterAgreesWithTheColumn() {
        ObClientDtos.ObClientDetail created = board("IT Filtered Academy", List.of(product));
        long journeyId = created.journeys().getFirst().id();
        jdbc.update("UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6) WHERE id = ?",
                journeyId);
        setStepClock(journeyId, 1, Instant.now().minus(10, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.HOURS));

        List<ObClientDtos.ObClientSummary> red =
                reads.list(admin, "IT Filtered", null, ObStepRag.RED, null, null, null, null, null, 50).data();
        List<ObClientDtos.ObClientSummary> green =
                reads.list(admin, "IT Filtered", null, ObStepRag.GREEN, null, null, null, null, null, 50).data();

        assertThat(red).extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Filtered Academy");
        assertThat(red.getFirst().rag()).isEqualTo(ObStepRag.RED);
        assertThat(green).isEmpty();
    }

    // ── B-108 · OB-03's filters ─────────────────────────────────────────────

    /**
     * The filter B-108 adds, and the reason it needed a container.
     *
     * <p>It is the only one on this list that is not a column on
     * {@code ob_clients}: it walks journeys to steps and back, so a mock of the
     * repository would assert nothing but that a parameter was passed along.
     * Two clients, one step owned on each side, and the assertion is that the
     * <b>other</b> client is absent — a filter the SQL quietly ignored would
     * return both and read as working.
     */
    @Test
    @DisplayName("the owner filter narrows to the clients whose journeys hold that person's steps")
    void ownerFilterNarrowsToTheirClients() {
        ObClientDtos.ObClientDetail mine = board("IT Owned Academy", List.of(product));
        // Named so it shares no stem with the subject: "IT Unowned Academy"
        // trips the near-duplicate name guard, which is a real refusal rather
        // than a test-harness quirk — SimilarClientNames is doing its job.
        board("IT Bystander Academy", List.of(product));

        ownStep(mine.journeys().getFirst().id(), 1, divyansh, null);

        assertThat(reads.list(admin, "IT ", null, null, null, null, null, divyansh, null, 50).data())
                .extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Owned Academy");
    }

    /**
     * The half that is easiest to lose, and the one
     * {@code OnboardingScopeResolver.hasStepOwnedBy} already argues for: the
     * backup exists to cover the step when the owner cannot, so a list that
     * hid those clients would hide exactly the ones a stand-in has been asked
     * to pick up. A predicate reading only {@code owner_user_id} passes every
     * other assertion in this class.
     */
    @Test
    @DisplayName("a backup owner is an owner for this filter")
    void backupOwnersMatchToo() {
        ObClientDtos.ObClientDetail covered = board("IT Covered Academy", List.of(product));

        ownStep(covered.journeys().getFirst().id(), 1, ayush, divyansh);

        assertThat(reads.list(admin, "IT Covered", null, null, null, null, null, divyansh, null, 50).data())
                .extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Covered Academy");
    }

    /**
     * Both owner columns are nullable and SQL equality never matches NULL, so
     * an unowned step attributes its client to nobody. Asserted rather than
     * assumed because the failure mode is silent and wide: a predicate written
     * with {@code <=>} or with a coalesce would hand every unassigned client to
     * whoever the filter names.
     */
    @Test
    @DisplayName("an unowned step gives nobody a claim on the client")
    void unownedStepsMatchNobody() {
        board("IT Ownerless Academy", List.of(product));

        assertThat(reads.list(admin, "IT Ownerless", null, null, null, null, null, ayush, null, 50).data())
                .isEmpty();
    }

    /**
     * The archived arm, which the {@code EXISTS} spells out and nothing else
     * would catch: a journey that was called off is not work anybody is
     * implementing, and leaving it in would keep a client on an implementor's
     * list for ever.
     */
    @Test
    @DisplayName("an archived journey does not keep the client on its owner's list")
    void archivedJourneysDropOutOfTheOwnerFilter() {
        ObClientDtos.ObClientDetail archived = board("IT Archived Academy", List.of(product));
        long journeyId = archived.journeys().getFirst().id();
        ownStep(journeyId, 1, divyansh, null);

        assertThat(reads.list(admin, "IT Archived", null, null, null, null, null, divyansh, null, 50).data())
                .hasSize(1);

        jdbc.update("UPDATE ob_journeys SET archived_at = NOW(6) WHERE id = ?", journeyId);

        assertThat(reads.list(admin, "IT Archived", null, null, null, null, null, divyansh, null, 50).data())
                .isEmpty();
    }

    /**
     * The sales filter, which the OB-03 screen cannot test against its own mock
     * corpus — every fixture client there shares one sales person, so the
     * filter either returns the whole list or none of it and neither outcome
     * distinguishes a working filter from an ignored one. Here the rows can be
     * arranged.
     */
    @Test
    @DisplayName("the sales filter narrows to that person's clients and no others")
    void salesFilterNarrows() {
        ObClientDtos.ObClientDetail theirs = board("IT Sold Academy", List.of(product));
        board("IT Bystander Academy", List.of(product));
        jdbc.update("UPDATE ob_clients SET sales_person_id = ? WHERE id = ?", divyansh, theirs.id());

        assertThat(reads.list(admin, "IT ", null, null, null, null, divyansh, null, null, 50).data())
                .extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Sold Academy");
    }

    /**
     * The gate filter is what OB-03 renders as "Prerequisites pending", and it
     * is the only way to ask for those clients: their {@code rag} is null, so
     * none of the three colours returns them. Both halves are asserted, because
     * a filter that returned everything would pass the first on its own.
     */
    @Test
    @DisplayName("the gate filter is the only way to ask for a client with no colour")
    void gateFilterFindsTheLockedOnes() {
        ObClientDtos.ObClientDetail locked = board("IT Gated Academy", List.of(product));

        assertThat(reads.list(admin, "IT Gated", null, null, "LOCKED", null, null, null, null, 50).data())
                .extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Gated Academy");
        assertThat(reads.list(admin, "IT Gated", null, null, "OPEN", null, null, null, null, 50).data())
                .isEmpty();

        jdbc.update("UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6) "
                + "WHERE ob_client_id = ?", locked.id());

        assertThat(reads.list(admin, "IT Gated", null, null, "OPEN", null, null, null, null, 50).data())
                .hasSize(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ObClientDtos.ObClientDetail detailOf(long id) {
        Optional<ObClientDtos.ObClientDetail> detail = reads.findDetail(admin, id);
        assertThat(detail).isPresent();
        return detail.orElseThrow();
    }

    private String sqlColourOf(long journeyId, int sequence) {
        return jdbc.queryForObject(
                "SELECT " + ObStepRag.colourOfStep("s")
                        + " FROM ob_journey_steps s WHERE s.journey_id = ? AND s.sequence = ?",
                String.class, journeyId, sequence);
    }

    /** B-108 · one step's two owner columns, which is all the owner filter reads. */
    private void ownStep(long journeyId, int sequence, Long owner, Long backup) {
        jdbc.update("UPDATE ob_journey_steps SET owner_user_id = ?, backup_owner_user_id = ? "
                        + "WHERE journey_id = ? AND sequence = ?",
                owner, backup, journeyId, sequence);
    }

    private void setStepClock(long journeyId, int sequence, Instant startedAt, Instant dueAt) {
        jdbc.update("UPDATE ob_journey_steps SET started_at = ?, due_at = ?, status = 'IN_PROGRESS' "
                        + "WHERE journey_id = ? AND sequence = ?",
                Timestamp.from(startedAt), Timestamp.from(dueAt), journeyId, sequence);
    }

    /**
     * Board a client the way the product now does it: the company, then its
     * purchases.
     *
     * <p>Two calls where the wizard did both at once. The purchase goes through
     * the operation that owns it, which is also what provisions the project and
     * the client's prerequisite checklist — so a fixture built this way exercises
     * the same path a real boarding takes, rather than a shortcut that only this
     * file knows about.
     *
     * <p><b>The name guard is acknowledged by default.</b> Every fixture here is
     * called "IT Something Academy", and the fuzzy guard is quite right to think
     * they are the same company. It has its own test below, which opts out.
     */
    private ObClientDtos.ObClientDetail board(String name, List<Long> productIds) {
        ObClientDtos.ObClientDetail client = writes.create(admin, ayush, lean(name, true)).detail();
        for (Long productId : productIds) {
            client = applications.add(admin, client.id(),
                    new ObClientDtos.ObApplicationWriteRequest(
                            productId, "ANNUAL", 100, BOARDED, BOARDED.plusYears(1)));
        }
        return client;
    }

    /**
     * The four fields, with a code derived from the name.
     *
     * <p>`uq_ob_clients_client_code` is org-wide and these fixtures share a
     * schema across tests, so the code has to be unique per client rather than
     * per test — hence the name hash rather than the run counter every other
     * identifier here uses.
     */
    private static ObClientDtos.ObClientCreateRequest lean(String name, boolean acknowledge) {
        return new ObClientDtos.ObClientCreateRequest(
                name, "IT-" + Integer.toHexString(name.hashCode()),
                "12 Test Road", "Pune", acknowledge);
    }

    /** {@link #lean} plus the tick box and the SPOC it requires. */
    private static ObClientDtos.ObClientCreateRequest withLogin(String name) {
        return new ObClientDtos.ObClientCreateRequest(
                name, "IT-" + Integer.toHexString(name.hashCode()),
                "12 Test Road", "Pune", true,
                true, "Arjun Singh", "arjun@littleflower.example");
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return idOfLastInsert();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)", code, name);
        return idOfLastInsert();
    }

    /**
     * Two steps, five TAT days between them — enough for a strip and a roll-up.
     *
     * <p><b>Both steps hang off a stage group.</b> V20260911_1630 made the
     * Implementation Stage a group that owns its tasks and left
     * {@code ob_journey_template_steps.template_stage_id} NOT NULL, so a step
     * inserted without one is refused with "Field 'template_stage_id' doesn't
     * have a default value" — in {@code @BeforeEach}, which fails every test in
     * this class rather than the one that cares.
     *
     * <p>The group carries no {@code implementation_stage_id}. That column is
     * nullable and the migration's own second backfill uses NULL for exactly
     * this case — a group holding hand-named steps rather than one named from
     * the stage master. These two steps are hand-named, and pointing them at a
     * master row would mean seeding one to say nothing about them.
     */
    private void insertTemplate(long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, productId, name);
        long templateId = idOfLastInsert();
        jdbc.update("""
                INSERT INTO ob_journey_template_stages
                    (template_id, implementation_stage_id, name, sequence)
                VALUES (?, NULL, 'Ungrouped', 9999)
                """, templateId);
        long stageId = idOfLastInsert();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps
                    (template_id, template_stage_id, sequence, name, tat_days)
                VALUES (?, ?, 1, 'Kickoff', 2), (?, ?, 2, 'Configuration', 3)
                """, templateId, stageId, templateId, stageId);
    }

    /** B-109 · one published, active version, so every {@code writes.create} in this class has a checklist to snapshot. */
    private long insertPrereqMaster() {
        jdbc.update("""
                INSERT INTO ob_prereq_template_versions (version, is_active, published_at, published_by)
                VALUES (1, 1, NOW(6), ?)
                """, ayush);
        long versionId = idOfLastInsert();
        jdbc.update("""
                INSERT INTO ob_prereq_template_tasks
                    (version_id, sequence, title, tat_days, is_mandatory, is_active)
                VALUES (?, 1, 'Signed MSA', 2, 1, 1),
                       (?, 2, 'Data export template', 3, 0, 1)
                """, versionId, versionId);
        return versionId;
    }

    private long idOfLastInsert() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
