package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A-065 · fires the schedules whose time has come.
 *
 * <h2>⚠️ The first scheduler in the api module, and why it is not in worker</h2>
 *
 * <p>Every other scheduled job in EduTrack lives in {@code worker/} — TEAM-PLAN
 * §6 says so, and Stream A's own hash verifier is there under an explicit
 * carve-out. This one cannot be: {@code worker} depends on {@code domain} and
 * not on {@code api}, and the eighteen report runners, the catalogue and
 * {@link ReportScope} are all in {@code api/feature/reports}. Putting the timer
 * in {@code worker} would mean moving three thousand lines of report code into
 * {@code domain}, or giving a mail worker a dependency on the web module.
 *
 * <p>So the timer is here, in Stream A's own package, and {@code api} gains
 * {@code @EnableScheduling} and a ShedLock provider for the first time — see
 * {@code ApiSchedulingConfig}. That is the smaller change, and unlike the
 * {@code worker/stats/} arrangement it needs nobody else's sign-off.
 *
 * <h2>The sweep is a query, not a cron per schedule</h2>
 *
 * <p>Cadence lives in {@code next_run_at}, so becoming due is an indexed range
 * scan and adding a schedule costs no registration anywhere. The alternative —
 * a Spring cron per row — would need the whole set re-registered on every
 * create and cancel, and would lose everything on a restart.
 *
 * <h2>What a failure must not do</h2>
 *
 * <p>Three things, each learned from a scanner one package over:
 *
 * <ul>
 *   <li><b>One bad schedule must not stop the others.</b> Each is run inside
 *       its own try/catch, because a report whose runner throws would otherwise
 *       take every later schedule in the sweep with it — and they are ordered,
 *       so it would be the same ones every time.</li>
 *   <li><b>An exception must not escape the {@code @Scheduled} method.</b> One
 *       that does cancels every future execution, silently and for good.
 *       {@code DigestScheduler} carries the same guard and says the same
 *       thing.</li>
 *   <li><b>A failed run must still advance the clock.</b> Otherwise a schedule
 *       whose report was withdrawn from the catalogue stays due for ever and is
 *       retried on every sweep — several times a minute, writing a FAILED row
 *       each time and turning one broken schedule into an unbounded table.</li>
 * </ul>
 */
