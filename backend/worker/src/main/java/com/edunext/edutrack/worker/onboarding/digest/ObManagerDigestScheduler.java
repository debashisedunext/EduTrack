package com.edunext.edutrack.worker.onboarding.digest;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * B-114 · one mail a day to a manager, listing the journeys that have stopped
 * moving.
 *
 * <h2>What it is for</h2>
 *
 * <p>Everything else §7 sends fires because something happened to one step, and
 * goes to the one person who can act on that step. Nobody is told what is
 * <em>not</em> happening. A step that quietly sits in WAITING_ON_CLIENT for
 * three weeks generates no event at all after the first, because §5.7 stops its
 * clock and nothing further is due — the system is behaving exactly as designed
 * and the implementation has stalled anyway. This is the mail that says so.
 *
 * <h2>Three rules taken from D-038, because they were argued once</h2>
 *
 * <ol>
 *   <li><strong>The clock is the organisation's, not the server's.</strong> The
 *       cron carries an explicit zone, day boundaries are computed from it, and
 *       {@link #warnIfZoneDisagrees} says so when an Admin moves the working
 *       calendar underneath a cron whose zone was fixed at bean creation. The
 *       zone defaults to {@code edutrack.digest.zone} rather than to a literal,
 *       so an organisation with one working day does not get two digest hours.</li>
 *   <li><strong>No digest on a non-working day.</strong> A Sunday digest is
 *       noise, and noise is what teaches people to filter digests — after which
 *       the one that mattered is filtered too.</li>
 *   <li><strong>Nobody is told they have nothing.</strong> A manager with no
 *       stuck step is not mailed. The absence is the good news, and printing it
 *       spends the reader's attention on nothing.</li>
 * </ol>
 *
 * <h2>One mail a day, and where that is actually enforced</h2>
 *
 * <p>Three things hold it, and none of them is sufficient alone.
 * {@code @SchedulerLock} stops two instances running the same 08:30.
 * A-107's unique index over {@code queued_dedupe_key} stops a second copy while
 * the first is still in the queue. Neither survives the case that matters — the
 * worker restarting at 08:45, after the morning's digests have already been
 * sent and their dedupe keys have gone NULL. So the day is part of the key and
 * {@link ObDigestRepository#alreadyQueued} checks it over every status, which
 * is what makes a hand-run of {@link #sendManagerDigest()} safe.
 *
 * <h2>It goes through the outbox, not through the mail engine</h2>
 *
 * <p>B-110's queue, so a digest gets the same retry ladder, the same
 * suppression check and the same failure notice as a sign-off request. The
 * alternative — calling {@code ObMailTransport} directly, as this class has no
 * business transaction to join — would make the digest the one onboarding mail
 * that vanishes when the SMTP server is briefly unreachable.
 */
@Component
public class ObManagerDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(ObManagerDigestScheduler.class);

    /**
     * The most rows one digest carries.
     *
     * <p>Two limits meet here and both are real. A manager with two hundred
     * stuck steps needs a conversation rather than a longer mail, and nothing
     * past the first screenful of a digest is read. And the rows are stored:
     * they sit in {@code ob_notification_outbox.payload} until the row is
     * pruned, so an unbounded list is an unbounded JSON document per manager
     * per day.
     *
     * <p>{@code stuck_count} in the payload stays the true total, so the table
     * says how many it left out rather than quietly showing twenty-five and
     * implying that is all of them.
     */
    private static final int MAX_ROWS = 25;

    /** "22 Sep 2026" — the form the onboarding layout's facts table already prints. */
    private static final DateTimeFormatter DUE_ON =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final ObDigestRepository digests;
    private final ObDigestCalendar calendar;
    private final ObOutboxEnqueuer outbox;
    private final ZoneId zone;
    private final int thresholdDays;
    private final Clock clock;

    ObManagerDigestScheduler(
            ObDigestRepository digests,
            ObDigestCalendar calendar,
            ObOutboxEnqueuer outbox,
            @Value("${edutrack.onboarding.digest.zone:${edutrack.digest.zone:Asia/Kolkata}}")
            String zone,
            @Value("${edutrack.onboarding.digest.stuck-after-working-days:2}")
            int thresholdDays,
            Clock clock) {
        this.digests = digests;
        this.calendar = calendar;
        this.outbox = outbox;
        this.zone = ZoneId.of(zone);
        // A threshold of zero would mail a manager about a step that became
        // overdue an hour ago, which is a TAT_BREACHED event's job and already
        // in their inbox. Floored rather than rejected: a misconfiguration
        // should not stop the worker from starting.
        this.thresholdDays = Math.max(thresholdDays, 1);
        this.clock = clock;
    }

    @Scheduled(cron = "${edutrack.onboarding.digest.cron:0 30 8 * * *}",
               zone = "${edutrack.onboarding.digest.zone:${edutrack.digest.zone:Asia/Kolkata}}")
    @SchedulerLock(name = "obManagerDigest", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void daily() {
        try {
            sendManagerDigest();
        } catch (RuntimeException e) {
            // An exception escaping a @Scheduled method cancels every future
            // execution of it — the digest would stop for good, silently.
            log.error("ob-digest: run failed, retrying at the next schedule", e);
        }
    }

    /**
     * One run. Package-visible so a test can drive it against a fixed clock
     * rather than waiting for 08:30.
     *
     * @return how many digests were queued
     */
    public int sendManagerDigest() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = clock.instant();

        // A window wide enough for the walk back and for measuring the oldest
        // stall the digest will report. Bounded on purpose: the snapshot exists
        // so the calendar is read once, not so it can answer any question.
        ObDigestCalendar.Snapshot snapshot =
                calendar.snapshot(today.minusYears(1), today.plusDays(1));
        if (!snapshot.isWorkingDay(today)) {
            log.debug("ob-digest: {} is not a working day — no digest", today);
            return 0;
        }
        warnIfZoneDisagrees(snapshot);

        Instant cutoff = snapshot.workingDaysBefore(now, thresholdDays);
        List<ObDigestRepository.StuckStep> stuck = digests.stuckSteps(cutoff);

        int unattributed = digests.unattributedStuckSteps(cutoff);
        if (unattributed > 0) {
            // Not a failure, and not something to hide either: these steps are
            // stuck and nobody is being told. B-113's escalation matrix is what
            // closes it — until then this is the only place the gap is visible.
            log.warn("ob-digest: {} stuck step(s) resolved to no manager and reached nobody — "
                            + "the owner has no reporting manager, or the step has no owner",
                    unattributed);
        }

        if (stuck.isEmpty()) {
            log.info("ob-digest: nothing stuck past {} working day(s) on {}", thresholdDays, today);
            return 0;
        }

        Map<Long, List<ObDigestRepository.StuckStep>> byManager = groupByManager(stuck);
        Map<Long, String> keys = new LinkedHashMap<>();
        byManager.keySet().forEach(id -> keys.put(id, dedupeKey(id, today)));
        Set<String> already = digests.alreadyQueued(
                ObNotificationEvent.MANAGER_DIGEST.key(), new LinkedHashSet<>(keys.values()));

        int queued = 0;
        for (Map.Entry<Long, List<ObDigestRepository.StuckStep>> entry : byManager.entrySet()) {
            String key = keys.get(entry.getKey());
            if (already.contains(key)) {
                log.debug("ob-digest: manager {} already has today's digest — not queued again",
                        entry.getKey());
                continue;
            }
            if (enqueue(entry.getValue(), key, snapshot, today)) {
                queued++;
            }
        }
        log.info("ob-digest: queued {} manager digest(s) for {} covering {} stuck step(s)",
                queued, today, stuck.size());
        return queued;
    }

    // ─────────────────────────────────────────────────────────────── grouping

    /**
     * One entry per manager, rows in the order the query returned them — oldest
     * stall first, which is the order the mail should read in.
     */
    private static Map<Long, List<ObDigestRepository.StuckStep>> groupByManager(
            List<ObDigestRepository.StuckStep> stuck) {
        Map<Long, List<ObDigestRepository.StuckStep>> byManager = new LinkedHashMap<>();
        for (ObDigestRepository.StuckStep step : stuck) {
            byManager.computeIfAbsent(step.managerId(), id -> new ArrayList<>()).add(step);
        }
        return byManager;
    }

    // ────────────────────────────────────────────────────────────── one digest

    private boolean enqueue(List<ObDigestRepository.StuckStep> rows, String dedupeKey,
                            ObDigestCalendar.Snapshot snapshot, LocalDate today) {
        ObDigestRepository.StuckStep first = rows.getFirst();

        // Every row counts towards the client tally — the summary is about all
        // of them — but only the first MAX_ROWS are written into the payload.
        // They are the oldest stalls, which is the right end to truncate.
        Set<Long> clients = new LinkedHashSet<>();
        List<Map<String, Object>> lines = new ArrayList<>(Math.min(rows.size(), MAX_ROWS));
        for (ObDigestRepository.StuckStep row : rows) {
            clients.add(row.obClientId());
            if (lines.size() < MAX_ROWS) {
                lines.add(lineFor(row, snapshot, today));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stuck_count", rows.size());
        payload.put("client_count", clients.size());
        payload.put("threshold", workingDays(thresholdDays));
        payload.put("oldest_stalled_for", stalledFor(first, snapshot, today));
        // The rows themselves. Not a {{variable}} — ObMailRenderer drops
        // non-scalar payload values by design, and ObDigestBody is what turns
        // this array into the table.
        payload.put(ObNotificationEvent.STUCK_ROWS, lines);

        // No client id, no journey, no step: a digest is about many of each,
        // and naming one of them in the row would put that client's name in the
        // layout header as though the mail were only about them.
        ObNotification notification = new ObNotification(
                ObNotificationEvent.MANAGER_DIGEST.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(first.managerId()),
                null, null, null, payload, dedupeKey);

        return outbox.enqueue(notification).isPresent();
    }

    /**
     * One line of the table.
     *
     * <p>Everything is a string except nothing — the composer prints these
     * verbatim after escaping, and a date formatted in the worker's default
     * locale halfway down a mail is the kind of thing nobody notices until a
     * container moves.
     */
    private Map<String, Object> lineFor(ObDigestRepository.StuckStep row,
                                        ObDigestCalendar.Snapshot snapshot, LocalDate today) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("client", row.clientName());
        line.put("product", row.productName());
        line.put("step", row.stepName());
        line.put("owner", row.ownerName() == null ? "" : row.ownerName());
        line.put("state", state(row.status()));
        line.put("stalled_for", stalledFor(row, snapshot, today));
        line.put("due_on", row.dueAt() == null
                ? ""
                : DUE_ON.format(LocalDateTime.ofInstant(row.dueAt(), zone)));
        return line;
    }

    /**
     * What the reader is looking at, in their words rather than the column's.
     *
     * <p>The distinction is the point of carrying it: "waiting on client" is a
     * phone call to the client, "blocked" is a conversation with the owner, and
     * "in progress" past its date is neither — it is a step nobody has recorded
     * anything about.
     */
    private static String state(String status) {
        return switch (status) {
            case "WAITING_ON_CLIENT" -> "Waiting on client";
            case "BLOCKED" -> "Blocked";
            default -> "Overdue";
        };
    }

    private String stalledFor(ObDigestRepository.StuckStep row,
                              ObDigestCalendar.Snapshot snapshot, LocalDate today) {
        if (row.stalledSince() == null) {
            return "";
        }
        LocalDate since = LocalDateTime.ofInstant(row.stalledSince(), zone).toLocalDate();
        int days = snapshot.workingDaysBetween(since, today);
        return days == 0 ? "today" : workingDays(days);
    }

    private static String workingDays(int days) {
        return days + (days == 1 ? " working day" : " working days");
    }

    /**
     * {@code MANAGER_DIGEST:EMAIL:day:20707:user:42} — the convention
     * {@code ObNotification.dedupeKeyFor} exists to keep callers with an
     * unusual subject consistent with. The subject is the day, which is what
     * makes "one a day" a property of the key rather than of the caller
     * remembering.
     */
    private static String dedupeKey(long managerId, LocalDate day) {
        return ObNotification.dedupeKeyFor(
                ObNotificationEvent.MANAGER_DIGEST.key(), ObChannel.EMAIL,
                "day", day.toEpochDay(), new ObRecipient.Staff(managerId));
    }

    /**
     * The cron's zone is fixed at startup; the calendar's is a row. If somebody
     * changes one, this is what says so — rather than the digest arriving at
     * the wrong hour with nothing anywhere explaining why.
     */
    private void warnIfZoneDisagrees(ObDigestCalendar.Snapshot snapshot) {
        if (!snapshot.zone().equals(zone)) {
            log.warn("ob-digest: the schedule runs in {} but the working calendar says {} — "
                            + "the digest is going out at the wrong local time. "
                            + "Set edutrack.onboarding.digest.zone to match, and restart.",
                    zone, snapshot.zone());
        }
    }
}
