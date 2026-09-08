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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-106 · {@code /onboarding/clients/{obClientId}/requirements} — OB-05's
 * requirements list, per {@code contracts/openapi.yaml}.
 *
 * <h2>Why these are not fields on {@link ObClientController}</h2>
 *
 * <p>{@code updateObClient} said it first — "{@code contacts} and {@code
 * applications} … are their own operations because each has a side effect a
 * field update cannot carry". Requirements have a different reason, and it is
 * the plainer one: they are a <b>collection of addressable rows</b>. A list
 * field on a client PATCH can only be replaced wholesale, which means two people
 * editing different requirements on the same screen overwrite each other's row
 * — and means a client PATCH that omitted the field would either clear the list
 * or need a presence flag for it, both of which
 * {@link ObClientUpdateRequest} exists to avoid having to reason about.
 *
 * <h2>Three routes, and the {@code DELETE} is the one worth explaining</h2>
 *
 * <p>{@link ObApplicationController} has two routes and no delete, and says at
 * length why a purchase cannot be removed: {@code fk_ob_journeys_application} is
 * {@code RESTRICT} and every purchase carries a journey. The two panels sit on
 * one OB-05 screen, so somebody will reasonably ask why one list can be pruned
 * and the other cannot. The answer is that nothing at all references
 * {@code ob_client_requirements} — see {@link ObRequirementService#delete} — so
 * removing a row here destroys no record of work that was done.
 *
 * <h2>Auth, and the interim state it shares with every onboarding controller</h2>
 *
 * <p>{@code isAuthenticated()} here; the real decisions are inside
 * {@link ObRequirementService}. {@code ModuleAccessGuard} (A-111) is written and
 * not yet wired into {@code SecurityConfig}, and the module's role vocabulary —
 * OB Admin, Manager, Viewer, Sales, Step Owner — is not blueprint §2's six, so
 * {@code @PreAuthorize} has nothing true to say about it.
 * {@link ObClientController}'s class javadoc states the position at length; this
 * is the ninth controller to hold it.
 *
 * <h2>No {@code Idempotency-Key} handling</h2>
 *
 * <p>The header is accepted and not yet honoured, as everywhere else in this
 * module. Here there is no backstop index behind it at all — unlike the SPOC
 * panel's {@code uq_ob_client_contacts_email} or the purchases panel's
 * {@code uq_ob_client_applications} — because two identically worded
 * requirements are not a data error: "SSO against their Azure AD" may genuinely
 * be raised twice for two products. So a retried {@code POST} produces a
 * duplicate row that a person deletes, which is the mildest failure of the three
 * and the reason this route is the one that can wait for the replay store.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/requirements")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObRequirementController {

    private final ObRequirementService requirements;

    ObRequirementController(ObRequirementService requirements) {
        this.requirements = requirements;
    }

    /**
     * Raise a requirement.
     *
     * <p>201 with the whole client document and its new {@code ETag} — see
     * {@link ObClientETag} for why a requirement-shaped response would leave
     * OB-05's other cards holding a tag that is already stale.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObClientRequirement", summary = "Add a requirement (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> add(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientDtos.ObRequirementWriteRequest request) {

        ObClientDtos.ObClientDetail client =
                requirements.add(scopeOf(caller), obClientId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    /**
     * Edit the wording or the label, or record that it has been met.
     *
     * <p>The {@code If-Match} comes from {@code getObClient}, exactly as the SPOC
     * and purchase patches do. It matters on this list for the most ordinary
     * reason of the three: several people work through requirements at once, and
     * two of them ticking and re-wording the same row is the expected case rather
     * than the unlucky one.
     */
    @PatchMapping(path = "/{requirementId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObClientRequirement",
            summary = "Edit a requirement, or mark it met (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> update(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long requirementId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObRequirementUpdateRequest request) {

        ObClientScope scope = scopeOf(caller);
        // Resolve and scope-check the client first, so an id this caller cannot
        // see answers 404 rather than 428 — a precondition failure would send
        // them to fetch a tag from a URL that will 404 too. Every PATCH in this
        // package orders it the same way.
        ObClientETag.require(ifMatch, requirements.readable(scope, obClientId));

        ObClientDtos.ObClientDetail client =
                requirements.update(scope, obClientId, requirementId, request);

        return ResponseEntity.ok()
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    /**
     * Remove a requirement.
     *
     * <p><b>200 with the client document, not 204.</b> Every other write in this
     * package answers with the document and a fresh tag, and a 204 here would
     * leave the caller holding the tag of a client that has just changed — their
     * next save would be a 412 they cannot account for, which is precisely the
     * failure {@link ObClientETag}'s own note describes as hardest to reproduce.
     *
     * <p>{@code If-Match} is required for the same reason it is on the PATCH: a
     * delete is the least recoverable operation on this screen, and doing it
     * against a list that has moved since it was read is how the wrong row goes.
     */
    @DeleteMapping(path = "/{requirementId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deleteObClientRequirement", summary = "Remove a requirement (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> delete(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long requirementId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {

        ObClientScope scope = scopeOf(caller);
        ObClientETag.require(ifMatch, requirements.readable(scope, obClientId));

        ObClientDtos.ObClientDetail client =
                requirements.delete(scope, obClientId, requirementId);

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
                        "authenticated onboarding-requirements route reached with no resolvable "
                                + "caller identity"));
    }
}