@Component
class ScheduledReportRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReportRunner.class);

    /**
     * How many schedules one sweep will run.
     *
     * <p>A bound rather than a target. Reports are expensive, the sweep holds a
     * lock while it works, and a backlog after an outage should drain over
     * several passes rather than occupy one instance for an hour. The oldest
     * due are taken first, so nothing starves.
     */
    private static final int PER_SWEEP = 20;

    /** "01 Aug 2026" — a period in a subject line, never an ISO timestamp. */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final ReportScheduleRepository schedules;
    private final ReportService reports;
    private final ReportExportService exports;
    private final ReportFileStore files;
    private final OutboxEnqueuer outbox;
    private final ObjectMapper json;
    private final ZoneId zone;
    private final int sendHour;
    private final Clock clock;

    /**
     * {@code api} publishes no {@code Clock} bean — {@code WidgetService} says
     * so and takes this same shape. The overload below is what a test drives
     * with a fixed clock and a fixed zone.
     */
    @org.springframework.beans.factory.annotation.Autowired
    ScheduledReportRunner(ReportScheduleRepository schedules,
                          ReportService reports,
                          ReportExportService exports,
                          ReportFileStore files,
                          OutboxEnqueuer outbox,
                          ObjectMapper json,
                          @Value("${edutrack.reports.zone:${edutrack.digest.zone:Asia/Kolkata}}") String zone,
                          @Value("${edutrack.reports.send-hour:6}") int sendHour) {
        this(schedules, reports, exports, files, outbox, json, zone, sendHour, Clock.systemUTC());
    }

    ScheduledReportRunner(ReportScheduleRepository schedules,
                          ReportService reports,
                          ReportExportService exports,
                          ReportFileStore files,
                          OutboxEnqueuer outbox,
                          ObjectMapper json,
                          String zone,
                          int sendHour,
                          Clock clock) {
        this.schedules = schedules;
        this.reports = reports;
        this.exports = exports;
        this.files = files;
        this.outbox = outbox;
        this.json = json;
        this.zone = ZoneId.of(zone);
        this.sendHour = sendHour;
        this.clock = clock;
    }

    /**
     * Every five minutes, not on the hour.
     *
     * <p>The hour a schedule fires is {@code next_run_at}'s business. This only
     * has to notice, and noticing within five minutes of 06:00 is what a
     * recipient cannot tell from noticing at 06:00 exactly. A cron pinned to the
     * hour would also mean an instance restarted at 06:01 skips the day.
     */
    @Scheduled(fixedDelayString = "${edutrack.reports.sweep-interval:PT5M}")
    @SchedulerLock(name = "scheduledReports", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void sweep() {
        // No enabled flag here, deliberately. The first draft had one and it
        // could not work: @SchedulerLock's interceptor wraps this method, so it
        // reaches Redis before the first line of the body runs. The switch is
        // on ApiSchedulingConfig instead — with it off there is no
        // @EnableScheduling at all, so this never fires, while the bean still
        // exists for a test to drive runDue() directly.
        try {
            runDue();
        } catch (RuntimeException e) {
            // See the class note: an escaping exception cancels every future
            // execution, and the scheduled reports would stop for good with
            // nothing anywhere saying so.
            log.error("scheduled reports: sweep failed, retrying at the next interval", e);
        }
    }

    /** @return how many schedules were run, successfully or not */
    int runDue() {
        Instant now = clock.instant();
        List<ReportScheduleRepository.Row> due = schedules.findDue(now, PER_SWEEP);
        if (due.isEmpty()) {
            return 0;
        }

        int ran = 0;
        for (ReportScheduleRepository.Row schedule : due) {
            try {
                runOne(schedule, now);
            } catch (RuntimeException e) {
                // Per schedule, so one broken report does not take the rest of
                // the sweep with it. Already advanced inside runOne's finally,
                // so this cannot loop.
                log.error("scheduled reports: schedule {} failed", schedule.id(), e);
            }
            ran++;
        }
        log.info("scheduled reports: ran {} due schedule(s)", ran);
        return ran;
    }

    private void runOne(ReportScheduleRepository.Row schedule, Instant now) {
        ReportCadence cadence = ReportCadence.of(schedule.cadence())
                .orElseThrow(() -> new IllegalStateException(
                        "schedule " + schedule.id() + " has cadence '" + schedule.cadence() + "'"));

        LocalDate runDate = LocalDate.ofInstant(now, zone);
        ReportCadence.Period period = cadence.periodEnding(runDate);
        long runId = schedules.startRun(schedule.id(), now, period.from(), period.to());

        try {
            // 🔴 Re-resolved, never frozen. The whole security design of this
            // feature is that the schedule cannot be more privileged than its
            // owner is right now — see ReportScheduleRepository.callerFor.
            Optional<CallerIdentity> owner = schedules.callerFor(schedule.createdBy());
            if (owner.isEmpty()) {
                // The owner is deactivated or gone. Stop rather than fail every
                // week for ever: a schedule that cannot be scoped to anybody has
                // no correct output, and somebody returning can create it again.
                schedules.deactivate(schedule.id(), schedule.createdBy());
                schedules.fail(runId, "The owner of this schedule is no longer an active user, "
                        + "so there is no role to decide which rows it may contain. "
                        + "The schedule has been cancelled.");
                return;
            }

            Map<String, Object> parameters = readParameters(schedule.parametersJson());
            Optional<ReportService.Rendered> rendered = reports.run(
                    owner.get(),
                    schedule.reportKey(),
                    period.from(), period.to(),
                    asId(parameters.get("projectId")),
                    asId(parameters.get("resourceId")),
                    new ReportFilters(
                            asId(parameters.get("clientId")),
                            asId(parameters.get("taskTypeId")),
                            asText(parameters.get("level"))));

            if (rendered.isEmpty()) {
                // The same empty ReportService.run gives the viewer, and it
                // means the same thing: this key is not runnable for this
                // caller. Reached here when a report is withdrawn from the
                // catalogue, or when the owner's role changed to one it cannot
                // be answered for — which is the demotion case working.
                schedules.fail(runId, "'" + schedule.reportKey() + "' is no longer available to "
                        + "the owner of this schedule, so it produced nothing.");
                return;
            }

            deliver(schedule, runId, cadence, period, rendered.get(), owner.get());

        } catch (Exception e) {
            schedules.fail(runId, e.getMessage());
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            // Always, including after a failure — see the class note. Computed
            // from now rather than from the stored next_run_at, so a schedule
            // that was due three days ago does not fire three times catching up.
            schedules.advance(schedule.id(), now,
                    cadence.nextRunAfter(ZonedDateTime.ofInstant(now, zone), zone, sendHour).toInstant());
        }
    }

    private void deliver(ReportScheduleRepository.Row schedule, long runId, ReportCadence cadence,
                         ReportCadence.Period period, ReportService.Rendered rendered,
                         CallerIdentity owner) throws java.io.IOException {

        ReportExporter.Format format = ReportExporter.Format.of(schedule.format())
                .orElseThrow(() -> new IllegalStateException(
                        "schedule " + schedule.id() + " has format '" + schedule.format() + "'"));

        byte[] file = exports.toBytes(format, schedule.reportKey(), rendered);
        String fileName = fileName(schedule.reportKey(), period, format);
        String key = files.put(schedule.id(), runId, fileName, format.contentType(), file);

        schedules.succeed(runId,
                rendered.report().rows().size(),
                rendered.meta().appliedScope(),
                key, fileName);

        // Stored first, mailed second, and the order is the point: a mail
        // announcing a file that was never stored sends people to a 404 they
        // cannot do anything about. If put() throws, the run is FAILED and
        // nobody is told about a report that does not exist.
        String subject = subject(schedule.reportKey(), cadence, period);
        for (Recipient recipient : recipientsOf(schedule)) {
            outbox.enqueue(new NewMail(
                    // No ticket: this mail is about none, and D-031's
                    // [CODE] prefix is skipped for exactly that case.
                    null,
                    NotificationEvent.SCHEDULED_REPORT.name(),
                    null,
                    recipient.userId(),
                    recipient.email(),
                    subject));
        }
        log.info("scheduled reports: schedule {} run {} produced {} row(s) for {}",
                schedule.id(), runId, rendered.report().rows().size(), owner.roleCode());
    }

    /**
     * The recipients as users, so the outbox can consult their notification
     * preferences.
     *
     * <p>Addresses are stored rather than ids ({@code report_schedules}'
     * header), and resolved back here on every run — which means an address
     * whose account was deactivated after the schedule was created is dropped
     * now rather than mailed a link it cannot open.
     */
    private List<Recipient> recipientsOf(ReportScheduleRepository.Row schedule) {
        List<String> addresses = readRecipients(schedule.recipientsJson());
        return schedules.activeUsersByEmail(addresses).stream()
                .map(u -> new Recipient(u.userId(), u.email()))
                .toList();
    }

    private record Recipient(Long userId, String email) {
    }

    /**
     * {@code sla-breach-2026-08-01_2026-08-07.xlsx}.
     *
     * <p>Named for the period rather than for the day it was generated, unlike
     * {@code ReportExportService.filenameFor}. An interactive export is about
     * "now" and the reader has the screen in front of them; these accumulate in
     * a Downloads folder weeks apart, and a file called
     * {@code sla-breach-2026-08-08.xlsx} that is actually about the previous
     * week is the kind of small lie somebody builds a wrong conclusion on.
     */
    static String fileName(String reportKey, ReportCadence.Period period, ReportExporter.Format format) {
        String window = period.from().equals(period.to())
                ? period.from().toString()
                : period.from() + "_" + period.to();
        return reportKey + "-" + window + "." + format.wire();
    }

    /**
     * "Resource Scorecard — weekly report for 01 Aug 2026 to 07 Aug 2026".
     *
     * <p>The period is in the subject because a scheduled mail is read in a list
     * of near-identical subject lines, and "which week is this one" is the only
     * question the reader has before opening it. The template body carries the
     * link; the subject carries the fact.
     */
    static String subject(String reportKey, ReportCadence cadence, ReportCadence.Period period) {
        String title = ReportCatalogue.declared().stream()
                .filter(d -> d.key().equals(reportKey))
                .map(ReportDtos.Descriptor::title)
                .findFirst()
                .orElse(reportKey);

        String window = period.from().equals(period.to())
                ? DAY.format(period.from())
                : DAY.format(period.from()) + " to " + DAY.format(period.to());

        return title + " — " + cadence.label() + " report for " + window;
    }

    // ── stored filters ───────────────────────────────────────────────────────

    private Map<String, Object> readParameters(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception unreadable) {
            // Running with no filters is wrong, so this does not do that: a
            // filter set this application wrote and can no longer read would
            // silently widen the report, and widening is the direction that
            // matters. Scope still bounds it, but "all projects I can see" is
            // not what the schedule asked for.
            throw new IllegalStateException("The stored filters for this schedule could not be read.");
        }
    }

    /** JSON numbers arrive as Integer or Long by magnitude; a string id is tolerated. */
    private static Long asId(Object value) {
        return switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            case String text -> text.isBlank() ? null : parseOrNull(text);
            default -> null;
        };
    }

    private static Long parseOrNull(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    private static String asText(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private List<String> readRecipients(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception unreadable) {
            throw new IllegalStateException("The recipients for this schedule could not be read.");
        }
    }
}
