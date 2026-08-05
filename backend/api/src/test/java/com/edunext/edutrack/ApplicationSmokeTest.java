package com.edunext.edutrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skeleton smoke test: proves the Spring context wires and the configuration
 * parses, with no infrastructure running.
 *
 * <p>Datasource, JPA and Flyway autoconfiguration are excluded deliberately so
 * this passes on a laptop with nothing installed. Real integration coverage
 * arrives with Testcontainers in A-013 (trigger negative tests) and A-045
 * (hash-chain concurrency) — both of which need Docker.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
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
