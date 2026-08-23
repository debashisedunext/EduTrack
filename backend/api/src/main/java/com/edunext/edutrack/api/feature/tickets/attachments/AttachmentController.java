

package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;



/**
 * C-025 · {@code /tickets/{ticketId}/attachments}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>Three routes. <b>C-026 added none</b>, and that is the design rather than an
 * omission: a thumbnail is served by the same short-lived signed URL mechanism as
 * the file itself, so it arrives as another field on {@code listAttachments}
 * instead of as a {@code GET …/thumbnail} endpoint that would have to re-derive
 * the scope check, the scan-status check and the expiry — three chances to get
 * §4B.4 wrong, for a redirect.
 *
 * <p><b>C-028 added the third</b>, which C-025 deliberately left out on the
 * grounds that a {@code DELETE} without the 15-minute window, the uploader check
 * and the tombstone would be worse than none. All three now exist in
 * {@link AttachmentService#delete}. Note that the contract has specified
 * {@code deleteAttachment} since D-001 and the generated client has always
 * exported it — {@code useTicketAttachments.remove} has been calling a route this
 * server did not serve, so the picker's remove button worked against the mock and
 * failed against a real backend.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally; see {@code PlannedCloseDateController}'s note and the 404s that cost.
 *
 * <p><b>{@code String}, not {@code long}</b> — the same {@code TicketDetailController}/
 * {@code ReopenController} bug: the contract's {@code TicketId} is a code like
 * {@code CRM-26-00347}, not a numeric id, so declaring it {@code long} 400s
 * against the real backend for every request a real caller sends. Fixed via
 * {@code ScopedTickets.requireByCode} (A-035).
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/attachments")
@Tag(name = "attachments")
class AttachmentController {

    private final AttachmentService service;
    private final AttachmentUserRefs people;

    AttachmentController(AttachmentService service, AttachmentUserRefs people) {
        this.service = service;
        this.people = people;
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
            @PathVariable String ticketId,
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
        // Arrays.asList and not List.of: uploaded_by is nullable — an unidentified
        // caller under dev-noauth leaves it null — and List.of throws on a null
        // element. resolve() filters nulls itself; handing it one must not be the
        // thing that turns a successful upload into a 500.
        return new AttachmentDtos.AttachmentResponse(AttachmentDtos.AttachmentDto.of(
                saved, null, null, people.resolve(Arrays.asList(saved.getUploadedBy()))));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listAttachments", summary = "List attachments")
    AttachmentDtos.AttachmentListResponse list(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestParam(required = false) Boolean clientVisibleOnly) {

        List<TicketAttachment> rows = service.list(caller, ticketId, cycle, clientVisibleOnly);

        // C-028. One lookup for the whole listing rather than one per row: a
        // twenty-file gallery from four uploaders is a single IN query, and the
        // n+1 is the version of this that would have been written by default.
        // Both id columns go in together because they overlap heavily — the
        // commonest deletion is somebody removing their own file.
        List<Long> ids = new ArrayList<>(rows.size() * 2);
        for (TicketAttachment row : rows) {
            ids.add(row.getUploadedBy());
            ids.add(row.getDeletedBy());
        }
        Map<Long, AttachmentDtos.UserRef> refs = people.resolve(ids);

        List<AttachmentDtos.AttachmentDto> data = rows.stream()
                .map(row -> AttachmentDtos.AttachmentDto.of(
                        row,
                        service.signedUrlFor(row).orElse(null),
                        // C-026. Two presigns per row rather than one, and both
                        // are local signature computations — no network call and
                        // no object read — so a twenty-file gallery costs the
                        // same one query it always did.
                        service.thumbnailUrlFor(row).orElse(null),
                        refs))
                .toList();

        return new AttachmentDtos.AttachmentListResponse(data);
    }

    /*
     * A-033 · ticket.update_progress, the same capability that guards the upload,
     * and for the same reason: removing a file you just attached to the wrong
     * ticket is part of working one, not a privileged act. All six roles hold it.
     *
     * The capability is emphatically NOT the whole check, and this is the route
     * where that distinction earns its place. It answers "may this person work
     * tickets at all"; §4B.4's rule is about *this* file and *this* person, and
     * lives in AttachmentService.delete where the uploader, the fifteen minutes
     * and the caller's role are all in scope. A permission fine-grained enough to
     * express "the uploader, or a PM" does not exist and should not — it would
     * have to be recomputed per row, which is not what an authority is.
     */
    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteAttachment", summary = "Delete an attachment",
            description = """
                    The uploader may remove a file within 15 minutes and it leaves no trace. \
                    After that — or when a PM or an administrator removes it — the row survives \
                    as a tombstone naming who removed it and when, so the ticket still records \
                    that the file was there. The stored object and its thumbnail go in every case.""")
    void delete(Authentication caller, @PathVariable String ticketId, @PathVariable long attachmentId) {
        service.delete(caller, ticketId, attachmentId);
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
