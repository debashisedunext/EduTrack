

package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;



/**
 * C-025 · {@code /tickets/{ticketId}/attachments}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>Still two routes. <b>C-026 added none</b>, and that is the design rather
 * than an omission: a thumbnail is served by the same short-lived signed URL
 * mechanism as the file itself, so it arrives as another field on
 * {@code listAttachments} instead of as a {@code GET …/thumbnail} endpoint that
 * would have to re-derive the scope check, the scan-status check and the
 * expiry — three chances to get §4B.4 wrong, for a redirect.
 *
 * <p>C-028's delete is a separate task with its own rules — a 15-minute window, a
 * tombstone, an uploader check — and adding a {@code DELETE} here that did none of
 * that would be worse than not having one.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally; see {@code PlannedCloseDateController}'s note and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/attachments")
@Tag(name = "attachments")
class AttachmentController {

    private final AttachmentService service;

    AttachmentController(AttachmentService service) {
        this.service = service;
    }

    /*
     * A-033 · ticket.update_progress, which all six roles hold.
     *
     * Attaching evidence is part of working a ticket, not a privileged act: §4B.4
     * lists the handoff dialog and the quick update panel among the upload
     * surfaces, and both are things a Developer, QA or Deployment resource does
     * daily. ticket.assign or ticket.close would be the wrong shape — they name
     * decisions about a ticket, and this is work on one.
     *
     * *Which* tickets is a different question and is not asked here: the caller's
     * row scope is applied inside the service by ScopedTickets, so a ticket the
     * caller may not see answers 404 (A-035) whatever their capability.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "uploadAttachment", summary = "Upload an attachment",
            description = """
                    Validated by extension allow-list **and** MIME sniffing, EXIF-stripped, \
                    stored privately under `tickets/{ticketId}/{uuid}` and virus-scanned. \
                    `scanStatus` is `PENDING` and `downloadUrl` is null until the scan passes.""")
    AttachmentDtos.AttachmentResponse upload(
            Authentication caller,
            @PathVariable long ticketId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "isClientVisible", defaultValue = "false") boolean isClientVisible,
            @RequestParam(name = "commentId", required = false) Long commentId) {

        TicketAttachment saved = service.upload(caller, ticketId,
                new AttachmentService.Upload(originalName(file), bytesOf(file), isClientVisible, commentId));

        // Deliberately no download URL on the 201, and no thumbnail URL either.
        // The scan has not run, so there is nothing to sign — and C-026 does not
        // even build a reduction until the verdict is CLEAN, because a thumbnail
        // is the file on screen. Minting either here "for convenience" would be
        // exactly the hole §4B.4's "before the file becomes visible" closes.
        return new AttachmentDtos.AttachmentResponse(AttachmentDtos.AttachmentDto.of(saved, null, null));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listAttachments", summary = "List attachments")
    AttachmentDtos.AttachmentListResponse list(
            Authentication caller,
            @PathVariable long ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestParam(required = false) Boolean clientVisibleOnly) {

        List<AttachmentDtos.AttachmentDto> data = service.list(caller, ticketId, cycle, clientVisibleOnly).stream()
                .map(row -> AttachmentDtos.AttachmentDto.of(
                        row,
                        service.signedUrlFor(row).orElse(null),
                        // C-026. Two presigns per row rather than one, and both
                        // are local signature computations — no network call and
                        // no object read — so a twenty-file gallery costs the
                        // same one query it always did.
                        service.thumbnailUrlFor(row).orElse(null)))
                .toList();

        return new AttachmentDtos.AttachmentListResponse(data);
    }

    /**
     * The name as submitted, never the one Spring derives from a path.
     *
     * <p>{@link MultipartFile#getOriginalFilename()} is attacker-controlled and
     * can be null, empty, or a full Windows path if the browser is old enough.
     * Only the last segment is kept — the value reaches
     * {@link AttachmentType#extensionOf} and a {@code Content-Disposition}
     * header, and neither should ever see a directory separator. It never
     * reaches the storage key at all ({@link AttachmentStorageKey}).
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
            // The body was announced and did not arrive. Nothing has been stored
            // and nothing has been inserted, so unwrapping to a 500 is honest —
            // this is not a request the caller can fix by changing it.
            throw new UncheckedIOException("the uploaded part could not be read", unreadable);
        }
    }
}
