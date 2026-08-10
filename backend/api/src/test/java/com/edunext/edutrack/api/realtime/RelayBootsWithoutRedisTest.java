package com.edunext.edutrack.api.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application must start when Redis is unreachable.
 *
 * <p>This is a regression test for a real outage-shaped bug. D-012's relay
 * subscribed during the lifecycle start phase, so a failure to reach the broker
 * propagated out of {@code SpringApplication.run} and the process exited — the
 * whole api, every REST endpoint, gone because one degradable subsystem could
 * not reach one dependency. CI's packaging job boots the jar with no
 * infrastructure at all and caught it; nothing in the test suite did, which is
 * why develop stayed red across nine merges before anyone noticed.
 *
 * <p>Port 1 is used rather than a plausible-looking one: it is privileged and
 * nothing will ever be listening, so this cannot pass by accidentally finding a
 * real Redis on a developer's machine.
 *
 * <p>No Testcontainers here on purpose. The whole point is the absence of a
 * broker, so this runs in surefire on any machine, in a second, and guards the
 * boot path on every build rather than only when Docker is available.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=250ms",
        "management.health.redis.enabled=false",
        // B-023, Stream B edit — flagged for Debashis's sign-off rather than
        // made quietly (CLAUDE.md, code ownership).
        //
        // JPA is no longer excluded: `CalendarService` is the first bean here to
        // read through a Spring Data repository, so without it the context
        // cannot build `CalendarController`. This test's own point — that the
        // app boots when infrastructure is missing — is unaffected: naming the
        // dialect and refusing the JDBC metadata lookup lets Hibernate build an
        // EntityManagerFactory without connecting, so MySQL is as absent here as
        // Redis is.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class RelayBootsWithoutRedisTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    RedisMessageListenerContainer relayContainer;

    @Test
    void theContextStartsWithNoBrokerReachable() {
        assertThat(context).isNotNull();
    }

    @Test
    void theRelayIsStillWiredRatherThanQuietlyDropped() {
        // Deliberately NOT asserting on relayContainer.isRunning(). The
        // container flips that flag in start(), before the subscription is
        // established, so it reports true here even though the connection
        // failed — which is exactly why RelayStarter tracks success itself
        // instead of trusting it.
        //
        // What matters is that the relay is still wired: a future change must
        // not be able to "fix" the boot by removing realtime altogether.
        assertThat(relayContainer).isNotNull();
        assertThat(context.getBean(RealtimePublisher.class)).isNotNull();
        assertThat(context.getBean(RealtimeRelay.RelayListener.class)).isNotNull();
    }
}
