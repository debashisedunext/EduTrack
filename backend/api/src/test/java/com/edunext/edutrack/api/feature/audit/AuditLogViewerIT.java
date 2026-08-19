package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.security.TestPrincipals;
import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.domain.audit.AuditEntry;
import com.edunext.edutrack.domain.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-071 · S-16 against real MySQL.
 *
 * <p>Two things here cannot be asserted anywhere else, and they are the reason
 * this suite exists rather than more unit tests.
 *
 * <p><b>The immutability triggers.</b> {@code V20260818_1500} is the layer that
 * turns "never editable" from a convention into a refusal, and a mock cannot
 * throw the exception a trigger raises. A-013 makes the same argument for the
 * three hash-chained tables: a guarantee enforced only by nobody having written
 * the statement yet is not a guarantee. These four tests attempt the mutation
 * and assert it is rejected — which is the only form of proof this layer has.
 *
 * <p><b>The keyset page.</b> Ordering, ties and the fetch-one-extra boundary are
 * all decided by SQL the unit tests stub out. The failure mode is silent: a
 * mis-ordered page drops rows rather than erroring, and on an audit log a
 * dropped row reads as an action nobody took.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditLogViewerIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_audit_it")
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

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    AuditTrail trail;

    @Autowired
    AuditService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtAuthoritiesConverter authorities;

    private long actor;

    @BeforeEach
    void clean() {
        // The DELETE trigger this suite exists to prove would refuse an ordinary
        // clean-up, which is the first thing anybody notices about this table.
        // Disabling it for the fixture would also disable the thing under test,
        // so the suite filters instead: every test scopes its assertions to the
        // actor it created, and the table is left to accumulate.
        actor = user("audit.actor");
    }

    @Nested
    @DisplayName("the four layers, at the layer only a database can refuse")
    class Immutability {

        @Test
        void aRowCanBeAppended() {
            assertThat(trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null))).isTrue();

            assertThat(countFor(actor)).isEqualTo(1);
        }

        @Test
        @DisplayName("UPDATE is refused by trg_audit_no_update")
        void aRowCannotBeEdited() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE audit_logs SET action = 'SOMETHING_ELSE' WHERE actor_id = ?", actor))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("cannot be updated");
        }

        @Test
        @DisplayName("DELETE is refused by trg_audit_no_delete")
        void aRowCannotBeRemoved() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", actor))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("cannot be deleted");
        }

        /**
         * The subtler half. A trigger that only fired on a targeted statement
         * would leave "delete everything" open, which is the shape somebody
         * covering their tracks would actually reach for.
         */
        @Test
        void andNorCanTheWholeTable() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(countFor(actor)).isEqualTo(1);
        }

        /**
         * A row survives its actor. Not a nicety: {@code fk_audit_logs_actor}
         * without this would make deleting a user the supported way to erase
         * what they did.
         */
        @Test
        @DisplayName("the actor cannot be deleted out from under the row")
        void theForeignKeyHoldsTheUser() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", actor))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("what is stored")
    class Storage {

        @Test
        void theTimestampIsTheDatabasesAndNotTheCallers() {
            Instant before = Instant.now().minusSeconds(5);

            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            assertThat(entries().get(0).createdAt()).isAfter(before);
        }

        @Test
        void aSystemRowHasNoActorAndIsStillRecorded() {
            assertThat(trail.record(new AuditEntry(null, "CHAIN_VERIFIED", "tickets",
                    null, null, null, null, null, null))).isTrue();
        }

        /**
         * An over-length User-Agent is a reason to trim the string, never a
         * reason to lose the record of what was done with it. Without the clamp
         * this is a {@code DataIntegrityViolationException} that
         * {@code AuditTrail} swallows — a silently missing row.
         */
        @Test
        void anOverlongUserAgentIsTrimmedRatherThanLosingTheRow() {
            String monstrous = "M".repeat(400);

            assertThat(trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null)
                    .from("203.0.113.9", monstrous))).isTrue();

            assertThat(entries().get(0).userAgent()).hasSize(255);
        }

        @Test
        void aTicketCodeRoundTripsAsTheSubject() {
            trail.record(AuditEntry.forRef(actor, "COMMENTS_CREATED", "tickets", "CRM-26-00347"));

            assertThat(entries().get(0).entityId()).isEqualTo("CRM-26-00347");
        }
    }

    @Nested
    @DisplayName("the viewer")
    class Viewer {

        @Test
        void returnsNewestFirst() {
            trail.record(AuditEntry.of(actor, "FIRST_CREATED", "tickets", null));
            trail.record(AuditEntry.of(actor, "SECOND_CREATED", "tickets", null));
            trail.record(AuditEntry.of(actor, "THIRD_CREATED", "tickets", null));

            assertThat(entries()).extracting(AuditDtos.Entry::action)
                    .containsExactly("THIRD_CREATED", "SECOND_CREATED", "FIRST_CREATED");
        }

        @Test
        void filtersByAction() {
            trail.record(AuditEntry.of(actor, "LOGIN_SUCCESS", "users", actor));
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            CursorPage<AuditDtos.Entry> page = service.page(
                    AuditService.Filters.of(actor, "LOGIN_SUCCESS", null, null, null), null, 50);

            assertThat(page.data()).singleElement()
                    .extracting(AuditDtos.Entry::action).isEqualTo("LOGIN_SUCCESS");
        }

        @Test
        void filtersByModule() {
            trail.record(AuditEntry.of(actor, "ROLES_UPDATED", "masters", 4L));
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            CursorPage<AuditDtos.Entry> page = service.page(
                    AuditService.Filters.of(actor, null, "masters", null, null), null, 50);

            assertThat(page.data()).singleElement()
                    .extracting(AuditDtos.Entry::entityType).isEqualTo("masters");
        }

        /**
         * {@code from} is inclusive and {@code to} is exclusive, so a range that
         * ends where the next begins neither repeats nor skips. Asserted against
         * a window that certainly contains the row and one that certainly does
         * not, rather than against an exact boundary a clock skew could flip.
         */
        @Test
        void filtersByDateWindow() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));
            Instant now = Instant.now();

            assertThat(service.page(AuditService.Filters.of(actor, null, null,
                    now.minusSeconds(300).toString(), now.plusSeconds(300).toString()),
                    null, 50).data()).hasSize(1);

            assertThat(service.page(AuditService.Filters.of(actor, null, null,
                    null, now.minusSeconds(300).toString()),
                    null, 50).data()).isEmpty();
        }

        /**
         * The assertion the keyset exists for. All five rows are written inside
         * the same microsecond-ish burst, which is exactly when an
         * {@code ORDER BY created_at} alone starts repeating or skipping — so
         * paging through them and finding each one once is the property under
         * test, not the page size.
         */
        @Test
        @DisplayName("paging a burst of same-instant rows loses none and repeats none")
        void pagesThroughTiesCleanly() {
            for (int i = 1; i <= 5; i++) {
                trail.record(AuditEntry.of(actor, "BURST_" + i + "_CREATED", "tickets", null));
            }

            List<String> seen = new java.util.ArrayList<>();
            String cursor = null;
            do {
                CursorPage<AuditDtos.Entry> page = service.page(
                        AuditService.Filters.of(actor, null, null, null, null), cursor, 2);
                page.data().forEach(entry -> seen.add(entry.action()));
                cursor = page.meta() == null ? null : page.meta().nextCursor();
            } while (cursor != null);

            assertThat(seen).hasSize(5).doesNotHaveDuplicates()
                    .containsExactly("BURST_5_CREATED", "BURST_4_CREATED", "BURST_3_CREATED",
                            "BURST_2_CREATED", "BURST_1_CREATED");
        }

        /**
         * The {@code LEFT JOIN}. Deactivation is what the product actually does
         * to a departing user (B-014), and the row must read the same either
         * way — the join must not quietly drop rows whose actor is inactive.
         */
        @Test
        void keepsRowsWhoseActorIsDeactivated() {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));
            jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", actor);

            assertThat(entries()).hasSize(1);
            assertThat(entries().get(0).actor()).isNotNull();
        }
    }

    /**
     * A-071 · the route, over real HTTP, because two things here only fail in a
     * running dispatcher.
     *
     * <p>The export branch writes the file onto the response and returns
     * {@code null} from a handler declaring {@code ResponseEntity}. That is a
     * Spring MVC contract, not application logic — {@code ReportController}
     * records at length how the neighbouring shape of it produced a 500 on
     * every export while JSON on the same route kept working, and no unit test
     * saw it. And the interceptor only runs inside {@code DispatcherServlet},
     * so "a refused read is recorded" is unassertable anywhere else.
     */
    @Nested
    @DisplayName("over HTTP")
    class Http {

        @Test
        void anAdminReadsTheLog() throws Exception {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            mvc.perform(get("/api/v1/audit-logs").param("actorId", String.valueOf(actor))
                            .with(authentication(TestPrincipals.of(authorities, RolePermissions.ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].action").value("TICKETS_CREATED"))
                    .andExpect(jsonPath("$.data[0].entityType").value("tickets"));
        }

        @Test
        @DisplayName("csv comes back as a file, not as a 500")
        void theCsvExportStreams() throws Exception {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            String body = mvc.perform(get("/api/v1/audit-logs")
                            .param("actorId", String.valueOf(actor))
                            .param("export", "csv")
                            .with(authentication(TestPrincipals.of(authorities, RolePermissions.ADMIN))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            org.hamcrest.Matchers.containsString("audit-log-")))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("TICKETS_CREATED").contains("Audit log");
        }

        @Test
        void theXlsxExportStreamsAWorkbook() throws Exception {
            trail.record(AuditEntry.of(actor, "TICKETS_CREATED", "tickets", null));

            byte[] bytes = mvc.perform(get("/api/v1/audit-logs")
                            .param("actorId", String.valueOf(actor))
                            .param("export", "xlsx")
                            .with(authentication(TestPrincipals.of(authorities, RolePermissions.ADMIN))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();

            // "PK" — a zip, which is what an .xlsx is. Cheaper than opening it
            // with POI and it fails on exactly the thing worth catching: a JSON
            // problem body delivered under a spreadsheet's filename.
            assertThat(bytes).hasSizeGreaterThan(2);
            assertThat(new String(bytes, 0, 2, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("PK");
        }

        /** The contract offers two formats here against three on reports. */
        @Test
        void pdfIsRefusedWithABadRequest() throws Exception {
            mvc.perform(get("/api/v1/audit-logs").param("export", "pdf")
                            .with(authentication(TestPrincipals.of(authorities, RolePermissions.ADMIN))))
                    .andExpect(status().isBadRequest());
        }

        /**
         * 403 and not 404: there is no id to probe for existence here, and the
         * existence of an audit log is documented in §13. See AuditController.
         */
        @Test
        void everyOtherRoleIsRefused() throws Exception {
            for (String role : List.of(RolePermissions.PM, RolePermissions.SUPPORT,
                    RolePermissions.DEVELOPER, RolePermissions.QA, RolePermissions.DEPLOYMENT)) {
                mvc.perform(get("/api/v1/audit-logs")
                                .with(authentication(TestPrincipals.of(authorities, 4242L, role, List.of()))))
                        .andExpect(status().isForbidden());
            }
        }

        /**
         * The end-to-end proof of the interceptor: a refused read changes
         * nothing, so this row is the only trace it happened at all — and it is
         * the row an Admin opens this screen to find.
         */
        @Test
        @DisplayName("a refusal records itself")
        void aDeniedReadIsAudited() throws Exception {
            long snooper = user("audit.snooper");

            mvc.perform(get("/api/v1/audit-logs")
                            .with(authentication(TestPrincipals.of(
                                    authorities, snooper, RolePermissions.DEVELOPER, List.of()))))
                    .andExpect(status().isForbidden());

            List<AuditDtos.Entry> theirs = service.page(
                    AuditService.Filters.of(snooper, null, null, null, null), null, 50).data();

            assertThat(theirs).singleElement().satisfies(entry -> {
                assertThat(entry.action()).isEqualTo("ACCESS_DENIED");
                assertThat(entry.entityType()).isEqualTo("audit_logs");
            });
        }
    }

    // --- helpers -----------------------------------------------------------

    private List<AuditDtos.Entry> entries() {
        return service.page(AuditService.Filters.of(actor, null, null, null, null), null, 50).data();
    }

    private int countFor(long actorId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE actor_id = ?", Integer.class, actorId);
        return count == null ? 0 : count;
    }

    private long user(String name) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'ADMIN'", Long.class);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Audit IT', ?, 1)
                """, u, u, u + "@example.test", roleId);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }
}
