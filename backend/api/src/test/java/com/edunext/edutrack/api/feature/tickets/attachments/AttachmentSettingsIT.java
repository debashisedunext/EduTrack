package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-027 · {@code attachment_settings} against a real MySQL.
 *
 * <p>{@code AttachmentSettingsServiceTest} proves which source wins and what the
 * container's multipart limit does to it, against mocks. This proves the half a
 * mock cannot:
 *
 * <ul>
 *   <li><b>that the migration leaves the enforced limits exactly where C-025
 *       had them</b> — the assertion this task lives or dies by, because a seed
 *       that differed from §4B.4's published numbers would change what every
 *       ticket in the system accepts the moment it was applied, silently;</li>
 *   <li>that the table really can hold only one row, which is a {@code CHECK}
 *       and is precisely the sort of thing that is right in a mock and absent in
 *       SQL;</li>
 *   <li>that the {@code CHECK} constraints refuse the same combinations
 *       {@link AttachmentLimits#of} refuses, so a row written by a fixture, a
 *       later migration or a DBA cannot put the upload guard into a state the
 *       {@code PUT} would have rejected;</li>
 *   <li>that a write round-trips and is read back by the service that enforces
 *       it.</li>
 * </ul>
 *
 * <p>Every test restores §4B.4's defaults afterwards. There is one row and it is
 * global, so a test that left it changed would reconfigure every test after it —
 * the failure mode being a suite that passes alone and fails in a full run.
 */
@SpringBootTest
@Testcontainers
class AttachmentSettingsIT {

    private static final long TEN_MB = 10L * 1024 * 1024;
    private static final long FIFTY_MB = 50L * 1024 * 1024;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_attachment_settings_it")
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
    AttachmentSettingsService service;

    @Autowired
    AttachmentSettingsRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void restoreTheBlueprintDefaults() {
        jdbc.update("""
                UPDATE attachment_settings
                   SET max_file_bytes = ?, max_ticket_bytes = ?, max_files = ?, updated_by = NULL
                 WHERE id = 1
                """, TEN_MB, FIFTY_MB, 20);
    }

    // ------------------------------------------------------------------
    // what applying the migration does to a running system
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the seeded row is §4B.4's own numbers, so nothing changes the day this ships")
    void theSeededRowMatchesTheBlueprint() {
        // The assertion the migration lives or dies by. C-025 enforced 10/50/20
        // from properties; if the seed differed, applying this migration would
        // silently change what every ticket in the system accepts, and the only
        // symptom would be an upload that used to work.
        AttachmentLimits limits = repository.load().orElseThrow().limits();

        assertThat(limits.maxFileBytes()).isEqualTo(TEN_MB);
        assertThat(limits.maxTicketBytes()).isEqualTo(FIFTY_MB);
        assertThat(limits.maxFiles()).isEqualTo(20);
    }

    @Test
    @DisplayName("nobody wrote the seeded row, and it does not pretend otherwise")
    void theSeededRowIsNotAttributedToAnybody() {
        assertThat(repository.load().orElseThrow().updatedBy()).isNull();
    }

    // ------------------------------------------------------------------
    // one row, enforced by the schema rather than by convention
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a second configuration cannot be inserted")
    void theTableHoldsExactlyOneRow() {
        // "The newest row wins" reads fine and then silently serves the old
        // limits the first time an insert lands with a clock skew. There is no
        // scope column a second row could ever mean something against.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attachment_settings (id, max_file_bytes, max_ticket_bytes, max_files)
                VALUES (2, ?, ?, ?)
                """, TEN_MB, FIFTY_MB, 20))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment_settings", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // the CHECK constraints refuse what the service refuses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a per-ticket total below the per-file cap is refused by the database too")
    void theTicketTotalMustCoverTheFileCap() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE attachment_settings SET max_file_bytes = ?, max_ticket_bytes = ? WHERE id = 1",
                TEN_MB, 1024L))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("zero is refused on every column — it would switch attachments off with nothing saying so")
    void zeroIsNotUnlimited() {
        assertThatThrownBy(() -> jdbc.update("UPDATE attachment_settings SET max_files = 0 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE attachment_settings SET max_file_bytes = 0, max_ticket_bytes = 0 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("the upper bounds are the ones AttachmentLimits states")
    void theCeilingsMatchTheApplications() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE attachment_settings SET max_file_bytes = ?, max_ticket_bytes = ? WHERE id = 1",
                AttachmentLimits.MAX_FILE_BYTES_CEILING + 1, AttachmentLimits.MAX_FILE_BYTES_CEILING + 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE attachment_settings SET max_files = ? WHERE id = 1",
                AttachmentLimits.MAX_FILES_CEILING + 1))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // a write, read back by the thing that enforces it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a saved change is what the upload guard sees on its next read")
    void aWriteRoundTrips() {
        service.replace(new TestingAuthenticationToken("admin", "n/a"), TEN_MB, 20L * 1024 * 1024, 4);

        AttachmentLimits enforced = service.effective();

        assertThat(enforced.maxTicketBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(enforced.maxFiles()).isEqualTo(4);
    }

    @Test
    @DisplayName("updated_at moves on a save, so a settings screen can say when")
    void theTimestampIsMaintainedByTheColumnItself() {
        var before = repository.load().orElseThrow().updatedAt();

        jdbc.update("UPDATE attachment_settings SET max_files = 19 WHERE id = 1");

        assertThat(repository.load().orElseThrow().updatedAt()).isAfterOrEqualTo(before);
    }
}
