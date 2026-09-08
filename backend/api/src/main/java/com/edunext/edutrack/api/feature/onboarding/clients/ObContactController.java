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
 * B-103 · {@code /onboarding/clients/{obClientId}/contacts} — OB-05's SPOC
 * panel, per {@code contracts/openapi.yaml}.
 *
 * <h2>Why these are not on {@link ObClientController}</h2>
 *
 * <p>{@code updateObClient} says why they are not fields on the client PATCH —
 * "{@code contacts} and {@code applications} … are their own operations because
 * each has a side effect a field update cannot carry". The side effect is the
 * primary slot: promoting one SPOC demotes another, and {@code
 * uq_ob_client_contacts_primary} means the demotion has to happen in the same
 * transaction or the write is refused by an index rather than by a rule anyone
 * wrote.
 *
 * <p>Separate class rather than three more methods on the client controller for
 * the reason feature packaging exists at all — see {@code ObClientService}'s own
 * note on the read/write split within one feature. {@link
 * ObClientExceptionHandler} covers both controllers, so the problem documents
 * are one set and not two.
 *
 * <h2>Auth, and the interim state it shares with every onboarding controller</h2>
 *
 * <p>{@code isAuthenticated()} here; the real decisions are inside {@link
 * ObContactService}. {@code ModuleAccessGuard} (A-111) is written and not yet
 * wired into {@code SecurityConfig}, and the module's role vocabulary — OB
 * Admin, Manager, Viewer, Sales, Step Owner — is not blueprint §2's six, so
 * {@code @PreAuthorize} has nothing true to say about it.
 * {@link ObClientController}'s class javadoc states the position at length; this
 * is the seventh controller to hold it.
 *
 * <h2>No {@code Idempotency-Key} handling, and one place it matters less</h2>
 *
 * <p>The header is accepted and not yet honoured, as everywhere else in this
 * module. The exposure is smaller here than on the wizard create: a retried
 * {@code POST} produces a second contact and then hits
 * {@code uq_ob_client_contacts_email}, so the duplicate is refused by the
 * database rather than stored. A retry that <em>changed</em> the email in
 * between is the case that would slip through, and it is a case somebody meant.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/contacts")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObContactController {

    private final ObContactService contacts;

    ObContactController(ObContactService contacts) {
        this.contacts = contacts;
    }

    /**
     * Add a SPOC.
     *
     * <p>201 with the whole client document and its new {@code ETag} — see
     * {@link ObClientETag} for why a contact-shaped response would leave OB-05's
     * other card holding a tag that is already stale.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObClientContact", summary = "Add a SPOC (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> add(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObContactDtos.ObContactUpsertRequest request) {

        ObClientDtos.ObClientDetail client =
                contacts.add(scopeOf(caller), userId(caller), obClientId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    /**
     * Edit, promote, demote, deactivate or reactivate — each of them a field on
     * this row rather than a verb of its own.
     *
     * <p>The {@code If-Match} comes from {@code getObClient}, which is what lets
     * this operation be strict where {@code updateClientContact} one module over
     * had to be exempted: its contacts hang off a collection with no tag, and
     * these are inside a tagged document.
     */
    @PatchMapping(path = "/{contactId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObClientContact", summary = "Edit a SPOC (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> update(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long contactId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObContactDtos.ObContactUpsertRequest request) {

        ObClientScope scope = scopeOf(caller);
        // Resolve and scope-check the client first, so an id this caller cannot
        // see answers 404 rather than 428 — a precondition failure would send
        // them to fetch a tag from a URL that will 404 too. ObClientController
        // orders its own PATCH the same way.
        requirePrecondition(scope, obClientId, ifMatch);

        return ok(contacts.update(scope, userId(caller), obClientId, contactId, request));
    }

    /**
     * Deactivate. Never delete.
     *
     * <p>No {@code If-Match}: this is a setter with one destination, so there is
     * no lost update for a tag to prevent — the concurrent caller wanted the
     * state the winner produced. The response still carries the client's new tag,
     * because everything else on OB-05 is editing against the old one.
     */
    @DeleteMapping(path = "/{contactId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "removeObClientContact", summary = "Remove a SPOC (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> remove(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long contactId) {

        return ok(contacts.remove(scopeOf(caller), obClientId, contactId));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void requirePrecondition(ObClientScope scope, long obClientId, String ifMatch) {
        ObClientDtos.ObClientDetail current = contacts.readable(scope, obClientId);
        ObClientETag.require(ifMatch, current);
    }

    private static ResponseEntity<ObClientDtos.ObClientDetailResponse> ok(
            ObClientDtos.ObClientDetail client) {

        return ResponseEntity.ok()
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identity(caller));
    }

    private static long userId(Authentication caller) {
        return identity(caller).userId();
    }

    /** {@code ObClientController.identity}'s reasoning, unchanged: a loud 500 beats a silent null. */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-contacts route reached with no resolvable "
                                + "caller identity"));
    }
}
