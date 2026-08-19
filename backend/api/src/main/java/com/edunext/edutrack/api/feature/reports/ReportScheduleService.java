package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A-065 · creating, listing and cancelling a scheduled report.
 *
 * <p>The running of it is {@link ScheduledReportRunner}; this owns everything a
 * person does to a schedule, and the validation that stops a schedule being
 * created that could only ever fail later.
 *
 * <h2>Refused now rather than discovered weekly</h2>
 *
 * <p>Three things are checked at creation because the alternative is a standing
 * instruction that quietly does nothing: a report key the caller cannot run, a
 * recipient with no account (the mail links to an authenticated download, so
 * they would receive a permanent invitation to a sign-in page), and more
 * schedules than one person plausibly reads. Each of those is fixable by
 * whoever is looking at the dialog and by nobody who receives the mail.
 */
@Service
class ReportScheduleService {

    /**
     * Active schedules one person may hold.
     *
     * <p>Not a licensing limit — it is the blast radius. A schedule is a loop
     * that sends mail for ever without anybody touching it, and the failure
     * worth bounding is a script, or a frustrated afternoon, leaving four
     * hundred of them behind. Twenty is far above what a person reads and far
     * below what would matter.
     */
    private static final int MAX_ACTIVE_PER_USER = 20;

    /** How many runs the list carries per schedule. A summary, not a log. */
    private static final int RECENT_RUNS = 5;

    private final ReportScheduleRepository schedules;
    private final ObjectMapper json;
    private final ZoneId zone;
    private final int sendHour;
    private final Clock clock;

    /**
     * {@code api} publishes no {@code Clock} bean — {@code WidgetService} and
     * {@code DashboardService} both say so and both take this shape. The
     * package-private overload below is what a unit test drives with a fixed
     * clock, since "when does this next fire" is exactly the kind of assertion
     * that must not depend on what time the suite happens to run.
     */
    @org.springframework.beans.factory.annotation.Autowired
    ReportScheduleService(ReportScheduleRepository schedules,
                          ObjectMapper json,
                          // Inherits the organisation's clock from the digest's
                          // setting unless overridden, rather than declaring a
                          // second timezone that can silently disagree with it.
                          @Value("${edutrack.reports.zone:${edutrack.digest.zone:Asia/Kolkata}}") String zone,
                          @Value("${edutrack.reports.send-hour:6}") int sendHour) {
        this(schedules, json, zone, sendHour, Clock.systemUTC());
    }

