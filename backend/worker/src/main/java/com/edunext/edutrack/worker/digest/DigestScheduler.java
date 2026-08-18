package com.edunext.edutrack.worker.digest;

import com.edunext.edutrack.domain.masters.HolidayRepository;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * D-038 · the daily digest at 08:30 and the weekly manager summary (§4B.6).
 *
 * <h2>Why these are not "mail events"</h2>
 *
 * <p>The other thirteen rows of §4B.6 fire because something happened to a
 * ticket. These two fire because it is 08:30. That is why this task did not
 * need D-037's producers and could be built while they wait on Stream C — the
 * dependency the plan inferred between them was sequence, not need.
 *
 * <h2>The clock is the organisation's, not the server's</h2>
 *
 * <p>"08:30" means 08:30 where the team is. Storage is UTC everywhere
 * (CLAUDE.md), so the cron carries an explicit zone and the day boundaries are
 * computed from it — never from the JVM default, which is whatever the
 * container happened to be started with, and never from MySQL date functions,
 * which would put a second silent timezone assumption beside the calendar's.
 *
 * <p>The zone is a property rather than a read of {@link WorkingCalendar},
 * because Spring resolves a cron zone at bean creation and the calendar is a
 * row that can change at runtime. {@link #warnIfZoneDisagrees()} exists so the
 * two cannot drift silently: if an Admin moves the org's timezone, the log says
 * so on the next run rather than the digest quietly arriving at the wrong hour.
 *
 * <h2>Not on a day nobody is working</h2>
 *
 * <p>A digest on a Sunday or a public holiday is noise, and noise is what
 * teaches people to filter digests — after which the one that mattered is
 * filtered too. Weekly-off days and org holidays are both honoured, which is
 * the same working calendar B-024 owns and every SLA calculation already uses.
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    /** "week of 03 Aug", exactly as §4B.6 writes it. */
    private static final DateTimeFormatter WEEK_OF =
            DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    private final DigestRepository digests;
    private final OutboxEnqueuer outbox;
    private final WorkingCalendarRepository calendars;
    private final HolidayRepository holidays;
    private final ZoneId zone;
    private final Clock clock;

    DigestScheduler(DigestRepository digests,
                    OutboxEnqueuer outbox,
                    WorkingCalendarRepository calendars,
                    HolidayRepository holidays,
                    @org.springframework.beans.factory.annotation.Value("${edutrack.digest.zone:Asia/Kolkata}")
                    String zone,
                    Clock clock) {
        this.digests = digests;
        this.outbox = outbox;
        this.calendars = calendars;
        this.holidays = holidays;
        this.zone = ZoneId.of(zone);
        this.clock = clock;
    }

    @Scheduled(cron = "${edutrack.digest.daily-cron:0 30 8 * * *}", zone = "${edutrack.digest.zone:Asia/Kolkata}")
    @SchedulerLock(name = "dailyDigest", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void daily() {
        try {
            sendDaily();
        } catch (RuntimeException e) {
            // An exception escaping a @Scheduled method cancels every future
            // execution of it — the digest would stop for good, silently.
            log.error("digest: daily run failed, retrying at the next schedule", e);
        }
    }

    @Scheduled(cron = "${edutrack.digest.weekly-cron:0 30 8 * * MON}", zone = "${edutrack.digest.zone:Asia/Kolkata}")
    @SchedulerLock(name = "weeklyManagerSummary", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void weekly() {
        try {
            sendWeekly();
        } catch (RuntimeException e) {
            log.error("digest: weekly run failed, retrying at the next schedule", e);
        }
    }

    /** @return how many digests were queued */
    int sendDaily() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (isNonWorking(today)) {
            log.debug("digest: {} is not a working day — no daily digest", today);
            return 0;
        }
        warnIfZoneDisagrees();

        var start = today.atStartOfDay(zone).toInstant();
        var end = today.plusDays(1).atStartOfDay(zone).toInstant();

        int queued = 0;
        for (DigestRepository.Assignee who : digests.assigneesWithOpenWork(start, end)) {
            // No ticket id: this mail is about many of them, and the subject
            // deliberately carries none. D-031 prefixes a ticket code onto a
            // subject only when there is one ticket to name.
            if (outbox.enqueue(new NewMail(null, NotificationEvent.DAILY_DIGEST.name(), null,
                    who.userId(), who.email(), dailySubject(who))).isPresent()) {
                queued++;
            }
        }
        log.info("digest: queued {} daily digest(s) for {}", queued, today);
        return queued;
    }

    /** @return how many summaries were queued */
    int sendWeekly() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        warnIfZoneDisagrees();
        var start = today.atStartOfDay(zone).toInstant();

        int queued = 0;
        for (DigestRepository.Manager who : digests.managersWithOpenWork(start)) {
            if (outbox.enqueue(new NewMail(null, NotificationEvent.WEEKLY_MANAGER_SUMMARY.name(), null,
                    who.userId(), who.email(), weeklySubject(who, today))).isPresent()) {
                queued++;
            }
        }
        log.info("digest: queued {} weekly manager summary(ies) for week of {}", queued, today);
        return queued;
    }

    /**
     * "Your open tickets — 4 due today, 1 overdue", per §4B.6.
     *
     * <p>The two figures the reader can act on go in the subject, because a
     * digest is read in a list of subject lines and often nowhere else. A count
     * of zero is left out rather than printed as "0 overdue" — the absence is
     * the good news and printing it spends the reader's attention on nothing.
     */
    static String dailySubject(DigestRepository.Assignee who) {
        StringBuilder s = new StringBuilder("Your open tickets — ").append(who.openCount()).append(" open");
        if (who.dueToday() > 0) s.append(", ").append(who.dueToday()).append(" due today");
        if (who.overdue() > 0) s.append(", ").append(who.overdue()).append(" overdue");
        return s.toString();
    }

    /** "Team summary — week of 03 Aug", per §4B.6. */
    static String weeklySubject(DigestRepository.Manager who, LocalDate weekOf) {
        StringBuilder s = new StringBuilder("Team summary — week of ").append(WEEK_OF.format(weekOf));
        s.append(" · ").append(who.openCount()).append(" open");
        if (who.overdue() > 0) s.append(", ").append(who.overdue()).append(" overdue");
        if (who.critical() > 0) s.append(", ").append(who.critical()).append(" critical");
        return s.toString();
    }

    private boolean isNonWorking(LocalDate day) {
        WorkingCalendar calendar = calendars.findAll().stream().findFirst().orElse(null);
        if (calendar != null && calendar.isNonWorkingDay(day.getDayOfWeek())) {
            return true;
        }
        return !holidays.findByHolidayDateBetweenAndIsActiveTrue(day, day).isEmpty();
    }

    /**
     * The cron's zone is fixed at startup; the calendar's is a row. If somebody
     * changes one, this is what says so — rather than the digest arriving at
     * the wrong hour with nothing anywhere explaining why.
     */
    private void warnIfZoneDisagrees() {
        calendars.findAll().stream().findFirst().ifPresent(calendar -> {
            if (!calendar.zone().equals(zone)) {
                log.warn("digest: the schedule runs in {} but the working calendar says {} — "
                                + "the digest is going out at the wrong local time. "
                                + "Set edutrack.digest.zone to match, and restart.",
                        zone, calendar.zone());
            }
        });
    }
}
