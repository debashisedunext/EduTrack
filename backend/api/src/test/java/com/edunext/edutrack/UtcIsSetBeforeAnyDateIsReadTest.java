package com.edunext.edutrack;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-023 · the UTC default must be set <em>before</em> JPA and JDBC start, not
 * during context refresh.
 *
 * <h2>Why this is a structural test and not a behavioural one</h2>
 *
 * <p>{@code ApplicationSmokeTest#defaultTimeZoneIsUtc} already asserts the zone
 * <em>is</em> UTC — and it passed for months while dates were being read back a
 * day early. It could not have caught this: by the time any test method runs,
 * the old {@code @PostConstruct} had executed and the value was correct. The
 * defect was never the value, it was the moment.
 *
 * <p>The behavioural version — store a {@code LocalDate}, read it back, compare
 * — is the check that actually found this, by running the application against
 * real MySQL on a machine in IST. It is deliberately <b>not</b> reproduced here,
 * because it would be worthless as a guard: CI runners and Testcontainers hosts
 * are UTC, where a zone-conversion bug cannot show itself. A green
 * always-passing test is worse than none, since it reads as coverage.
 *
 * <p>So this asserts the mechanism instead. A static initialiser runs at class
 * load — before {@code SpringApplication.run} in {@code main}, and before the
 * context builds an {@code EntityManagerFactory} in a test. A
 * {@code @PostConstruct} runs after both, which is how
 * {@code holidays.holiday_date} came back as 2026-12-24 for a row stored as
 * 2026-12-25.
 */
class UtcIsSetBeforeAnyDateIsReadTest {

    @Test
    @DisplayName("the default zone is UTC once the application class is loaded")
    void defaultZoneIsUtc() {
        // Referencing the class guarantees its static initialiser has run.
        assertThat(EduTrackApplication.class).isNotNull();

        // Not an ID-string comparison. TimeZone.getTimeZone("UTC").getID() is not
        // portable across JDK builds: CI (Ubuntu, Temurin 25) returns "Etc/UTC"
        // for the identical call that returns "UTC" on this codebase's other
        // dev machines. Both are the same zero-offset, no-DST zone — the alias
        // name is a JDK/tzdata implementation detail this test has no business
        // asserting on. What the comment above and PLAN.md §3.1 actually require
        // is the offset, not the label, so that is what gets checked.
        TimeZone zone = TimeZone.getDefault();
        assertThat(zone.getRawOffset())
                .as("storage is UTC everywhere (PLAN.md §3.1); a JVM on another zone "
                        + "silently shifts every DATE and DATETIME the app reads")
                .isZero();
        assertThat(zone.observesDaylightTime())
                .as("a DST-observing zone would still shift dates twice a year even at "
                        + "nominal zero offset")
                .isFalse();
    }

    /**
     * The regression itself. Moving this back to a lifecycle callback would
     * restore the day-early bug, and nothing else in the suite would notice.
     */
    @Test
    @DisplayName("UTC is not set from a lifecycle callback — that runs after Hibernate reads it")
    void utcIsNotSetFromALifecycleCallback() {
        Method[] lifecycleMethods = Arrays.stream(EduTrackApplication.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostConstruct.class))
                .toArray(Method[]::new);

        assertThat(lifecycleMethods)
                .as("""
                        EduTrackApplication declares a @PostConstruct. If it sets the default \
                        timezone, it runs too late: Hibernate and Connector/J have already \
                        captured the JVM zone by then, and every LocalDate read back is shifted \
                        by the host's offset. Use the static initialiser instead.""")
                .isEmpty();
    }
}
