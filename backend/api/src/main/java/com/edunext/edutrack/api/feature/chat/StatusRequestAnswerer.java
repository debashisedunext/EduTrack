package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * D-056 · a reply closes the status requests it answers, and the wait is
 * measured.
 *
 * <p>Deliberately a separate component from {@link StatusRequestService} rather
 * than a method on it. The ask side calls {@link ChatService} to post its card;
 * the answer side is called <em>by</em> {@code ChatService} on every message.
 * One class doing both would be a circular bean dependency, and breaking it
 * with {@code @Lazy} would hide a real design point — these are two directions
 * of the same feature and only one of them may know about chat.
 */
@Component
class StatusRequestAnswerer {

    private static final Logger log = LoggerFactory.getLogger(StatusRequestAnswerer.class);

    private final StatusRequestRepository requests;
    private final StatusRequestNotifier notifier;
    private final WorkingHoursService workingHours;

    StatusRequestAnswerer(StatusRequestRepository requests,
                          StatusRequestNotifier notifier,
                          WorkingHoursService workingHours) {
        this.requests = requests;
        this.notifier = notifier;
        this.workingHours = workingHours;
    }

    /**
     * Close whatever this message answers.
     *
     * <p>Runs inside the caller's transaction, so a request cannot be recorded
     * as answered by a message that was rolled back.
     *
     * @param senderName for the manager's notification; the reply's author
     */
    void messagePosted(long threadId, long senderId, long messageId, String senderName) {
        // Cheap EXISTS first. This runs on every chat message in the system and
        // the answer is nearly always no; a thread with nothing outstanding
        // should not pay for a join to tickets, still less take row locks for
        // an UPDATE that would match nothing.
        if (!requests.hasOpenRequests(threadId)) {
            return;
        }

        Instant answeredAt = Instant.now();
        for (StatusRequestRepository.AnswerableRow row : requests.answerable(threadId, senderId)) {
            Instant requestedAt = row.requestedAt().toInstant();
            int workingMins = workingMinutes(requestedAt, answeredAt, row);

            // The rowcount is the claim. Two replies landing together must raise
            // one notification to the manager, not two — the same discipline
            // D-020 and D-022 use, and the D-022 double-nudge that taught it.
            if (!requests.close(row.id(), messageId, senderId, answeredAt, workingMins)) {
                continue;
            }
            notifier.answered(row.requestedById(), row.ticketId(), row.ticketCode(),
                    threadId, senderName);
        }
    }

    /**
     * How long the manager actually waited, in working minutes.
     *
     * <p>Through B-024, because CLAUDE.md and D-027 say every duration is —
     * <em>"a Friday-18:00 ticket with a 4-hour SLA must not breach on Saturday
     * morning"</em>, and a response-time scorecard that charges somebody for a
     * weekend is the same mistake wearing different clothes. The resource's own
     * approved leave counts too, via {@code askedOfId}: the clock started
     * against the person we asked, and a week they were signed off is not a week
     * they were ignoring their manager.
     *
     * <p>Stamped once, here, and never recomputed. Holidays get added and leave
     * is approved retrospectively, so re-deriving an old figure against today's
     * calendar would silently restate a number somebody has already reported.
     *
     * <p>A failure to compute must not lose the answer. The reply is the thing
     * that matters and it is already written; a request left open because the
     * calendar could not be read would sit in a manager's list forever, so the
     * duration falls back to zero, is logged, and the two instants on the row
     * remain the evidence.
     */
    private int workingMinutes(Instant requestedAt,
                               Instant answeredAt,
                               StatusRequestRepository.AnswerableRow row) {
        try {
            BigDecimal hours = workingHours.workingHoursBetween(
                    requestedAt, answeredAt, row.projectId(), row.askedOfId());
            return hours.multiply(BigDecimal.valueOf(60))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
        } catch (RuntimeException e) {
            log.error("chat: could not measure the response time for status request {} "
                    + "(ticket {}); recorded as 0 working minutes", row.id(), row.ticketCode(), e);
            return 0;
        }
    }
}
