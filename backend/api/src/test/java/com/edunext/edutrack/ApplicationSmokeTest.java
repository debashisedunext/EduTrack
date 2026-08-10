package com.edunext.edutrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skeleton smoke test: proves the Spring context wires and the configuration
 * parses, with no infrastructure running.
 *
 * <p>Flyway autoconfiguration is excluded deliberately so this passes on a
 * laptop with nothing installed — it opens a connection during context refresh
 * and offers no way not to. Real integration coverage arrives with
 * Testcontainers in A-013 (trigger negative tests), A-020
 * ({@code AuthLoginIT}) and A-045 (hash-chain concurrency) — all of which need
 * Docker.
 *
 * <p><b>JPA is no longer excluded (B-023)</b> — see the property block below.
 * It connected during refresh only to read JDBC metadata for dialect
 * selection, which is a lookup that can be refused.
 *
 * <p><b>The datasource is no longer excluded (A-020).</b> It was, until the
 * application grew its first bean that reads the database — {@code
 * AuthUserRepository} needs a {@code JdbcClient}, which needs a
 * {@code JdbcTemplate}, which needs a {@code DataSource}. Excluding it now
 * means this test asserts a context nobody runs.
 *
 * <p>Keeping it costs nothing here: Spring Boot builds the {@code HikariDataSource}
 * without starting its pool, and the pool opens on the first
 * {@code getConnection()} — which never happens in this test. So the context is
 * complete and still nothing dials MySQL.
 */
@SpringBootTest(properties = {
        // B-023, Stream B edit — flagged for Shivendra's sign-off rather than
        // made quietly (CLAUDE.md, code ownership).
        //
        // JPA is no longer excluded. `CalendarService` is the first bean in this
        // module to read through a Spring Data repository, so with
        // JpaRepositoriesAutoConfiguration off the context cannot build
        // `CalendarController` at all — the same argument A-020 made when it
        // dropped the datasource exclusions.
        //
        // The no-infrastructure promise is kept rather than traded away. What
        // made Hibernate dial MySQL during refresh was reading JDBC metadata to
        // pick a dialect; naming the dialect and refusing that lookup lets it
        // build an EntityManagerFactory offline. Flyway stays excluded — it
        // connects unconditionally, and there is no equivalent switch.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class ApplicationSmokeTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    /**
     * B-023 edit. Not an ID-string comparison — {@code TimeZone.getTimeZone("UTC")}
     * is not portable across JDK builds. CI (Ubuntu, Temurin 25) returns an
     * instance whose {@code getID()} is {@code "Etc/UTC"} for the identical call
     * that returns {@code "UTC"} elsewhere; both are the same zero-offset,
     * no-DST zone. {@link UtcIsSetBeforeAnyDateIsReadTest} carries the full
     * explanation of what this codebase actually needs from the default zone.
     */
    @Test
    void defaultTimeZoneIsUtc() {
        TimeZone zone = TimeZone.getDefault();
        assertThat(zone.getRawOffset()).isZero();
        assertThat(zone.observesDaylightTime()).isFalse();
    }
}
