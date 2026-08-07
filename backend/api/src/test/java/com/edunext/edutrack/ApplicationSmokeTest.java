package com.edunext.edutrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skeleton smoke test: proves the Spring context wires and the configuration
 * parses, with no infrastructure running.
 *
 * <p>JPA and Flyway autoconfiguration are excluded deliberately so this passes
 * on a laptop with nothing installed — both open a connection during context
 * refresh. Real integration coverage arrives with Testcontainers in A-013
 * (trigger negative tests), A-020 ({@code AuthLoginIT}) and A-045 (hash-chain
 * concurrency) — all of which need Docker.
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
@SpringBootTest
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class ApplicationSmokeTest {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    @Test
    void defaultTimeZoneIsUtc() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
