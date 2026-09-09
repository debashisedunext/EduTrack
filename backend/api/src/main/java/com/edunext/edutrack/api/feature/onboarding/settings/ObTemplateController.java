package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-113 · OB-12 — the module's notification wording.
 *
 * <h2>OB Admin only, reads included</h2>
 *
 * <p>The contract states it "as on {@code /onboarding/settings}", and the
 * vocabulary route is Admin-only for the reason it gives: it is a catalogue of
 * enum values, Admin-only because the screen it feeds is.
 *
 * <h2>{@code If-Match} on the write, and this is the strongest case in either
 * module</h2>
 *
 * <p>The contract's own words: the field most likely to be edited is a long body
 * two admins can plausibly be rewording at once, and a lost update here is
 * silent <b>and outward-facing</b> — the losing edit vanishes and what reaches a
 * client is wording nobody chose.
 */
@RestController
@RequestMapping("/api/v1/onboarding/notification-templates")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObTemplateController {

    private static final String OB_ADMIN = "OB_ADMIN";

    private final ObTemplateService templates;

    ObTemplateController(ObTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObNotificationTemplates",
            summary = "The module's notification wording (OB-12)")
    ResponseEntity<ObTemplateDtos.TemplateListResponse> list(
            Authentication caller,
            @RequestParam(required = false) ObChannel channel,
            @RequestParam(required = false) ObTemplateDtos.Category category) {

        requireAdmin(caller);
        return ResponseEntity.ok(
                new ObTemplateDtos.TemplateListResponse(templates.list(channel, category)));
    }

    /**
     * <p>Ahead of {@code /{templateId}} in this file for readability; Spring
     * ranks the literal segment above the variable one regardless, so
     * {@code vocabulary} is never parsed as an id.
     */
    @GetMapping(path = "/vocabulary", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObNotificationTemplateVocabulary",
            summary = "Event codes, channels, recipients and merge tags (OB-12)")
    ResponseEntity<ObTemplateDtos.VocabularyResponse> vocabulary(Authentication caller) {
        requireAdmin(caller);
        return ResponseEntity.ok(new ObTemplateDtos.VocabularyResponse(templates.vocabulary()));
    }

    @PatchMapping(path = "/{templateId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObNotificationTemplate",
            summary = "Reword a notification (OB-12)")
    ResponseEntity<ObTemplateDtos.TemplateResponse> update(
            Authentication caller,
            @PathVariable long templateId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ObTemplateDtos.UpdateRequest request) {

        CallerIdentity identity = requireAdmin(caller);
        // Resolved first, so a template that does not exist answers 404 rather
        // than 428 — ObClientETag's rule: answering "fetch a tag" for a URL that
        // will 404 too sends the caller in a circle.
        ObTemplateDtos.Template current = templates.get(templateId);
        ObTemplateETag.require(ifMatch, current);

        ObTemplateDtos.Template saved = templates.update(templateId, request, identity.userId());
        return ResponseEntity.ok()
                .eTag(ObTemplateETag.of(saved))
                .body(new ObTemplateDtos.TemplateResponse(saved));
    }

    /** {@link ObSettingsController#requireAdmin}'s rule, and its reasoning. */
    private static CallerIdentity requireAdmin(Authentication caller) {
        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-templates route reached with no resolvable "
                                + "caller identity"));
        boolean admin = identity.moduleRole(ModuleAccessGuard.ONBOARDING)
                .filter(OB_ADMIN::equals)
                .isPresent();
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Onboarding notification templates are OB Admin only.");
        }
        return identity;
    }
}
