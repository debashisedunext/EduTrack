package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-116 · {@code /onboarding/signoffs/{signoffId}/certificate} — the archived
 * acceptance PDF (OB-05), per {@code contracts/openapi.yaml}.
 *
 * <h2>Auth, and the interim state it shares with every onboarding controller</h2>
 *
 * <p>{@code isAuthenticated()} here; row visibility is enforced inside
 * {@link ObSignoffCertificateService} via {@link ObSignoffCertificateVisibility}
 * — A-112's rule. {@code ObClientAttachmentController}'s class javadoc states
 * the position at length; this is the same interim state every onboarding
 * controller in this codebase declares, and {@code ModuleAccessGuard} is not
 * wired into {@code SecurityConfig} yet.
 *
 * <h2>{@code byte[]}, not {@code StreamingResponseBody}</h2>
 *
 * <p>{@code ObReportController}'s own class comment records
 * {@code ResponseEntity<StreamingResponseBody>} failing with "Failed to write
 * request" for every format on that route, because Spring resolves the
 * streaming handler from the <em>declared</em> return type and a
 * {@code ResponseEntity<?>} erases it. This route answers one shape only, so
 * it declares {@code ResponseEntity<byte[]>} and never runs into that — the
 * certificate is already fully materialised bytes by the time it reaches
 * here, not a cursor over rows.
 */
@RestController
@RequestMapping("/api/v1/onboarding/signoffs")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObSignoffCertificateController {

    private final ObSignoffCertificateService certificates;

    ObSignoffCertificateController(ObSignoffCertificateService certificates) {
        this.certificates = certificates;
    }

    @GetMapping(path = "/{signoffId}/certificate", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(operationId = "getObSignoffCertificate",
            summary = "The archived acceptance PDF (OB-05)")
    ResponseEntity<byte[]> certificate(Authentication caller, @PathVariable long signoffId) {
        byte[] pdf = certificates.read(scopeOf(caller), signoffId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"signoff-" + signoffId + "-certificate.pdf\"")
                .body(pdf);
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return CallerIdentity.of(caller).map(ObClientScope::of)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-signoff route reached with no resolvable "
                                + "caller identity"));
    }
}
