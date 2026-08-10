package com.edunext.edutrack.domain.masters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-023 · {@code uq_holidays} has to stop a duplicate <b>org-wide</b> holiday,
 * not just a duplicate project one.
 *
 * <h2>What this exists for</h2>
 *
 * <p>The original index was {@code (holiday_date, project_id)}, which reads as
 * "one holiday per date per scope" and is not. <b>MySQL compares NULLs as
 * distinct in a unique index</b>, and {@code project_id IS NULL} is exactly how
 * an org-wide holiday is stored — so the same date could be inserted any number
 * of times and the endpoint answered 201 where the contract promises 409.
 *
 * <p>No unit test could have found it: the rule lives entirely in the database,
 * and a mocked repository throws whatever the test tells it to. It took calling
 * {@code POST /masters/holidays} twice against a real MySQL. That is why this is
 * an IT and why both scopes are asserted — the project-scoped case always
 * worked, which is precisely what made the org-wide hole invisible.
 *
 * <p>{@code V20260810_0930} fixes it with a stored generated column mapping
 * {@code NULL} to {@code 0}.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HolidayUniquenessIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_holiday_it")
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    @Autowired
    DataSource dataSource;

    JdbcClient db;

    /**
     * A migrated schema has no projects — only reference data is seeded — and
     * {@code fk_holidays_project} means a project-scoped holiday needs one to
     * point at. Inserted per test rather than assumed.
     */
    @BeforeEach
    void setUp() {
        db = JdbcClient.create(dataSource);
        for (long id : new long[]{1L, 2L, 5L}) {
            db.sql("INSERT IGNORE INTO projects (id, project_code, name) VALUES (?, ?, ?)")
                    .param(id).param("P" + id).param("Project " + id)
                    .update();
        }
    }

    private void insert(String date, String name, Long projectId) {
        db.sql("INSERT INTO holidays (holiday_date, name, project_id) VALUES (?, ?, ?)")
                .param(date).param(name).param(projectId)
                .update();
    }

    /** The defect, as a test. Under the old index both inserts succeeded. */
    @Test
    @DisplayName("a second org-wide holiday on the same date is refused")
    void orgWideDuplicatesAreRefused() {
        insert("2026-12-25", "Christmas", null);

        assertThatThrownBy(() -> insert("2026-12-25", "Duplicate", null))
                .as("project_id IS NULL must not exempt a row from the unique key")
                .hasMessageContaining("uq_holidays");
    }

    @Test
    @DisplayName("a second holiday on the same date for one project is refused")
    void projectDuplicatesAreRefused() {
        insert("2026-08-15", "Independence Day", 1L);

        assertThatThrownBy(() -> insert("2026-08-15", "Clash", 1L))
                .hasMessageContaining("uq_holidays");
    }

    /**
     * The rule is per scope, not per date. A project holiday sitting alongside
     * the org one is normal — either makes the day non-working, so they union
     * rather than conflict.
     */
    @Test
    @DisplayName("a project holiday may share a date with the org-wide one")
    void differentScopesMayShareADate() {
        insert("2026-11-08", "Diwali", null);
        insert("2026-11-08", "Diwali — extra day", 1L);
        insert("2026-11-08", "Diwali — extra day", 2L);

        assertThat(db.sql("SELECT COUNT(*) FROM holidays WHERE holiday_date = '2026-11-08'")
                .query(Integer.class).single())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the generated scope column mirrors project_id, with 0 standing in for org-wide")
    void scopeColumnMirrorsProjectId() {
        insert("2026-01-26", "Republic Day", null);
        insert("2026-01-26", "Republic Day — project", 5L);

        assertThat(db.sql("SELECT project_scope FROM holidays WHERE holiday_date = '2026-01-26' "
                        + "ORDER BY project_scope").query(Long.class).list())
                .as("0 is unreachable as a real projects.id — AUTO_INCREMENT starts at 1")
                .containsExactly(0L, 5L);
    }
}
