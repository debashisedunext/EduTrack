package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's "sign-off pending". The prototype's sixth tab — "Client
 * sign-offs, pending &amp; upcoming."
 *
 * <h2>Requested and unanswered, which is narrower than the prototype's tab</h2>
 *
 * <p>The prototype lists every step that <em>requires</em> a sign-off and is
 * not done, including ones nobody has asked for yet — its own chip vocabulary
 * has "Not yet requested" beside "Link sent". A-118's description is
 * "Requested and unanswered, oldest first", and that is the narrower and more
 * useful list: a step whose sign-off has not been requested is waiting on us
 * and appears on {@code stuck-and-aging}; one that has been requested is
 * waiting on the client and appears nowhere else.
 *
 * <p>Recorded rather than silently reinterpreted, because the two documents
 * disagree and the contract is the one that is current.
 *
 * <h2>The expired column exists because the status cannot say it</h2>
 *
 * <p>{@code ObSignoffStatus} is explicit that {@code EXPIRED} "is reached by
 * the token's TTL passing, not by an operation — there is no route that expires
 * a sign-off, because the thing that expires it is time." So a row is
 * {@code PENDING} in the table long after its link stopped working, and a
 * pending list that showed those as merely awaiting the client would have
 * somebody chasing a client who cannot act however willing they are. The state
 * is therefore derived here from {@code token_expires_at} against the request's
 * own instant, and the two cases are separate words rather than a nullable
 * flag.
 *
 * <p>This is a report and it does not write the status back. Nothing here may:
 * a read answering a GET that also mutates rows is how a report comes to behave
 * differently the second time it is run.
 */
@Component
class SignoffPendingRunner implements ObReportRunner {

    static final String KEY = "signoff-pending";

    private final ObReportRepository repository;
    private final WorkingHoursService workingHours;

    SignoffPendingRunner(ObReportRepository repository, WorkingHoursService workingHours) {
        this.repository = repository;
        this.workingHours = workingHours;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                      Long ownerSubject, ObReportFilters filters) {

        List<ObReportDtos.Column> columns = List.of(
                new ObReportDtos.Column("client", "Client", STRING),
                new ObReportDtos.Column("service", "Service", STRING),
                new ObReportDtos.Column("kind", "Kind", STRING),
                new ObReportDtos.Column("contact", "Sent to", STRING),
                new ObReportDtos.Column("contactEmail", "Email", STRING),
                new ObReportDtos.Column("requestedOn", "Requested", DATE),
                new ObReportDtos.Column("waitingFor", "Waiting", DURATION),
                new ObReportDtos.Column("linkState", "Link", STRING),
                new ObReportDtos.Column("expiresOn", "Expires", DATE));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.PendingSignoff signoff : repository.pendingSignoffs(
                scope, from, to, filters.obClientId())) {

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("client", signoff.client());
            out.put("service", signoff.service());
            out.put("kind", "GO_LIVE".equals(signoff.kind()) ? "Go-live" : "Step");
            out.put("contact", signoff.contact());
            out.put("contactEmail", signoff.contactEmail());
            out.put("requestedOn", asDate(signoff.requestedAt()));
            out.put("waitingFor", workingHours.workingHoursBetween(signoff.requestedAt(), now));
            out.put("linkState", now.isAfter(signoff.expiresAt()) ? "Expired" : "Live");
            out.put("expiresOn", asDate(signoff.expiresAt()));
            rows.add(out);
        }
        return new Result(columns, rows);
    }

    /** UTC, like every date this product writes down. The viewer applies the user's zone. */
    private static LocalDate asDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
