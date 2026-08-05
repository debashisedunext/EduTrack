package com.edunext.edutrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;

/**
 * EduTrack — organisation task and client ticketing platform.
 *
 * <p>This class deliberately sits in the root package {@code com.edunext.edutrack}
 * so component scanning and Spring Boot autoconfiguration cover {@code api},
 * {@code domain} and {@code worker} without explicit {@code @EntityScan} or
 * {@code @EnableJpaRepositories}. Declaring those eagerly enables JPA repository
 * infrastructure even when no EntityManagerFactory exists, which breaks any test
 * that runs without a database.
 *
 * <p>Module layout (TEAM-PLAN.md §6): {@code api} and {@code worker} both depend
 * on {@code domain}; neither depends on the other. Features are packaged per
 * feature — {@code api/feature/tickets/} holds its own controller, service and
 * DTOs — rather than per layer. That is what keeps four developers out of each
 * other's files.
 */
@SpringBootApplication
@EnableAsync
public class EduTrackApplication {

    /**
     * Everything is stored and computed in UTC; user-facing timezone conversion
     * happens in the presentation layer only (CLAUDE.md, Conventions). If this
     * is ever removed, every SLA and duration figure in the system is wrong on
     * a machine outside UTC.
     */
    @PostConstruct
    void forceUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(EduTrackApplication.class, args);
    }
}
