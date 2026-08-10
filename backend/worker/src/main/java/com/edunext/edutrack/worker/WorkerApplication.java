package com.edunext.edutrack.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
 *
 * <h2>{@code @EnableJpaRepositories} / {@code @EntityScan} are not redundant here</h2>
 *
 * {@code scanBasePackages} widens {@code @ComponentScan} to the root package,
 * which is how a plain {@code domain} bean — {@code OutboxEnqueuer}, now
 * {@code WorkingHoursService} (B-024) — gets found. Spring Data JPA does not
 * follow it: repository and entity scanning key off Spring Boot's
 * {@code AutoConfigurationPackages}, which is registered from the package of
 * <em>this</em> class — {@code com.edunext.edutrack.worker} — regardless of
 * {@code scanBasePackages}. {@code EduTrackApplication} never needed the
 * explicit annotations because it sits in the root package itself; this class
 * does not, so it has to say so. Without it, no {@code domain} {@code
 * JpaRepository} — {@code WorkingCalendarRepository} among them — is ever
 * registered as a bean here, which stayed invisible only because nothing in
 * {@code worker} had asked Spring Data for one before.
 */
@SpringBootApplication(scanBasePackages = "com.edunext.edutrack")
@EnableJpaRepositories(basePackages = "com.edunext.edutrack")
@EntityScan(basePackages = "com.edunext.edutrack")
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
