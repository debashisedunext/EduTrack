package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-104 · {@code /onboarding/clients/{obClientId}/applications} — OB-05's
 * purchases panel, per {@code contracts/openapi.yaml}.
 *
 * <h2>Why these are not fields on {@link ObClientController}</h2>
 *
 * <p>{@code updateObClient} said it first — "{@code contacts} and {@code
 * applications} … are their own operations because each has a side effect a
 * field update cannot carry". For the SPOCs that side effect is the primary
 * slot; here it is <b>a journey</b>. Adding a purchase instantiates one, and a
 * client PATCH that silently created a journey and its whole step tree as a
 * consequence of a list field changing would be the least discoverable write in
 * the module.
 *
 * <h2>Two routes, not three</h2>
 *
 * <p>There is no {@code DELETE}. {@link ObApplicationService} gives the argument
 * in full: {@code fk_ob_journeys_application} is {@code RESTRICT} and every
 * purchase carries a journey from the moment it is made, so the operation could
 * only be implemented as a route that always fails or as this package deleting
 * another stream's work. It is left off rather than shipped broken, and named as
 * left off in {@code README.md}.
 *
 * <h2>Auth, and the interim state it shares with every onboarding controller</h2>
 *
 * <p>{@code isAuthenticated()} here; the real decisions are inside {@link
 * ObApplicationService}. {@code ModuleAccessGuard} (A-111) is written and not yet
 * wired into {@code SecurityConfig}, and the module's role vocabulary — OB Admin,
 * Manager, Viewer, Sales, Step Owner — is not blueprint §2's six, so
 * {@code @PreAuthorize} has nothing true to say about it. {@link
 * ObClientController}'s class javadoc states the position at length; this is the
 * eighth controller to hold it.
 *
 * <h2>No {@code Idempotency-Key} handling, and here it bites harder than usual</h2>
 *
 * <p>The header is accepted and not yet honoured, as everywhere else in this
 * module. On the SPOC panel a retried {@code POST} is caught by
 * {@code uq_ob_client_contacts_email}; here the equivalent backstop is
 * {@code uq_ob_client_applications}, which is stricter — a retried purchase of
 * the same product is refused by the index whatever the rest of the body says,
 * because the product is the whole key. What a retry can still produce is the
 * second-guessing that follows a 409 the caller did not expect, which is why
 * {@link DuplicateApplicationProductException} hands back the id of the purchase
 * that is already there.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/applications")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObApplicationController {

    private final ObApplicationService applications;

    ObApplicationController(ObApplicationService applications) {
        this.applications = applications;
    }

    /**
     * Record a purchase, and instantiate the journey it produces.
     *
     * <p>201 with the whole client document and its new {@code ETag} — see
     * {@link ObClientETag} for why an application-shaped response would leave
     * OB-05's other cards holding a tag that is already stale. It matters more
     * here than on the SPOC panel: this write also adds a journey strip, so a
     * caller that did not re-read would be looking at an accordion missing the
     * thing they just created.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObClientApplication", summary = "Record a purchase (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> add(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientDtos.ObApplicationWriteRequest request) {

        ObClientDtos.ObClientDetail client = applications.add(scopeOf(caller), obClientId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    /**
     * Edit the licence type, the seat count or the licence window — which is
     * what a renewal is.
     *
     * <p>The {@code If-Match} comes from {@code getObClient}, exactly as the SPOC
     * patch's does, and for a reason this route makes concrete: two people
     * renewing the same licence to different end dates is a lost update that
     * nothing else in the system would ever surface, because no downstream reads
     * {@code license_end} yet to notice it went the wrong way.
     */
    @PatchMapping(path = "/{applicationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObClientApplication", summary = "Edit a purchase (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> update(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long applicationId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObClientDtos.ObApplicationWriteRequest request) {

        ObClientScope scope = scopeOf(caller);
        // Resolve and scope-check the client first, so an id this caller cannot
        // see answers 404 rather than 428 — a precondition failure would send
        // them to fetch a tag from a URL that will 404 too. ObClientController
        // and ObContactController both order their PATCH the same way.
        ObClientETag.require(ifMatch, applications.readable(scope, obClientId));

        ObClientDtos.ObClientDetail client =
                applications.update(scope, obClientId, applicationId, request);

        return ResponseEntity.ok()
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identity(caller));
    }

    /** {@code ObClientController.identity}'s reasoning, unchanged: a loud 500 beats a silent null. */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-applications route reached with no resolvable "
                                + "caller identity"));
    }
}
