package com.edunext.edutrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Clock;
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

    /*
     * Everything is stored and computed in UTC; user-facing timezone conversion
     * happens in the presentation layer only (CLAUDE.md, Conventions). If this
     * is ever removed, every SLA and duration figure in the system is wrong on
     * a machine outside UTC.
     *
     * ---------------------------------------------------------------------
     * B-023, Stream B edit — flagged for Shivendra's sign-off rather than made
     * quietly (CLAUDE.md, code ownership).
     *
     * THIS WAS A @PostConstruct AND THAT WAS TOO LATE.
     *
     * A @PostConstruct on the application bean runs during context refresh —
     * after Hibernate has built its EntityManagerFactory and after Connector/J
     * has captured the JVM's default zone. Both keep the value they read at
     * startup, so setting it afterwards changed what the code saw and nothing
     * about how dates were read back.
     *
     * The symptom on a machine in IST, found by running the app against real
     * MySQL for B-023:
     *
     *     holidays.holiday_date in MySQL   2026-12-25
     *     GET /masters/holidays returns    2026-12-24
     *
     * Every LocalDate read back was a day early. Nothing caught it because no
     * feature had read a DATE column until the working calendar, and because
     * Testcontainers tests and CI both run on UTC hosts where the bug cannot
     * appear. The giveaway was in plain sight — the application's own log
     * timestamps read +05:30.
     *
     * A static initialiser runs at class load, which is before Spring starts
     * in main() and before the context builds anything in a test. That is
     * early enough. Verified both ways: with this in place the API and the
     * database agree; with the @PostConstruct they differed by a day.
     *
     * A JVM-level -Duser.timezone=UTC would also fix it, but only where
     * somebody remembers to pass it — which is not a property the codebase can
     * rely on. ApplicationSmokeTest#defaultTimeZoneIsUtc still guards the
     * value; UtcIsSetBeforeAnyDateIsReadTest guards the timing.
     * ---------------------------------------------------------------------
     */
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(EduTrackApplication.class, args);
    }

    /**
     * A-121 · UTC, and the reason it is here rather than in a feature package.
     *
     * <p>{@code WorkerApplication} has declared this since D-010 and
     * {@code api} never did, so {@code ObOutboxEnqueuer} — whose own javadoc
     * says "{@code api} enqueues from inside business transactions" — could
     * not actually be injected into anything in this module. It is
     * {@code @Lazy}, so nothing noticed: the bean was never created, and the
     * first constructor to ask for it failed the whole context with
     * "No qualifying bean of type java.time.Clock".
     *
     * <p><b>This changes no existing behaviour.</b> Every class in {@code api}
     * that takes a {@code Clock} does so on a secondary test-seam constructor
     * and marks the no-arg one {@code @Autowired}, which is explicit and wins
     * over any bean; they all keep the {@code Clock.systemUTC()} they build
     * themselves. What changes is that a {@code Clock} can now be resolved
     * where a class has no such fallback, which is the case for every JDBC
     * bean {@code domain} shares with the worker.
     *
     * <p>{@code @ConditionalOnMissingBean} for {@code WorkerApplication}'s
     * reason: a test that wants a fixed clock swaps one in rather than working
     * around this one.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
