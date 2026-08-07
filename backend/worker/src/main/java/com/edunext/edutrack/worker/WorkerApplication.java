package com.edunext.edutrack.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * The worker process — SLA scanners, the mail outbox, digests.
 *
 * <p>Deployed either as its own process or, in small installations, inside
 * {@code api} behind a profile flag (PLAN.md §2.3). {@code api} and
 * {@code worker} both depend on {@code domain} and neither depends on the
 * other, so anything they share lives in {@code domain}.
 *
 * <p>Flyway is disabled here (see {@code application.yml}): {@code api} owns
 * running migrations. Two processes racing to migrate the same schema on
 * deploy is a real failure mode, and Flyway's lock turns it into a slow
 * startup rather than a clean one.
 */
// Scans from the root rather than from `…worker`, so shared domain services —
// OutboxEnqueuer among them — are picked up. This matches EduTrackApplication,
// which scans the same root; api is not on this module's classpath.
@SpringBootApplication(scanBasePackages = "com.edunext.edutrack")
@ConfigurationPropertiesScan
@EnableScheduling
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    /**
     * Injected rather than reached for statically, so scheduling behaviour —
     * leases, backoff windows — is testable without sleeping.
     * {@code @ConditionalOnMissingBean} lets a test swap in a fixed clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
