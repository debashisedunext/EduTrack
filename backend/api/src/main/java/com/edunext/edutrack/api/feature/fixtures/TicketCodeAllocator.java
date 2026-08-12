package com.edunext.edutrack.api.feature.fixtures;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * B-007 · issues fixture ticket codes in the exact {@code {PROJECT}-{YY}-{NNNNN}}
 * shape C-011 defined — {@code CRM-26-00347}.
 *
 * <p><b>This intentionally duplicates {@code api.feature.tickets.TicketCodeGenerator}
 * / {@code TicketCode} rather than calling them.</b> Both are package-private to
 * {@code feature.tickets} — Stream C's directory — and CLAUDE.md requires that
 * stream's sign-off before this package reaches into it. Rather than do that
 * quietly, or fork the visibility open without asking, this class re-implements
 * the same {@code LAST_INSERT_ID(ticket_seq + 1)} idiom against {@code projects}
 * and the same {@code "%s-%02d-%05d"} rendering. Flagging the duplication here
 * for whoever reviews this: the fix is either widening {@code TicketCodeGenerator}
 * to {@code public} (Divyansh's call — it is his package) or leaving the two
 * copies as they are, since {@code TicketIdGenerationIT} already guards the
 * original and nothing here needs the concurrency proof that class exists for —
 * this loader runs single-threaded at startup.
 *
 * <p>Continues the real counter, not a fixture-private one: {@code ticket_seq}
 * on {@code CRM}/{@code PAY}/{@code WEB} is left exactly where the corpus stops,
 * so the very next ticket a developer creates by hand through the real API
 * allocates {@code CRM-26-00071}, never re-using a fixture code.
 */
@Component
@Profile("fixtures")
class TicketCodeAllocator {

    private static final String ALLOCATE = """
            UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?
            """;

    private static final String READ_BACK = "SELECT LAST_INSERT_ID()";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    TicketCodeAllocator(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** Test seam. */
    TicketCodeAllocator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Allocates and renders the next code for a project. Must run inside the
     * caller's transaction — {@code LAST_INSERT_ID()} is connection-local, same
     * reasoning as {@code TicketCodeGenerator} — hence {@link Propagation#MANDATORY}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String next(long projectId, String projectCode) {
        int updated = jdbc.update(ALLOCATE, projectId);
        if (updated == 0) {
            throw new IllegalStateException("no project with id " + projectId + " — fixture data is inconsistent");
        }
        long sequence = jdbc.queryForObject(READ_BACK, Long.class);
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
        return "%s-%02d-%05d".formatted(projectCode, year % 100, sequence);
    }
}
