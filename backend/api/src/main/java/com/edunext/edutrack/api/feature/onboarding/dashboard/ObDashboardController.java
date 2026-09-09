package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardItemListMeta;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardItemListResponse;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardSummaryResponse;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B-121 · {@code /onboarding/dashboard} per {@code contracts/openapi.yaml} —
 * OB-02, the card board.
 *
 * <p>Two routes so far: {@link #summary} (B-121) and {@link #cardItems}
 * (B-127), the S-06 slide-over behind one card. The other two the contract
 * declares under this prefix — {@code /delayed-projects} and
 * {@code /implementor-workload} — are B-128's two grids and belong here when
 * they land, in this package rather than one of their own — feature
 * packaging, and they share {@link ObDashboardCardKey} and
 * {@link ObDashboardScope}, which is the whole reason those two are types
 * rather than private constants.
 *
 * <h2>Auth: {@code isAuthenticated()}, with the real gate elsewhere</h2>
 *
 * <p>The same interim position every {@code /onboarding/**} route in this
 * codebase currently holds, and it is worth restating rather than inferring
 * from the annotation.
 *
 * <p>"Manager and Admin only" is not a capability {@code RolePermissions} can
 * express, because it is an <em>onboarding</em> role and lives in the
 * {@code moduleRoles} claim, which {@code JwtAuthoritiesConverter} does not
 * turn into a Spring authority — {@code OnboardingScopeResolver}'s own note. So
 * the decision is made inside {@link ObDashboardService} from
 * {@link CallerIdentity#moduleRole}, and a caller with no onboarding standing
 * reaches this route and is told, in words, that there is nothing to count.
 *
 * <p>A caller with no {@code ONBOARDING} entitlement at all should never get
 * that far: A-111's {@code ModuleAccessGuard} answers 404 for them, on the
 * reasoning that a 403 would disclose that the module is deployed. That guard
 * is written and not yet wired into {@code SecurityConfig} (its own javadoc
 * says so), so today such a caller reaches the handler and receives a board of
 * unavailable cards instead of a 404. Not a gap this task introduces, and
 * deliberately not one it papers over with a bespoke check here — a second
 * module gate in a feature package is how the first one comes to be relaxed
 * without anybody noticing.
 */
@RestController
@RequestMapping("/api/v1/onboarding/dashboard")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObDashboardController {

    private final ObDashboardService dashboard;
    private final ObDashboardCardItemsService cardItems;

    ObDashboardController(ObDashboardService dashboard, ObDashboardCardItemsService cardItems) {
        this.dashboard = dashboard;
        this.cardItems = cardItems;
    }

    /**
     * The OB-02 card board.
     *
     * <p>An unidentifiable caller is treated as seeing nothing rather than as
     * an error, which is {@link CallerIdentity#of}'s documented requirement on
     * everyone who reads it. {@code @PreAuthorize} has already refused the
     * anonymous case, so this branch is a second line rather than the first.
     */
    @GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObDashboardSummary", summary = "The OB-02 card board (OB-02)")
    ResponseEntity<ObDashboardSummaryResponse> summary(
            Authentication authentication,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(required = false) Long productId) {

        ObDashboardService.Rendered rendered = CallerIdentity.of(authentication)
                .map(caller -> dashboard.summary(caller, productId))
                .orElseGet(() -> new ObDashboardService.Rendered(null, null));

        if (rendered.summary() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // The summary refreshes on a timer, so a board polling faster than the
        // refresh is asking a question whose answer provably has not moved.
        // The validator says so for the cost of a hash.
        if (rendered.etag() != null && matches(ifNoneMatch, rendered.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(rendered.etag()).build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (rendered.etag() != null) {
            response = response.eTag(rendered.etag());
        }
        return response.body(new ObDashboardSummaryResponse(rendered.summary()));
    }

    /**
     * B-127 · the S-06 slide-over behind one card.
     *
     * <p>No {@code ETag} on this route — the contract declares none, unlike
     * {@link #summary}: the count above is a function of {@code computed_at}
     * alone, but this is a bounded row fetch against the live tables, and a
     * validator built from anything cheaper than the query itself would be a
     * promise this route cannot keep.
     *
     * <p>An unidentifiable caller reaches {@link ObDashboardScope#deniesEverything()}'s
     * empty page rather than an exception, on {@link #summary}'s own reasoning:
     * {@code @PreAuthorize} has already refused the anonymous case, so a
     * caller {@link CallerIdentity#of} still cannot resolve is a second,
     * narrower line rather than the first.
     */
    @GetMapping(path = "/cards/{cardKey}/items", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObDashboardCardItems", summary = "The slide-over behind one card (OB-02)")
    ObDashboardItemListResponse cardItems(
            Authentication authentication,
            @PathVariable String cardKey,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return CallerIdentity.of(authentication)
                .map(caller -> cardItems.items(caller, cardKey, productId, ownerUserId, cursor, limit))
                .orElseGet(() -> new ObDashboardItemListResponse(
                        List.of(), new ObDashboardItemListMeta(null, false, null)));
    }

    /**
     * {@code If-None-Match} against one validator.
     *
     * <p>Element-wise, because the header is defined as a comma-separated set
     * and a client sending two validators is entitled to a 304 on either.
     *
     * <p><b>The third copy of this helper in the codebase</b>, after
     * {@code DashboardController} and {@code CalendarController}, and
     * {@code DashboardController}'s own note says a shared one belongs in
     * {@code common/} "once a third appears". It is still local, because
     * {@code common/} is Stream A's directory and extracting into it is a
     * cross-stream edit that would want its own review — raised in the backlog
     * rather than taken unilaterally in a task about a dashboard.
     */
    private static boolean matches(String ifNoneMatch, String current) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (trimmed.replace("W/", "").replace("\"", "").equals(current)) {
                return true;
            }
        }
        return false;
    }
}
