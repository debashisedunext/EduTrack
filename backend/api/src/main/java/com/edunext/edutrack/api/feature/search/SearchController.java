package com.edunext.edutrack.api.feature.search;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-072 · the top bar's search box and {@code Ctrl+K} — blueprint §7.2 and gap
 * item 9.
 *
 * <h2>Reachable by every role, and narrowed by rows</h2>
 *
 * <p>{@code isAuthenticated()} rather than a capability. Every role has a top
 * bar and every role has tickets they can open; what differs is <em>which</em>,
 * and {@link SearchScope} decides that per row. A capability check would take
 * the search box away from a Developer entirely, when what §2 gives them is a
 * narrower set of results.
 *
 * <p><b>Nothing here can widen what a caller sees.</b> The people group is the
 * staff directory that {@code GET /users} already opens to every role; the
 * ticket groups go through §10.2's rule restated for SQL. An out-of-scope
 * ticket code returns no result rather than a refusal, so search cannot be used
 * to confirm that a ticket exists.
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "search")
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    /**
     * <p>No {@code ETag}. Unlike the reports beside it, this is answered while
     * somebody is typing and never twice with the same query — a validator
     * would cost a header on every keystroke and save nothing.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "globalSearch", summary = "Global search (§7.2)")
    SearchDtos.GlobalSearchResponse search(Authentication caller, @RequestParam(name = "q") String q) {
        return new SearchDtos.GlobalSearchResponse(search.search(identity(caller), q));
    }

    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached search with no CallerIdentity"));
    }
}
