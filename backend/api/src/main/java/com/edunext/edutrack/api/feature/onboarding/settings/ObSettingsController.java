package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-113 · OB-11 — TAT thresholds, scanner cadence and the escalation matrix.
 *
 * <h2>OB Admin only, reads included</h2>
 *
 * <p>The contract states it and gives the reason: "org-wide settings are not a
 * row-scoped resource, so a 403 tells a non-Admin only what the 404 from
 * ModuleGuard already would". Every other onboarding read is scoped and answers
 * 404 for what it may not see, because a 403 there would confirm a row exists.
 * There is one settings row and everybody knows it exists, so hiding it buys
 * nothing and costs the caller a comprehensible refusal.
 *
 * <h2>{@code GET} is the only source of the tag {@code PUT} demands</h2>
 *
 * <p>A precondition with nowhere to satisfy it is the gap B-016 closed on
 * {@code /projects/{id}} after months. The read carries {@code ETag} and honours
 * {@code If-None-Match}; the write requires {@code If-Match} and answers 428
 * without one, on {@link ObSettingsETag}'s own reasoning.
 *
 * <h2>{@code PUT} rather than {@code PATCH}</h2>
 *
 * <p>OB-11 is one form behind one Save button, so the request is the whole
 * object. That is also what makes {@code If-Match} load-bearing here rather than
 * a formality: two admins on this screen at once would otherwise have the second
 * silently discard the first's escalation matrix along with the threshold they
 * did not touch.
 */
@RestController
@RequestMapping("/api/v1/onboarding/settings")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObSettingsController {

    private static final String OB_ADMIN = "OB_ADMIN";

    private final ObSettingsService settings;

    ObSettingsController(ObSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObSettings",
            summary = "TAT thresholds, scanner cadence and the escalation matrix (OB-11)")
    ResponseEntity<ObSettingsDtos.SettingsResponse> get(
            Authentication caller,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        requireAdmin(caller);
        ObSettingsDtos.Settings current = settings.get();
        String etag = ObSettingsETag.of(current);

        if (ifNoneMatch != null && ifNoneMatch.replace("W/", "").replace("\"", "").trim().equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .body(new ObSettingsDtos.SettingsResponse(current));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObSettings",
            summary = "Replace the onboarding settings (OB-11)")
    ResponseEntity<ObSettingsDtos.SettingsResponse> update(
            Authentication caller,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ObSettingsDtos.WriteRequest request) {

        CallerIdentity identity = requireAdmin(caller);
        // Read, precondition, then write — in that order, so a stale editor is
        // refused before anything is changed rather than after the first of the
        // four statements has landed.
        ObSettingsETag.require(ifMatch, settings.get());

        ObSettingsDtos.Settings saved = settings.replace(request, identity.userId());
        return ResponseEntity.ok()
                .eTag(ObSettingsETag.of(saved))
                .body(new ObSettingsDtos.SettingsResponse(saved));
    }

    /**
     * <p>403 rather than 404, which is the opposite of every other onboarding
     * route and is the contract's own call — see the class javadoc.
     *
     * <p>A caller with no onboarding grant at all never reaches here:
     * {@code ModuleAccessGuard} answers 404 for the whole tree first, so the
     * only person who can see this 403 already knows the module exists.
     */
    private static CallerIdentity requireAdmin(Authentication caller) {
        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-settings route reached with no resolvable "
                                + "caller identity"));
        boolean admin = identity.moduleRole(ModuleAccessGuard.ONBOARDING)
                .filter(OB_ADMIN::equals)
                .isPresent();
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Onboarding settings are OB Admin only.");
        }
        return identity;
    }
}
