package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentDtos;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * B-107 · {@code /onboarding/clients/{obClientId}/attachments} — OB-05's
 * documents card, per {@code contracts/openapi.yaml}.
 *
 * <h2>Multipart, and why not JSON</h2>
 *
 * <p>{@code AttachmentController} and {@code ChatAttachmentController} both take
 * multipart, for a reason that is unchanged here: a base64 JSON body inflates the
 * payload by a third and buffers the whole file as a String before anything has
 * decided whether it is acceptable. A file that is going to be refused should be
 * refused having cost as little as possible.
 *
 * <h2>Three routes and no {@code If-Match}</h2>
 *
 * <p>Every other write in this package answers with the whole
 * {@code ObClientDetail} and takes a precondition. These do neither, and
 * {@link ObClientAttachmentService}'s class note has the argument: the AV scan
 * moves {@code scanStatus} on a background thread with no user action behind it,
 * so folding attachments into the client document would move the client's
 * {@code ETag} for a change nobody made and hand a 412 to whoever happened to be
 * editing the address.
 *
 * <p>The consequence worth naming is that the documents card refreshes itself
 * rather than riding on the client document's tag — it has to, because a
 * PENDING row becomes CLEAN without anybody saving anything.
 *
 * <h2>Auth, and the interim state it shares with every onboarding controller</h2>
 *
 * <p>{@code isAuthenticated()} here; the real decisions are inside the service
 * and, for the module role, in {@code ObModuleRoleRules}. The module's role
 * vocabulary — OB Admin, Manager, Viewer, Sales, Step Owner — is not blueprint
 * §2's six, so {@code @PreAuthorize} has nothing true to say about it.
 * {@link ObClientController}'s class javadoc states the position at length; this
 * is the tenth controller to hold it.
 *
 * <h2>No {@code Idempotency-Key} handling</h2>
 *
 * <p>The header is accepted and not yet honoured, as everywhere else in this
 * module. What a retried upload produces here is a second row pointing at a
 * second object with the same bytes — visible, removable, and the mildest of the
 * three failures the module's creates can have. {@code content_sha256} is
 * written on every row precisely so a future replay store, or a duplicate
 * sweeper, has something exact to match on rather than a file name.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/attachments")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObClientAttachmentController {

    private final ObClientAttachmentService attachments;

    ObClientAttachmentController(ObClientAttachmentService attachments) {
        this.attachments = attachments;
    }

    /**
     * File a document against the client.
     *
     * <p>201 with the row as inserted — {@code scanStatus} is PENDING and there
     * is no {@code downloadUrl}, which is what the contract's 201 describes and
     * not an error state. The client reloads the card once the scan lands.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "uploadObClientAttachment",
            summary = "File a document against a client (OB-05)")
    ResponseEntity<ObAttachmentDtos.ObAttachmentResponse> upload(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "kind", required = false) String kind) {

        ObAttachmentDtos.ObAttachmentView view = attachments.upload(
                scopeOf(caller), obClientId, kind, originalName(file), bytesOf(file));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObAttachmentDtos.ObAttachmentResponse(view));
    }

    /**
     * The documents on this client, oldest first, tombstones included where the
     * record should keep them.
     *
     * <p>No {@code ETag}. The set changes without anybody writing to it — a
     * PENDING row becomes CLEAN and its {@code downloadUrl} appears — so a tag
     * minted here would be stale by the time it was useful, and a signed URL is
     * re-minted on every read anyway because it expires in minutes.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObClientAttachments",
            summary = "Documents filed against a client (OB-05)")
    ResponseEntity<ObAttachmentDtos.ObAttachmentListResponse> list(
            Authentication caller,
            @PathVariable long obClientId) {

        List<ObAttachmentDtos.ObAttachmentView> views = attachments.list(scopeOf(caller), obClientId);
        return ResponseEntity.ok(new ObAttachmentDtos.ObAttachmentListResponse(views));
    }

    /**
     * Remove a document.
     *
     * <p><b>204, where the other panels answer 200 with the client document.</b>
     * That difference follows from the same fact the missing {@code If-Match}
     * does: attachments are not inside the client document, so there is no fresh
     * tag to hand back and returning the client would be returning something this
     * operation did not change.
     */
    @DeleteMapping("/{attachmentId}")
    @Operation(operationId = "deleteObClientAttachment",
            summary = "Remove a document from a client (OB-05)")
    ResponseEntity<Void> delete(
            Authentication caller,
            @PathVariable long obClientId,
            @PathVariable long attachmentId) {

        attachments.delete(scopeOf(caller), obClientId, attachmentId);
        return ResponseEntity.noContent().build();
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
                        "authenticated onboarding-attachments route reached with no resolvable "
                                + "caller identity"));
    }

    /**
     * The name as submitted, never the one Spring derives from a path.
     *
     * <p>{@code AttachmentController}'s rule and its reason, which applies
     * identically here: the value reaches a {@code Content-Disposition} header
     * and the type policy's extension check, and neither should ever see a
     * directory separator. It never reaches the storage key at all — that is a
     * UUID this application mints.
     */
    private static String originalName(MultipartFile file) {
        String submitted = file.getOriginalFilename();
        if (submitted == null || submitted.isBlank()) {
            return "";
        }
        String name = submitted.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the uploaded part could not be read", unreadable);
        }
    }
}