    ReportScheduleService(ReportScheduleRepository schedules,
                          ObjectMapper json,
                          String zone,
                          int sendHour,
                          Clock clock) {
        this.schedules = schedules;
        this.json = json;
        this.zone = ZoneId.of(zone);
        this.sendHour = sendHour;
        this.clock = clock;
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Transactional
    ReportScheduleDtos.Schedule create(CallerIdentity caller, ReportScheduleDtos.ScheduleRequest request) {
        String reportKey = required(request.reportKey(), "reportKey");

        // Resolved against the *caller's* catalogue, so a report that cannot be
        // answered for their role is refused here rather than producing a
        // schedule that fails on every run. ReportService.run makes the same
        // check for the same reason; this is that rule arriving a week early.
        ReportScope scope = ReportScope.of(caller);
        ReportCatalogue.find(reportKey, scope)
                .filter(ReportDtos.Descriptor::available)
                .orElseThrow(() -> badRequest(
                        "No report '" + reportKey + "' is available to schedule for your role."));

        ReportCadence cadence = ReportCadence.of(request.cadence())
                .orElseThrow(() -> badRequest(
                        "cadence must be DAILY, WEEKLY or MONTHLY."));

        String format = normalisedFormat(request.format());
        List<String> recipients = validatedRecipients(request.recipients());

        if (activeCount(caller.userId()) >= MAX_ACTIVE_PER_USER) {
            throw badRequest("You already have " + MAX_ACTIVE_PER_USER
                    + " active scheduled reports. Cancel one before adding another.");
        }

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        long id = schedules.create(
                reportKey, cadence, format,
                write(recipients), write(parametersWithoutDates(request.parameters())),
                caller.userId(),
                cadence.nextRunAfter(now, zone, sendHour).toInstant());

        return schedules.findById(id).map(row -> toDto(row, caller.userId()))
                .orElseThrow(() -> new IllegalStateException("schedule " + id + " vanished after insert"));
    }

    // ── read ─────────────────────────────────────────────────────────────────

    /**
     * The caller's own schedules <b>and the ones they receive</b>.
     *
     * <p>Both, because the mail links here. A list of only what you created
     * sent every recipient who followed that link to an empty screen — see
     * {@link ReportScheduleRepository#findVisibleTo}. A received schedule is
     * read-only: {@code ownedByMe} is false and the client offers no Cancel,
     * because stopping somebody else's standing instruction is not the
     * recipient's decision to make. What they can do is ask the owner, whose
     * name is on the row.
     */
    List<ReportScheduleDtos.Schedule> mine(CallerIdentity caller) {
        return schedules.findVisibleTo(caller.userId()).stream()
                .map(row -> toDto(row, caller.userId()))
                .toList();
    }

    // ── cancel ───────────────────────────────────────────────────────────────

    /**
     * Deactivates rather than deletes, and only the owner's own.
     *
     * <p><b>404 for somebody else's schedule, never 403</b> — §2's rule for
     * out-of-scope ids, and it holds here for the same reason it holds for a
     * ticket: a 403 confirms that schedule 41 exists and belongs to somebody,
     * which is a fact the caller was not entitled to learn by guessing.
     *
     * <p>Cancelling twice is not an error. The caller asked for it to stop and
     * it is stopped; a 404 on the second click would be a worse answer to a
     * request that has already been honoured.
     */
    @Transactional
    void cancel(CallerIdentity caller, long id) {
        ReportScheduleRepository.Row row = schedules.findById(id)
                .filter(s -> s.createdBy() == caller.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No scheduled report " + id + "."));
        if (row.active()) {
            schedules.deactivate(id, caller.userId());
        }
    }

    // ── download ─────────────────────────────────────────────────────────────

    /**
     * The stored file for one run, or empty for anything the caller may not
     * have.
     *
     * <p>Ownership is re-checked here and not merely at creation. The link
     * travels by email and email gets forwarded, so this method has to assume
     * the person clicking is not the person it was sent to — which is exactly
     * what an emailed attachment could never assume.
     */
    Optional<StoredFile> fileFor(CallerIdentity caller, long scheduleId, long runId,
                                 ReportFileStore store) {
        // Owner or recipient. Restricting this to the owner was the defect that
        // made the feature single-player: everybody the report was emailed to
        // got a link that answered 404.
        //
        // The rows in the file are the owner's view, and that is deliberate
        // rather than overlooked — a schedule is a sharing act, the same one
        // the owner performs by exporting and forwarding, and it is bounded by
        // their own scope at the moment it ran. What this must still refuse is
        // somebody who was never named on it, which is why the check is here
        // and not only at creation: the link travels by email, and email gets
        // forwarded.
        if (!schedules.isVisibleTo(scheduleId, caller.userId())) {
            return Optional.empty();
        }
        return schedules.findRun(scheduleId, runId)
                .filter(run -> run.storageKey() != null)
                .flatMap(run -> store.read(run.storageKey())
                        .map(bytes -> new StoredFile(
                                run.fileName() == null ? "report" : run.fileName(), bytes)));
    }

    record StoredFile(String fileName, byte[] bytes) {
    }

    // ── validation ───────────────────────────────────────────────────────────

    private List<String> validatedRecipients(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw badRequest("recipients must contain at least one address.");
        }
        // De-duplicated case-insensitively but keeping what was typed: two
        // spellings of one address would otherwise mean two copies of every
        // mail, which reads as a system fault rather than as a typo.
        Set<String> seen = new LinkedHashSet<>();
        List<String> unique = new ArrayList<>();
        for (String address : requested) {
            if (address == null || address.isBlank()) {
                throw badRequest("recipients must not contain a blank address.");
            }
            if (seen.add(address.trim().toLowerCase(Locale.ROOT))) {
                unique.add(address.trim());
            }
        }

        List<String> unknown = schedules.unknownRecipients(unique);
        if (!unknown.isEmpty()) {
            throw badRequest("These addresses do not belong to an active EduTrack user, and the "
                    + "report link can only be opened by somebody who can sign in: "
                    + String.join(", ", unknown));
        }

        // Stored as the user's own `users.email` rather than as typed. One
        // spelling, so "am I a recipient of this?" is an exact comparison
        // rather than a case-insensitive JSON search — JSON_SEARCH compares
        // under utf8mb4_bin and would not match `Priya@…` against `priya@…`,
        // which is the difference between a colleague being able to open their
        // report and not.
        Map<String, String> canonical = schedules.activeUsersByEmail(unique).stream()
                .collect(java.util.stream.Collectors.toMap(
                        u -> u.email().toLowerCase(Locale.ROOT),
                        ReportScheduleRepository.Recipient::email,
                        (a, b) -> a));

        return unique.stream()
                .map(typed -> canonical.getOrDefault(typed.toLowerCase(Locale.ROOT), typed))
                .toList();
    }

    private static String normalisedFormat(String requested) {
        if (requested == null || requested.isBlank()) {
            // The contract's default. Applied here rather than as a column
            // default so the value the caller gets back is the value that will
            // be used, on the same response.
            return "xlsx";
        }
        return com.edunext.edutrack.api.feature.reports.export.ReportExporter.Format.of(requested)
                .orElseThrow(() -> badRequest("format must be xlsx, csv or pdf."))
                .wire();
    }

    /**
     * Drops any date range from the stored filters.
     *
     * <p>A schedule's window comes from its cadence ({@link ReportCadence}), and
     * a stored {@code from}/{@code to} would win over it and make every run
     * email the same period for ever — a failure that looks exactly like a
     * working schedule until two files are compared. The viewer posts back the
     * filter bar it rendered, dates included, so this is the normal path rather
     * than an abuse case; it is dropped silently for the reason
     * {@code ?resourceId=} is dropped silently one route over.
     */
    private Map<String, Object> parametersWithoutDates(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> kept = new java.util.LinkedHashMap<>(parameters);
        kept.keySet().removeIf(key -> "from".equalsIgnoreCase(key) || "to".equalsIgnoreCase(key));
        return kept;
    }

    private int activeCount(long userId) {
        return (int) schedules.findByOwner(userId).stream()
                .filter(ReportScheduleRepository.Row::active)
                .count();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required.");
        }
        return value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private ReportScheduleDtos.Schedule toDto(ReportScheduleRepository.Row row, long callerId) {
        List<ReportScheduleDtos.Run> runs = schedules.recentRuns(row.id(), RECENT_RUNS).stream()
                .map(run -> new ReportScheduleDtos.Run(
                        run.id(), run.runAt(), run.periodFrom(), run.periodTo(), run.status(),
                        run.rowCount(), run.appliedScope(), run.errorText(),
                        run.storageKey() != null))
                .toList();

        return new ReportScheduleDtos.Schedule(
                row.id(),
                row.reportKey(),
                titleOf(row.reportKey()),
                row.cadence(),
                row.format(),
                readList(row.recipientsJson()),
                readMap(row.parametersJson()),
                row.active(),
                row.createdBy() == callerId,
                row.createdBy(),
                schedules.displayName(row.createdBy()).orElse(null),
                row.nextRunAt(),
                row.lastRunAt(),
                runs);
    }

    /** Falls back to the key: a schedule for a report since withdrawn is still worth listing. */
    private static String titleOf(String reportKey) {
        return ReportCatalogue.declared().stream()
                .filter(d -> d.key().equals(reportKey))
                .map(ReportDtos.Descriptor::title)
                .findFirst()
                .orElse(reportKey);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise schedule field", e);
        }
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception unreadable) {
            // A column this application wrote that it can no longer read is a
            // bug, not a caller error — but it must not take the whole list
            // down with it, or one bad row hides every other schedule.
            return List.of();
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception unreadable) {
            return Map.of();
        }
    }
}
