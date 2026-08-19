package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A-063 · S-27's engine: the catalogue, the scope, the ETag, and dispatch to a
 * {@link ReportRunner}.
 *
 * <p>Three of those four are here rather than in the runners deliberately — a
 * report added by A-066 cannot forget to apply scope, because it is handed a
 * resolved {@link ReportScope} and never sees the request.
 */
@Service
class ReportService {

    /**
     * The default window when the caller names neither end.
     *
     * <p>Thirty days, matching the dashboard's default rather than choosing a
     * second convention: a person moving from S-05 to S-27 comparing the same
     * figure should not have to notice that one screen means a month and the
     * other a quarter.
     */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final Map<String, ReportRunner> runners;

    ReportService(List<ReportRunner> runners) {
        // Spring injects every implementation, so a report added by A-066 is
        // registered by existing — there is no list to remember to update. A
        // duplicate key is a programming error and fails at startup rather
        // than silently letting one runner shadow another.
        this.runners = runners.stream().collect(Collectors.toMap(ReportRunner::key, Function.identity()));
    }

    /**
     * The hub's card grid.
     *
     * <p>Every caller gets every descriptor. What differs is {@code scopeNote},
     * because §2 narrows the <em>rows</em> a report returns and not which
     * reports exist — a Developer may legitimately open their own scorecard,
     * and hiding the card would say the report does not exist rather than that
     * it will be about them.
     */
    ReportDtos.Catalogue catalogue(CallerIdentity caller) {
        ReportScope scope = ReportScope.of(caller);
        return new ReportDtos.Catalogue(ReportCatalogue.forScope(scope), scope.note());
    }

    /**
     * Run a report, or empty for a key that does not resolve to something
     * runnable — which the controller turns into a 404.
     *
     * <p><b>Unknown and unbuilt are the same answer here</b>, and the asymmetry
     * with the catalogue is the point. The catalogue can say "exists, not built
     * yet" in a sentence a person reads. A runner cannot: it has no columns to
     * name and no rows to return, so a 200 would have to invent an empty report,
     * which asserts that the query ran and found nothing.
     */
    Optional<Rendered> run(CallerIdentity caller, String reportKey,
                           LocalDate from, LocalDate to,
                           Long projectId, Long resourceId, ReportFilters filters) {

        ReportScope scope = ReportScope.of(caller);

        // Resolved against the caller's own view of the catalogue, so a report
        // that cannot be answered per person is a 404 by URL as well as a greyed
        // card on the hub. Checking the global list here instead would leave the
        // hub honest and the endpoint not, which is the half-fix that matters:
        // the URL is what a bookmark, a shared link and A-065's schedule use.
        Optional<ReportDtos.Descriptor> descriptor = ReportCatalogue.find(reportKey, scope);
        if (descriptor.isEmpty() || !descriptor.get().available()) {
            return Optional.empty();
        }

        ReportRunner runner = runners.get(reportKey);
        if (runner == null) {
            // The catalogue claims this is available and nothing implements it.
            // A configuration error rather than a caller error, and one that
            // must not read as "no such report" — ReportCatalogueTest asserts
            // the two lists agree, so reaching here means that test was
            // bypassed or a bean failed to register.
            throw new IllegalStateException(
                    "Report '" + reportKey + "' is marked available in the catalogue "
                            + "but no ReportRunner is registered for it.");
        }

        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);

        List<Long> projects = scope.projectFilter(projectId);
        Long subject = scope.resourceSubject(resourceId);

        ReportRunner.Result result = runner.run(scope, start, end, projects, filters);

        ReportDtos.Report report =
                new ReportDtos.Report(reportKey, result.columns(), result.rows());

        return Optional.of(new Rendered(
                report,
                new ReportDtos.RunMeta(appliedScope(scope, projects, subject)),
                etagOf(reportKey, scope, projects, subject, start, end, filters, result.asOf())));
    }

    /**
     * What the server actually narrowed to, in words the viewer prints.
     *
     * <p>Exists because a delivery role's {@code resourceId} is discarded
     * silently: without this line, "the filter did nothing" and "the filter
     * matched nothing" are indistinguishable, and only one of them is about the
     * data.
     */
    private static String appliedScope(ReportScope scope, List<Long> projects, Long subject) {
        if (scope.ownWorkOnly()) {
            return "your own work";
        }
        if (subject != null) {
            return projects.isEmpty()
                    ? "one resource, across all projects"
                    : "one resource, within your projects";
        }
        return projects.isEmpty() ? "the whole organisation" : "your projects";
    }

    /**
     * The validator. Same construction as the dashboard's, for the same reason.
     *
     * <p>The contract's own note on this route says reports "are re-run every
     * time somebody changes a filter and changes it back" — that is the case
     * this pays for. Built from the key, the caller's scope, the parameters and
     * {@code asOf}, because the answer is a pure function of exactly those: if
     * A-051 has not recomputed, the report cannot have changed.
     *
     * <p><b>Scope is in the hash</b>, so two callers with different projects
     * asking the same URL never share a validator. Omitting it is how a cache
     * hands one person another's report after a role change.
     *
     * <p>Null {@code asOf} means nothing has been computed for the window — a
     * state that can change with no {@code computed_at} to prove it, so it gets
     * no validator rather than one that would pin an empty report in place.
     */
    static String etagOf(String reportKey, ReportScope scope, List<Long> projects,
                         Long subject, LocalDate from, LocalDate to,
                         ReportFilters filters, java.time.Instant asOf) {
        if (asOf == null) {
            return null;
        }
        // B-060 · filters are in the hash for the same reason scope is. Two
        // clients asked for from the same URL differ only by ?clientId=, and a
        // validator that ignored it would hand the second caller a 304 against
        // the first client's rows — a cross-client leak on the one report
        // explicitly shaped to be sent to a client.
        int hash = Objects.hash(reportKey, scope.ownWorkOnly(), scope.userId(),
                scope.projectIds(), projects, subject, from, to, filters, asOf);
        return Integer.toHexString(hash);
    }

    record Rendered(ReportDtos.Report report, ReportDtos.RunMeta meta, String etag) {
    }
}
