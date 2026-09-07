package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B-122 · OB-10's engine: the catalogue, the scope, the ETag, and dispatch to a
 * runner.
 *
 * <p>{@code ReportService}'s shape one module over, and the split it enforces
 * is the reason this class exists rather than the controller calling runners
 * directly: a runner owns "what are the columns and rows" and this owns
 * scoping, the date window and the validator. A new report therefore cannot get
 * scoping wrong by forgetting it, because it never sees the request.
 */
@Service
class ObReportService {

    /**
     * The window a report opens on when the caller has not chosen one.
     *
     * <p>Ninety days rather than the ticketing hub's thirty, and the difference
     * is the module's own arithmetic: the prototype's time-to-live chart has
     * boarding taking 24 to 34 working days, so a thirty-day default would open
     * every report on a window shorter than a single completed journey. The TAT
     * tab's own caption says "last 90 days" for the same reason.
     */
    private static final int DEFAULT_WINDOW_DAYS = 90;

    private final Map<String, ObReportRunner> runners;
    private final Clock clock;

    /**
     * <p>{@code @Autowired} is not decorative here. Two constructors and no
     * annotation is not an ambiguity Spring resolves — it looks for a default
     * constructor, finds none, and fails the <em>whole application context</em>,
     * so every {@code @SpringBootTest} in the module goes red at once with an
     * error naming the context rather than the cause.
     * {@code DashboardService} carries the same note for the same reason.
     */
    @Autowired
    ObReportService(List<ObReportRunner> runners) {
        this(runners, Clock.systemUTC());
    }

    /** Test seam — a default window cannot be asserted against a clock that only moves forwards. */
    ObReportService(List<ObReportRunner> runners, Clock clock) {
        this.runners = runners.stream()
                .collect(Collectors.toMap(ObReportRunner::key, Function.identity()));
        this.clock = clock;
    }

    /**
     * The whole catalogue, with the caller's scope note.
     *
     * <p>Every descriptor, whatever the caller's role. A report's
     * <em>rows</em> are narrowed and its <em>existence</em> is not: hiding
     * cards per role would make the hub a second, undocumented copy of A-114's
     * matrix, and a Step Owner who cannot see "Sales pipeline" on the hub has
     * no way to learn it is a thing that exists and that their rows of it are
     * empty rather than missing.
     */
    ObReportDtos.Catalogue catalogue(CallerIdentity caller) {
        return new ObReportDtos.Catalogue(
                ObReportCatalogue.declared(), ObReportScope.of(caller).scopeNote());
    }

    /**
     * One report, run.
     *
     * @return empty for a key the catalogue does not name <b>and</b> for one it
     *         names as unavailable. The contract declares a single error
     *         response for this route and says why: "by the time a caller is
     *         running a key there are no rows to describe and no columns to
     *         name", and from the caller's side a key that does not resolve to
     *         a runnable report is a key that is not there. The catalogue is
     *         where "exists but unbuilt" is expressed with a reason a person
     *         can read.
     */
    Optional<Rendered> run(CallerIdentity caller, String reportKey,
                           LocalDate from, LocalDate to,
                           Long productId, Long obClientId, Long ownerUserId, String rag) {

        ObReportDtos.ObReportDescriptor descriptor = ObReportCatalogue.find(reportKey);
        if (descriptor == null || !descriptor.available()) {
            return Optional.empty();
        }
        ObReportRunner runner = runners.get(reportKey);
        if (runner == null) {
            // A descriptor marked available with no runner behind it is a
            // wiring mistake, not a caller mistake. It answers 404 rather than
            // 500 because the caller can do nothing either way, and
            // ObReportCatalogueTest refuses the state at build time.
            return Optional.empty();
        }

        Instant now = clock.instant();
        LocalDate end = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);

        ObReportScope scope = ObReportScope.of(caller);
        ObReportFilters filters = new ObReportFilters(productId, obClientId, rag);
        Long ownerSubject = scope.ownerSubject(ownerUserId);

        ObReportRunner.Result result = runner.run(scope, start, end, now, ownerSubject, filters);

        ObReportDtos.Report report =
                new ObReportDtos.Report(reportKey, result.columns(), result.rows());
        ObReportDtos.RunMeta meta = new ObReportDtos.RunMeta(scope.appliedScope(), now);

        return Optional.of(new Rendered(report, meta,
                etagOf(reportKey, scope, start, end, ownerSubject, filters, result.rows())));
    }

    /**
     * The validator, built over the rows themselves.
     *
     * <h2>Why the content and not the parameters</h2>
     *
     * <p>{@code ReportService} hashes the request plus the summary table's
     * {@code computed_at}, and returns no validator at all when that is null —
     * because a report whose freshness cannot be proved must not be pinned in a
     * cache. Every report here is read live from the journey tables, so there
     * is no {@code computed_at} to hash and the parameter-only version would be
     * exactly the validator that pins a moving answer.
     *
     * <p>So the rows go into the hash. A 304 then means what it says: the
     * answer has not changed since the caller last saw it.
     *
     * <p><b>What this does not save is the query.</b> The contract justifies
     * the ETag by these reads being expensive, and the expense is in running
     * them — which has already happened by the time this method is called. The
     * saving is the payload and the client's re-render, which for the case the
     * contract actually describes (somebody toggling a filter back and forth)
     * is most of what they were waiting for, but it is worth being straight
     * about: making the ETag skip the work needs a cached result keyed by these
     * same inputs, which is a caching layer and not a header.
     *
     * <p><b>Scope is in the hash</b>, so two callers with different roles
     * asking the same URL never share a validator. Omitting it is how a cache
     * hands one person another's report after a grant changes.
     */
    static String etagOf(String reportKey, ObReportScope scope, LocalDate from, LocalDate to,
                         Long ownerSubject, ObReportFilters filters,
                         List<Map<String, Object>> rows) {

        int hash = Objects.hash(reportKey, scope.moduleRole(), scope.userId(),
                from, to, ownerSubject, filters, rows);
        return Integer.toHexString(hash);
    }

    record Rendered(ObReportDtos.Report report, ObReportDtos.RunMeta meta, String etag) {
    }
}
