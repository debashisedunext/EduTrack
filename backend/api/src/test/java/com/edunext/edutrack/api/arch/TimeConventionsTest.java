package com.edunext.edutrack.api.arch;

import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * A-037 · "Time is UTC everywhere in storage" (CLAUDE.md), as a build failure.
 *
 * <p>Every SLA figure in this system is computed from stored instants against
 * the working calendar. A field typed {@link LocalDateTime} is a wall-clock
 * reading with no zone, so Hibernate writes it using whatever the JVM's default
 * happens to be and reads it back the same way — correct on a developer's
 * machine, correct in CI, and wrong by five and a half hours on a server in
 * another region. Nothing fails; a Friday-18:00 ticket with a four-hour SLA
 * simply breaches at the wrong moment, and the report that says so is the only
 * evidence.
 *
 * <p>{@link OffsetDateTime} and {@link ZonedDateTime} are refused for a
 * different reason. They are not ambiguous, they are <em>redundant</em>: the
 * column is {@code DATETIME(6)} holding UTC, so the offset they carry is
 * decoration that a reader will eventually take as permission to store a local
 * time with its offset alongside. {@link java.time.Instant} is the only one of
 * the five that cannot be read as a local time, which is precisely why it is
 * the convention.
 *
 * <p>{@link java.time.LocalDate} stays legal and is not an oversight — a
 * holiday, a leave day and an effort log's {@code work_date} are calendar dates
 * rather than instants, and forcing them through a timezone is how a day off
 * lands on the wrong day.
 *
 * <p>Clean today, across all 37 entities. This is the rule that keeps it that
 * way, and the moment it earns its keep is the one where somebody adds a column
 * and reaches for the type their IDE offers first.
 */
class TimeConventionsTest {

    @Test
    void noPersistentFieldStoresAWallClockReading() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat(
                        annotatedWith(Entity.class).or(annotatedWith(Embeddable.class)))
                .should().haveRawType(LocalDateTime.class)
                .orShould().haveRawType(OffsetDateTime.class)
                .orShould().haveRawType(ZonedDateTime.class)
                .orShould().haveRawType(java.util.Date.class)
                .orShould().haveRawType(java.util.Calendar.class)
                .orShould().haveRawType(java.sql.Timestamp.class)
                .because("""
                        storage is UTC and the column is DATETIME(6). Use Instant — it is \
                        the only one of these that cannot be misread as a local time, and \
                        every SLA and duration figure in the system is computed from it. \
                        LocalDate is fine for a calendar date such as work_date.""");

        rule.check(ProductionClasses.get());
    }
}
