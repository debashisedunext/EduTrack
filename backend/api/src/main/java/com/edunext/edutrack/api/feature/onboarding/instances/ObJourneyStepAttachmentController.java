package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentDtos;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentOwner;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentPipeline;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/** C-121 - the task-owned document upload used by OB-06's Attach action. */
@RestController
@RequestMapping("/api/v1/onboarding/journey-steps/{stepId}/attachments")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyStepAttachmentController {

    private final ObJourneyStepLifecycleService steps;
    private final ObAttachmentPipeline pipeline;
    private final UserRepository users;

    ObJourneyStepAttachmentController(ObJourneyStepLifecycleService steps,
                                      ObAttachmentPipeline pipeline,
                                      UserRepository users) {
        this.steps = steps;
        this.pipeline = pipeline;
        this.users = users;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "uploadObJourneyStepAttachment",
            summary = "Attach a document to a running task (OB-06)")
    ResponseEntity<ObAttachmentDtos.ObAttachmentResponse> upload(
            Authentication caller,
            @PathVariable long stepId,
            @RequestParam("file") MultipartFile file) {
        long userId = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException("authenticated attachment route reached without identity"))
                .userId();
        steps.requireAttachmentAccess(stepId, userId);
        ObAttachment row = pipeline.store(
                ObAttachmentOwner.STEP, stepId, ObAttachmentKind.SUBMISSION,
                ObAttachmentPipeline.Uploader.staff(userId), originalName(file), bytesOf(file));

        User user = users.findById(userId).orElse(null);
        String displayName = user == null ? null : user.getFullName();
        ObAttachmentDtos.ObAttachmentView view = new ObAttachmentDtos.ObAttachmentView(
                row.getId(), row.getFileName(), row.getContentType(), row.getSizeBytes(),
                row.getKind().name(), row.getUploadedByType().name(), row.getScanStatus().name(),
                null, false, ObAttachmentDtos.ActorRef.of(userId, displayName),
                null, null, row.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObAttachmentDtos.ObAttachmentResponse(view));
    }

    private static String originalName(MultipartFile file) {
        String submitted = file.getOriginalFilename();
        if (submitted == null || submitted.isBlank()) return "";
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
