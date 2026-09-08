package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-124 · {@code /onboarding/prereq-template-tasks/{templateTaskId}} and its
 * documents — the routes that address one task of the OB-14 master rather
 * than the master itself.
 *
 * <h2>A real PATCH, where the journey designer has none</h2>
 *
 * <p>{@code ObJourneyTemplateStepController} composes a draft by adding,
 * removing and reordering; changing a step's TAT there means removing it
 * and adding it back. That works because a journey template is per-product
 * and one Admin owns one product's design.
 *
 * <p>This master is org-wide and singular, so there is exactly one draft for
 * the whole organisation and two Admins editing it at once is the normal
 * case rather than the unlucky one. Delete-plus-re-add would lose the task's
 * position and its reference documents, and on a set where
 * {@code isMandatory} decides whether a gate can ever open it is a heavier
 * operation than the edit it stands in for. Hence a PATCH, and hence a real
 * {@code If-Match} — a stale editor is refused rather than quietly
 * overwriting a colleague's change to the same checklist.
 *
 * <p><b>The tag comes from {@code getObPrereqTemplate}</b>, not from a read
 * of this row: it covers the version and every task on it, which is what
 * makes it able to detect the conflict that matters here — somebody else
 * editing a <i>different</i> task on the same draft. {@code CONVENTIONS.md}
 * §5 asks that this pairing be made by hand, because
 * {@code check-conventions.py} cannot see across the two paths.
 *
 * <p>Separate class from {@code ObPrereqTemplateController} because the
 * paths are separate resources; the exception handler covers both.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObPrereqTemplateTaskController {

    private final ObPrereqTemplateService service;
    private final ObPrereqTemplateAssembler assembler;

    ObPrereqTemplateTaskController(ObPrereqTemplateService service,
                                   ObPrereqTemplateAssembler assembler) {
        this.service = service;
        this.assembler = assembler;
    }

    @PatchMapping(path = "/prereq-template-tasks/{templateTaskId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObPrereqTemplateTask", summary = "Edit a draft task (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateTaskResponse> updateTask(
            @PathVariable long templateTaskId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObPrereqTemplateDtos.ObPrereqTemplateTaskWriteRequest request) {

        requirePrecondition(ifMatch);
        var task = service.updateTask(templateTaskId, request.title(), request.description(),
                request.tatDays(), request.isMandatory(), request.activeOrDefault());
        return ResponseEntity.ok(
                new ObPrereqTemplateDtos.ObPrereqTemplateTaskResponse(assembler.task(task)));
    }

    /**
     * Reference documents on the task go with it — the migration's cascade.
     * No {@code dependentStepIds} conflict, unlike
     * {@code removeObJourneyTemplateStep}: nothing points at a prerequisite
     * task, because there is no dependency graph here to re-point.
     */
    @DeleteMapping("/prereq-template-tasks/{templateTaskId}")
    @Operation(operationId = "removeObPrereqTemplateTask",
            summary = "Remove a task from the draft (OB-14)")
    ResponseEntity<Void> removeTask(@PathVariable long templateTaskId) {
        service.removeTask(templateTaskId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The Admin's own document, shown to the client on CP-04.
     *
     * <p>The file is uploaded through the module's shared attachment route
     * and this records the resulting {@code attachmentId} against the task,
     * so the type policy and the AV scan are the same ones every other
     * upload passes.
     */
    @PostMapping(path = "/prereq-template-tasks/{templateTaskId}/docs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObPrereqTemplateTaskDoc",
            summary = "Attach a reference document to a draft task (OB-14)")
    ResponseEntity<ObPrereqTemplateDtos.ObPrereqTemplateTaskDocResponse> addTaskDoc(
            @PathVariable long templateTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObPrereqTemplateDtos.ObPrereqTemplateTaskDocWriteRequest request) {

        var doc = service.addTaskDoc(templateTaskId, request.label(), request.attachmentId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObPrereqTemplateDtos.ObPrereqTemplateTaskDocResponse(assembler.doc(doc)));
    }

    @DeleteMapping("/prereq-template-task-docs/{docId}")
    @Operation(operationId = "removeObPrereqTemplateTaskDoc",
            summary = "Detach a reference document from a draft task (OB-14)")
    ResponseEntity<Void> removeTaskDoc(@PathVariable long docId) {
        service.removeTaskDoc(docId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The precondition is the master's tag, and it is checked before the
     * task is looked up.
     *
     * <p>That order is deliberate and is the opposite of
     * {@code ObClientController}'s. There, the 404 comes first because
     * answering 428 for a row the caller cannot see would send them to
     * fetch a tag from a URL that will 404 too. Here the tag's source —
     * {@code GET /onboarding/prereq-template} — is a different URL that
     * answers regardless of whether this task id is real, so a caller told
     * "send an If-Match" always has somewhere to go.
     */
    private void requirePrecondition(String ifMatch) {
        ObPrereqTemplateVersion draft = service.draft().orElseThrow(PrereqNoDraftException::new);

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET /onboarding/prereq-template first and send back its ETag.");
        }
        String current = Integer.toHexString(assembler.template(draft).hashCode());
        String candidate = ifMatch.trim();
        boolean ok = "*".equals(candidate)
                || candidate.replace("W/", "").replace("\"", "").equals(current);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "The prerequisites master changed since you read it. Reload and reapply your edit.");
        }
    }
}
