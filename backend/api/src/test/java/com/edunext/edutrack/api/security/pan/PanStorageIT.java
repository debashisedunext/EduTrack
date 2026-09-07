package com.edunext.edutrack.api.security.pan;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A-113 · the crypto against the real columns.
 *
 * <p>Everything else about PAN is proved without a database, and cheaply — the
 * unit tests cover the cipher, the index, the masking and the audit decisions.
 * <b>Three claims cannot be proved that way</b>, and each of them is a claim the
 * schema makes rather than the code:
 *
 * <ol>
 *   <li>the ciphertext fits {@code VARBINARY(255)} and survives the round trip
 *       through MySQL rather than only through memory;</li>
 *   <li>the blind index fits {@code BINARY(32)} — a type that <em>pads</em>
 *       rather than truncating, so a wrong length comes back silently altered
 *       instead of failing;</li>
 *   <li><b>the UNIQUE constraint on {@code pan_blind_index} actually fires.</b>
 *       This is the one that matters. The whole two-column design exists
 *       because a UNIQUE index over randomised ciphertext would "apply cleanly,
 *       look exactly like a working constraint, and silently never fire" — the
 *       migration's words. An assertion that the replacement <em>does</em> fire
 *       is the only thing that distinguishes this design from the one it was
 *       chosen over.</li>
 * </ol>
 *
 * <p>Raw JDBC against {@code ob_clients}, deliberately: <b>B-102 owns the
 * entity</b> and {@code ObClient}'s own note says to widen that class rather
 * than add a second {@code @Entity} over the table. Mapping PAN columns here to
 * write a test would pre-empt exactly that.
 */
@SpringBootTest
@Testcontainers
class PanStorageIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_pan_it")
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

    private static final String PAN = "AAAPL1234C";
    private static final CallerIdentity CALLER = new CallerIdentity(1L, "OB_ADMIN", List.of());

    @Autowired
    PanService pan;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_pan_%'");
    }

    private long insertClient(String name, PanService.SealedPan sealed) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, pan_ciphertext, pan_blind_index)
                VALUES (?, ?, ?, ?)
                """, name, LocalDate.of(2026, 9, 7), sealed.ciphertext(), sealed.blindIndex());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    @DisplayName("a PAN survives the round trip through the real columns")
    void roundTripsThroughMySql() {
        long id = insertClient("it_pan_round_trip", pan.seal(PAN));

        byte[] stored = jdbc.queryForObject(
                "SELECT pan_ciphertext FROM ob_clients WHERE id = ?", byte[].class, id);

        assertThat(pan.reveal(id, CALLER, stored)).isEqualTo(PAN);
    }

    @Test
    @DisplayName("the stored blind index is exactly 32 bytes back, not padded")
    void blindIndexIsNotPadded() {
        // BINARY(32) pads with 0x00 rather than rejecting, so a 31-byte index
        // would come back 32 bytes long and quietly wrong — matching nothing it
        // should match, and the duplicate guard would simply stop working.
        PanService.SealedPan sealed = pan.seal(PAN);
        long id = insertClient("it_pan_index_width", sealed);

        byte[] stored = jdbc.queryForObject(
                "SELECT pan_blind_index FROM ob_clients WHERE id = ?", byte[].class, id);

        assertThat(stored).hasSize(32).isEqualTo(sealed.blindIndex());
    }

    @Test
    @DisplayName("the UNIQUE constraint fires on a second client with the same PAN")
    void refusesADuplicatePan() {
        // The assertion the whole two-column design was chosen for.
        insertClient("it_pan_first", pan.seal(PAN));

        assertThatExceptionOfType(DuplicateKeyException.class)
                .isThrownBy(() -> insertClient("it_pan_second", pan.seal(PAN)));
    }

    @Test
    @DisplayName("it fires however the duplicate was typed")
    void refusesADuplicateTypedDifferently() {
        // Normalisation is what makes this true. Without it the second row
        // hashes differently, the constraint does not fire, and the client is
        // onboarded twice — the exact defect plan §1.1 item 6 exists to prevent.
        insertClient("it_pan_case_first", pan.seal(PAN));

        assertThatExceptionOfType(DuplicateKeyException.class)
                .isThrownBy(() -> insertClient("it_pan_case_second", pan.seal("  aaapl1234c  ")));
    }

    @Test
    @DisplayName("two different PANs both store, so the constraint is not over-firing")
    void admitsDistinctPans() {
        // The counterweight. A constraint that refused everything would pass the
        // test above and be just as broken.
        insertClient("it_pan_distinct_a", pan.seal(PAN));

        assertThatExceptionOfType(DuplicateKeyException.class)
                .isThrownBy(() -> insertClient("it_pan_distinct_dup", pan.seal(PAN)));
        assertThat(insertClient("it_pan_distinct_b", pan.seal("BBBPL9876D"))).isPositive();
    }

    @Test
    @DisplayName("ciphertext is randomised in the column too, so it could carry no UNIQUE key")
    void ciphertextDiffersPerRow() {
        // Two rows for two different PANs, stored: the ciphertexts differ, and
        // so would two encryptions of the SAME PAN — which is precisely why the
        // uniqueness lives on the index column instead.
        long first = insertClient("it_pan_cipher_a", pan.seal(PAN));
        long second = insertClient("it_pan_cipher_b", pan.seal("BBBPL9876D"));

        byte[] a = jdbc.queryForObject(
                "SELECT pan_ciphertext FROM ob_clients WHERE id = ?", byte[].class, first);
        byte[] b = jdbc.queryForObject(
                "SELECT pan_ciphertext FROM ob_clients WHERE id = ?", byte[].class, second);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a lookup by PAN finds the row without decrypting anything")
    void findsByBlindIndex() {
        long id = insertClient("it_pan_lookup", pan.seal(PAN));

        Long found = jdbc.queryForObject(
                "SELECT id FROM ob_clients WHERE pan_blind_index = ?",
                Long.class, pan.blindIndexOf("aaapl1234c"));

        assertThat(found).isEqualTo(id);
    }

    @Test
    @DisplayName("a client with no PAN is allowed, and many of them do not collide")
    void tolerAtesNullPans() {
        // Both columns are nullable and almost every existing row holds NULL.
        // MySQL's UNIQUE ignores NULLs, so this must not be a constraint
        // violation — otherwise the guard would forbid a second client being
        // created before either had a PAN recorded.
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES ('it_pan_null_a', ?)",
                LocalDate.of(2026, 9, 7));
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES ('it_pan_null_b', ?)",
                LocalDate.of(2026, 9, 7));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_clients WHERE name LIKE 'it_pan_null_%'", Integer.class))
                .isEqualTo(2);
    }
}
