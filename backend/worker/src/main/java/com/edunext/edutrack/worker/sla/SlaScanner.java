package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * D-020 · the fifteen-minute SLA scan · D-027 · with every duration from
 * Stream B's calendar.
 *
 * <p>Blueprint §16 item 3: a ticket past its Planned Close Date becomes
 * {@code CRITICAL}, is flagged delayed, and its Reporting Manager, Project
 * Manager and assignee are told. This is what walkthrough A step 11 exercises.
 *
 * <h2>Detection is a timestamp comparison. Reporting is not.</h2>
 *
 * <p>Whether a ticket has breached is {@code pcd_open < now}. The Planned Close
 * Date is an instant somebody committed to, and it passes when it passes,
 * whatever the working calendar says — running <em>detection</em> through
 * working hours would mean a Friday-18:00 deadline was not breached until
 * Monday, which is not what the client was promised.
 *
 * <p><strong>How far past it is</strong> is a duration, and every duration goes
 * through {@link WorkingHoursService} (D-027, and CLAUDE.md's "never write your
 * own date maths"). That distinction is the whole point of B-024, and blueprint
 * §5 calls getting it wrong the most commonly missed requirement in systems of
 * this kind — usually in the other direction, by starting an SLA clock on a
 * Friday evening and breaching it on Saturday morning.
 */
@Component
class SlaScanner {

    private static final Logger log = LoggerFactory.getLogger(SlaScanner.class);

    /**
     * A bound on one pass, not on the backlog.
     *
     * <p>The first run against a database nobody has scanned could match
     * thousands. Escalating all of them in one pass would hold the lock while
     * sending thousands of mails, so a pass takes a slice and the next takes
     * the rest. What was left is logged — a silent cap reads as "nothing else
     * was wrong", which is the opposite of true here.
     */
    private static final int MAX_PER_PASS = 500;

    private final SlaRepository tickets;
    private final SlaEscalation escalation;
    private final Clock clock;

    SlaScanner(SlaRepository tickets, SlaEscalation escalation, Clock clock) {
        this.tickets = tickets;
        this.escalation = escalation;
        this.clock = clock;
    }

    /**
     * <p>{@code lockAtMostFor} is deliberately close to the interval, because
     * the two failure modes are not symmetric. A lock released early lets a
     * second instance escalate tickets the first is still working through and
     * everybody gets the alert twice — the inbox that cried wolf, which is how
     * people learn to ignore breach mail. A lock held too long only delays the
     * next pass, and a late alert about an already-late ticket costs far less.
     */
    @Scheduled(fixedDelayString = "${edutrack.sla.scan-interval:PT15M}")
    @SchedulerLock(name = "slaScanner", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            // An exception escaping a @Scheduled method cancels every future
            // execution. The scanner would stop, nothing would say so, and the
            // next breach would simply never be announced.
            log.error("sla: scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many this pass escalated */
    int scanOnce() {
        Instant now = clock.instant();
        List<SlaRepository.BreachedTicket> breached = tickets.breached(now, MAX_PER_PASS);

        if (breached.size() == MAX_PER_PASS) {
            log.warn("sla: hit the {}-ticket cap in one pass; the rest follow next interval",
                    MAX_PER_PASS);
        }

        int escalated = 0;
        for (SlaRepository.BreachedTicket ticket : breached) {
            try {
                if (escalation.escalate(ticket, now)) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                // Per ticket, so one bad row cannot cost the rest of the pass.
                // The flag and its alerts roll back together, so this ticket is
                // simply picked up again next interval rather than left flagged
                // with nobody told.
                log.error("sla: could not escalate {}", ticket.ticketCode(), e);
            }
        }

        if (escalated > 0) {
            log.info("sla: escalated {} ticket(s) past their planned close date", escalated);
        }
        return escalated;
    }
}
