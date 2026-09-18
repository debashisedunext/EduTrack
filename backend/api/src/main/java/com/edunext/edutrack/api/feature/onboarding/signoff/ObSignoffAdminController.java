package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff sign-off surface — {@code listObSignoffs}, {@code requestObSignoff},
 * {@code getObSignoff}, {@code resendObSignoff} and {@code cancelObSignoff}.
 *
 * <h2>Two base paths, one controller</h2>
 *
 * <p>Four of the five hang off {@code /onboarding/signoffs} and one — the
 * request — is nested under {@code /onboarding/journeys/&#123;journeyId&#125;/signoffs},
 * because a sign-off is created <em>within</em> a journey and addressed
 * afterwards by its own id. Rather than split one feature across two
 * controllers, the class maps the plural noun and the nested route is spelled in
 * full on its own method. {@code ObImplementationStageController} and
 * {@code ObJourneyTemplateController} both map the bare {@code /api/v1/onboarding}
 * prefix for the same reason; this is the narrower version of that.
 *
 * <p>{@code getObSignoffCertificate} stays in
 * {@link ObSignoffCertificateController}. It is B-116's, it streams a PDF rather
 * than JSON, and moving it here to tidy the URL space would be an edit to a
 * finished task for no behavioural gain.
 *
 * <h2>Authorisation</h2>
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} at class level, matching every
 * other onboarding controller. The real narrowing is A-112's row scope, applied
 * in SQL by {@link ObSignoffAdminRepository} rather than by a role check here:
 * a PM sees their own clients' sign-offs and nobody else's, and a caller with no
 * onboarding standing sees an empty list rather than a 403. Out of scope answers
 * 404, never 403 — blueprint §2's no-existence-leak rule.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObSignoffAdminController {

    private final ObSignoffAdminService service;

    ObSignoffAdminController(ObSignoffAdminService service) {
        this.service = service;
    }

    /**
     * OB-05's panel and OB-10's pending report read this.
     *
     * <p>No {@code ETag}: a keyset page has no single version to tag, and
     * CONVENTIONS.md §5 puts tags on detail reads rather than on lists.
     */
    @GetMapping(path = "/signoffs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObSignoffs", summary = "Sign-offs across clients (OB-05, OB-10)")
    ResponseEntity<ObSignoffAdminDtos.ObSignoffListResponse> list(
            Authentication caller,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long obClientId,
            @RequestParam(required = false) Long journeyId,
            @RequestParam(required = false) ObSignoffKind kind,
            @RequestParam(required = false) ObSignoffStatus status) {

        return ResponseEntity.ok(service.list(
                scopeOf(caller), obClientId, journeyId, kind, status, cursor, limit));
    }

    /**
     * The acceptance evidence — who signed, when, from what IP and user agent.
     *
     * <p>Read-only, and there is no operation anywhere that edits those four
     * fields. PHASE-2-BUILD-PLAN decision 5 chose recorded acceptance over
     * statutory e-sign for v1, and this response is what that decision produces.
     */
    @GetMapping(path = "/signoffs/{signoffId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObSignoff", summary = "One sign-off, with its acceptance record (OB-05)")
    ResponseEntity<ObSignoffAdminDtos.ObSignoffDetailResponse> get(
            Authentication caller, @PathVariable long signoffId) {

        ObSignoffAdminDtos.ObSignoffDetail detail = service.get(scopeOf(caller), signoffId);
        return ResponseEntity.ok()
                .eTag(Integer.toHexString(detail.hashCode()))
                .body(new ObSignoffAdminDtos.ObSignoffDetailResponse(detail));
    }

    /**
     * Ask a client to sign off a service, or the go-live.
     *
     * <p>{@code 201}: this creates the row the whole of §8 hangs off.
     * {@code data.tokenExpiresAt} is on the response so OB-05 can say when the
     * link dies; the token itself is on no response in the contract and is not
     * returned here to anybody, the requester included.
     */
    @PostMapping(path = "/journeys/{journeyId}/signoffs",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "requestObSignoff",
            summary = "Ask a client to sign off a service, or the go-live (OB-05)")
    ResponseEntity<ObSignoffAdminDtos.ObSignoffResponse> request(
            Authentication caller,
            @PathVariable long journeyId,
            @Valid @RequestBody ObSignoffAdminDtos.ObSignoffRequestBody body) {

        CallerIdentity identity = identityOf(caller);
        ObSignoffAdminDtos.ObSignoff created =
                service.request(ObClientScope.of(identity), journeyId, body, identity.userId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObSignoffAdminDtos.ObSignoffResponse(created));
    }

    /**
     * Send a fresh link; the old one stops working.
     *
     * <p>{@code 200} rather than {@code 201} — the sign-off already exists and
     * this reissues its token. Not "sends the same link again", which is what
     * the word resend suggests and is impossible: only the SHA-256 is stored.
     */
    @PostMapping(path = "/signoffs/{signoffId}/resend", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resendObSignoff", summary = "Send a fresh link — the old one stops working (OB-05)")
    ResponseEntity<ObSignoffAdminDtos.ObSignoffResponse> resend(
            Authentication caller, @PathVariable long signoffId) {

        return ResponseEntity.ok(new ObSignoffAdminDtos.ObSignoffResponse(
                service.resend(scopeOf(caller), signoffId)));
    }

    /**
     * Withdraw a request that should not have been sent.
     *
     * <p>The row becomes {@code CANCELLED} rather than disappearing, so the
     * staff side can still answer a client who remembers being asked.
     */
    @PostMapping(path = "/signoffs/{signoffId}/cancel",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelObSignoff", summary = "Withdraw a request that should not have been sent (OB-05)")
    ResponseEntity<ObSignoffAdminDtos.ObSignoffResponse> cancel(
            Authentication caller,
            @PathVariable long signoffId,
            @Valid @RequestBody ObSignoffAdminDtos.ObSignoffCancelRequest body) {

        CallerIdentity identity = identityOf(caller);
        return ResponseEntity.ok(new ObSignoffAdminDtos.ObSignoffResponse(
                service.cancel(ObClientScope.of(identity), signoffId, body.reason(), identity.userId())));
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identityOf(caller));
    }

    private static CallerIdentity identityOf(Authentication caller) {
        return CallerIdentity.of(caller).orElseThrow(() -> new IllegalStateException(
                "authenticated onboarding-signoff route reached with no resolvable caller identity"));
    }
}
