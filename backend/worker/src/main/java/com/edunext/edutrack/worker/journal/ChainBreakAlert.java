package com.edunext.edutrack.worker.journal;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A-044 · one digest per run, never one message per break.
 *
 * <p>The alternative was tried elsewhere and is already known to fail here.
 * A-021 recorded that D-035 throttles the outbox to one mail per recipient per
 * minute — so three hundred break-notifications means one delivered and 299
 * dropped. That is the worst available failure for an alarm, because from the
 * outside it looks exactly like it fired.
 *
 * <h2>The ratio is the most useful thing in the message</h2>
 *
 * <p>Three broken rows across two tickets is plausibly tampering. Fifty
 * thousand is not — nobody edits fifty thousand rows, and the overwhelmingly
 * likely cause is that {@code ChainPayloads} changed without
 * {@link com.edunext.edutrack.domain.journal.ChainPayloads#VERSION} being
 * bumped, which is the exact scenario A-042 added that marker for. Those two
 * want opposite first moves, and a digest that only says "50,000 breaks" sends
 * somebody hunting an intruder for a day. So the message says which it looks
 * like, and why.
 *
 * <h2>Counts by kind, then a bounded sample</h2>
 *
 * <p>Every kind is listed with its count, because that is small and always
 * useful. Individual breaks are capped: a message nobody can read to the end is
 * a message nobody reads. The full set stays in the run's log.
 */
@Component
class ChainBreakAlert {

    private static final Logger log = LoggerFactory.getLogger(ChainBreakAlert.class);

    private static final String ADMIN_ROLE = "ADMIN";

    /** Enough to characterise the failure, few enough that the mail stays readable. */
    static final int SAMPLE_SIZE = 20;

    /**
     * Above this share of scanned tickets, the message leads with "check the
     * payload builder" rather than "investigate tampering". Deliberately high:
     * the claim is only worth making when the alternative is absurd.
     */
    private static final double CODE_CHANGE_RATIO = 0.5;

    private final NotificationWriter notifications;

    ChainBreakAlert(NotificationWriter notifications) {
        this.notifications = notifications;
    }

    /**
     * @param breaks         every break the run found; nothing is sent when empty
     * @param ticketsScanned how many tickets the run covered, for the ratio
     */
    void raise(List<ChainBreak> breaks, int ticketsScanned) {
        if (breaks.isEmpty()) {
            return;
        }
        try {
            String title = title(breaks);
            String body = body(breaks, ticketsScanned);

            // Logged whatever happens next. The bell and the mail can both fail
            // — no admin seeded, notifications table unreachable — and a chain
            // break that was detected and then silently not reported is the
            // worst outcome this class can produce.
            log.error("chain verification: {}\n{}", title, body);

            List<Long> admins = notifications.activeUsersInRole(ADMIN_ROLE);
            if (admins.isEmpty()) {
                log.error("chain verification: {} breaks found and no active ADMIN to notify",
                        breaks.size());
                return;
            }
            for (Long admin : admins) {
                notifications.write(new NewNotification(
                        admin, null, NotificationEvent.CHAIN_VERIFICATION_FAILED, title, body, null));
            }
        } catch (RuntimeException e) {
            // Never propagates: the scanner has finished its work by now, and
            // failing here would turn "we could not send the alert" into "the
            // run failed", losing the anchors it legitimately wrote.
            log.error("chain verification: could not raise the break digest", e);
        }
    }

    private static String title(List<ChainBreak> breaks) {
        Set<Long> tickets = new TreeSet<>();
        breaks.forEach(b -> tickets.add(b.ticketId()));
        return "chain verification — " + breaks.size() + (breaks.size() == 1 ? " break" : " breaks")
                + " across " + tickets.size() + (tickets.size() == 1 ? " ticket" : " tickets");
    }

    private static String body(List<ChainBreak> breaks, int ticketsScanned) {
        Map<ChainBreak.Kind, Integer> byKind = new LinkedHashMap<>();
        Set<Long> tickets = new TreeSet<>();
        for (ChainBreak b : breaks) {
            byKind.merge(b.kind(), 1, Integer::sum);
            tickets.add(b.ticketId());
        }

        StringBuilder body = new StringBuilder();
        byKind.forEach((kind, count) -> body.append(kind).append(": ").append(count).append('\n'));

        if (ticketsScanned > 0 && tickets.size() > ticketsScanned * CODE_CHANGE_RATIO) {
            body.append('\n')
                    .append(tickets.size()).append(" of ").append(ticketsScanned)
                    .append(" tickets scanned have a break. At this ratio the likely cause is a "
                            + "change to the hashed column set without a ChainPayloads.VERSION "
                            + "bump, not tampering — check that before opening an investigation.\n");
        }

        body.append('\n');
        breaks.stream().limit(SAMPLE_SIZE).forEach(b -> body.append(b).append('\n'));
        if (breaks.size() > SAMPLE_SIZE) {
            body.append("… and ").append(breaks.size() - SAMPLE_SIZE)
                    .append(" more, in the worker log.\n");
        }
        return body.toString();
    }
}
