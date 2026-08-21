package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

/**
 * D-053 · {@code POST /chat/threads/{threadId}/attachments} — §7.6's file and
 * image share.
 *
 * <p>Multipart, like {@code AttachmentController}, and for its reason: a
 * base64 JSON body inflates the payload by a third and buffers the whole file
 * as a String before anything has decided whether it is acceptable.
 *
 * <p><b>No capability of its own.</b> Being in the thread is the whole
 * authorisation question — chat has no per-role permissions in blueprint §2,
 * and {@code ChatController} carries the same {@code isAuthenticated()} for the
 * same reason. A non-participant gets 404, never 403: the thread they are not
 * in is indistinguishable from one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/chat/threads/{threadId}/attachments")
@Tag(name = "chat")
@PreAuthorize("isAuthenticated()")
class ChatAttachmentController {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI TOO_LARGE = URI.create("https://edutrack/errors/attachment-too-large");

    private final ChatAttachmentService attachments;

    ChatAttachmentController(ChatAttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "uploadChatAttachment",
            summary = "Share a file or image into a thread (§7.6)",
            description = """
                    Answers 201 with `scanStatus: PENDING` and no `downloadUrl`. \
                    The bytes are sniffed against the declared name, EXIF-stripped \
                    and stored privately; the AV verdict follows, and the download \
                    URL is minted only once it is CLEAN.

                    Upload and send are separate requests on purpose — the file is \
                    checked while the author is still typing, so a file that will be \
                    refused is refused before they have written anything. Pass the \
                    returned id in `attachmentIds` when posting the message.""")
    ResponseEntity<ChatAttachmentDtos.ChatAttachmentResponse> upload(
            Authentication authentication,
            @PathVariable long threadId,
            @RequestParam("file") MultipartFile file) {

        return attachments
                .upload(threadId, CurrentUser.idOf(authentication), originalName(file), bytesOf(file))
                .map(view -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new ChatAttachmentDtos.ChatAttachmentResponse(view)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such thread"));
    }

    /** 413 — {@link ChatAttachmentTooLargeException}'s own javadoc says why not 400. */
    @ExceptionHandler(ChatAttachmentTooLargeException.class)
    ResponseEntity<ProblemDetail> handleTooLarge(ChatAttachmentTooLargeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setType(TOO_LARGE);
        problem.setTitle("That file is too large");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /**
     * 400 — the bytes are not a type this application accepts, or the name and
     * the content disagree. {@code AttachmentTypePolicy}'s refusal, unchanged;
     * mapped here because this controller has its own advice scope.
     */
    @ExceptionHandler(com.edunext.edutrack.api.feature.tickets.attachments
            .UnsupportedAttachmentTypeException.class)
    ResponseEntity<ProblemDetail> handleUnsupported(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The name as submitted, never the one Spring derives from a path —
     * {@code AttachmentController}'s own rule and its reason, which applies
     * identically here: the value reaches a {@code Content-Disposition} header
     * and the type policy's extension check, and neither should ever see a
     * directory separator. It never reaches the storage key at all.
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
