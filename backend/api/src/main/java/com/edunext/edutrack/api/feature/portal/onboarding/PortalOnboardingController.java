package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.escalations.ObClientEscalationService;
import com.edunext.edutrack.api.feature.onboarding.prereqs.ObClientPrereqService;
import com.edunext.edutrack.api.feature.onboarding.prereqs.ObPrereqTaskService;
import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import com.edunext.edutrack.api.feature.portal.PortalPasswordChangeGate;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/**
 * C-121 · {@code /portal/onboarding/**} — CP-03 (interactive prerequisites
 * above read-only journey accordions) and CP-04 (prerequisite task detail:
 * description, reference docs, comments, uploads, submit).
 *
 * <p>C-122 adds {@link #signoffs} for CP-05 — the sign-off list — on this
 * same controller rather than a second one, since one class per portal
 * subtree is the pattern {@code PortalTicketController} sets for {@code
 * /portal/tickets/**} and there is no reason for {@code /portal/onboarding/**}
 * to fork into two.
 *
 * <h2>No {@code obClientId} path segment, anywhere on this controller</h2>
 *
 * <p>A staff onboarding route takes an id and then checks scope against it.
 * This never takes one: {@link ClientPrincipal#obClientId()} — read from the
 * caller's own verified token — <b>is</b> the client, so there is no
 * parameter a caller could widen. "My onboarding home" needs no id at all.
 *
 * <h2>What CP-03 explicitly omits, and where each is left</h2>
 *
 * <p>No owner names, no internal communications, no block reasons — plan
 * §9's CP-03 row, and {@link PortalJourneyReader}'s query never selects them
 * in the first place, which is stronger than filtering them out afterwards.
 * <b>C-126 fills the escalate slot</b>: {@link #escalate} is the raise route,
 * {@link PortalOnboardingDtos.PortalStepDot#openEscalation()} is no longer
 * always {@code null}, and the resolve flow and the OB-05 red chip are
 * {@link com.edunext.edutrack.api.feature.onboarding.escalations.ObClientEscalationController}'s,
 * on the staff side. Nothing about this controller's other routes changed to
 * make room for it, which was the point of naming the slot ahead of time.
 *
 * <h2>Password-change gate, called explicitly on every route</h2>
 *
 * <p>{@link PortalPasswordChangeGate#require} is the first line of every
 * handler here — see that class's own note on why it is a call rather than a
 * filter.
 */
@RestController
@RequestMapping(path = "/api/v1/portal/onboarding", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "portal")
@PreAuthorize("isAuthenticated()")
public class PortalOnboardingController {

    private final ObClientPrereqService prereqs;
    private final ObPrereqTaskService tasks;
    private final PortalPrereqAssembler assembler;
    private final PortalJourneyReader journeys;
    private final PortalPrereqThreadReader thread;
    private final PortalReferenceDocReader referenceDocs;
    private final PortalPrereqAttachmentService attachments;
    private final PortalPrimaryContactReader clients;
    private final PortalPasswordChangeGate passwordChangeGate;
    private final PortalSignoffReader signoffs;
    private final PortalEscalationStepReader escalationSteps;
    private final ObClientEscalationService clientEscalations;

    PortalOnboardingController(ObClientPrereqService prereqs,
                               ObPrereqTaskService tasks,
                               PortalPrereqAssembler assembler,
                               PortalJourneyReader journeys,
                               PortalPrereqThreadReader thread,
                               PortalReferenceDocReader referenceDocs,
                               PortalPrereqAttachmentService attachments,
                               PortalPrimaryContactReader clients,
                               PortalPasswordChangeGate passwordChangeGate,
                               PortalSignoffReader signoffs,
                               PortalEscalationStepReader escalationSteps,
                               ObClientEscalationService clientEscalations) {
        this.prereqs = prereqs;
        this.tasks = tasks;
        this.assembler = assembler;
        this.journeys = journeys;
        this.thread = thread;
        this.referenceDocs = referenceDocs;
        this.attachments = attachments;
        this.clients = clients;
        this.passwordChangeGate = passwordChangeGate;
        this.signoffs = signoffs;
        this.escalationSteps = escalationSteps;
        this.clientEscalations = clientEscalations;
    }

    @GetMapping(path = "/home", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPortalOnboardingHome",
            summary = "Interactive prerequisites above read-only journey accordions (CP-03)")
    ResponseEntity<PortalOnboardingDtos.PortalOnboardingHomeResponse> home(Authentication caller) {
        passwordChangeGate.require(caller);
        long obClientId = obClientId(caller);

        List<PortalOnboardingDtos.PortalJourneyStrip> strips = journeys.journeysOf(obClientId);
        List<ObClientPrereqTask> prereqTasks = prereqs.tasksOf(obClientId);
        ObClientPrereqService.Progress progress = prereqs.progressOf(prereqTasks);
        var header = prereqs.headerOf(obClientId).orElse(null);

        PortalOnboardingDtos.PortalPrereqs prereqView = new PortalOnboardingDtos.PortalPrereqs(
                obClientId,
                header == null ? 0 : header.getTemplateVersion(),
                header == null ? null : header.getStatus().name(),
                header == null ? null : header.getClearedAt(),
                assembler.gateStatusOf(strips),
                progress.mandatoryTotal(), progress.mandatoryVerified(), progress.optionalOutstanding(),
                assembler.tasks(prereqTasks));

        String clientName = clients.clientNameOf(obClientId).orElse(null);
        return ResponseEntity.ok(new PortalOnboardingDtos.PortalOnboardingHomeResponse(
                new PortalOnboardingDtos.PortalOnboardingHome(obClientId, clientName, prereqView, strips)));
    }

    /**
     * CP-05 · pending and past sign-offs, deep-linking into the §8 flow
     * (Onboarding-Module-Plan.md §8/§9).
     *
     * <p>"Deep-linking" here means routing the client at {@code /signoff}
     * (OB-09, unchanged) and telling them which inbox to check — not a link
     * carrying a live token. {@code ob_signoffs.token_hash} cannot be turned
     * back into the plaintext a PENDING row's email holds ({@code
     * ObSignoffTokens}'s whole point), so no read anywhere in this
     * application can mint that link; only sending a fresh one can, and
     * {@code resendObSignoff} is contract-only today — no controller calls
     * it yet. Building a portal-scoped mint-and-mail path to close that gap
     * was judged out of scope for a list screen and is flagged in this
     * task's backlog entry rather than built ad hoc.
     */
    @GetMapping(path = "/signoffs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listPortalSignoffs",
            summary = "Pending and past sign-offs, deep-linking into the §8 flow (CP-05)")
    ResponseEntity<PortalOnboardingDtos.PortalSignoffListResponse> signoffs(Authentication caller) {
        passwordChangeGate.require(caller);
        long obClientId = obClientId(caller);
        return ResponseEntity.ok(new PortalOnboardingDtos.PortalSignoffListResponse(signoffs.listFor(obClientId)));
    }

    /**
     * C-126 · CP-03's Escalate control — "escalate on any running service
     * with a mandatory comment" (plan §4/§9). {@code stepId} is resolved and
     * validated against this caller's own {@code obClientId} by {@link
     * PortalEscalationStepReader} before anything is written, on {@link
     * #requireOwnTask}'s exact idiom one route up.
     *
     * <p>{@code 201} for a genuinely new escalation, {@code 200} when the
     * step already carried an open one — {@link ObClientEscalationService
     * #raise}'s own idempotent-in-effect handling of a double-click or two
     * open tabs, surfaced here as the status code rather than hidden behind
     * a uniform {@code 201}.
     */
    @PostMapping(path = "/steps/{stepId}/escalate",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "raisePortalEscalation", summary = "Escalate a running service to staff (CP-03)")
    ResponseEntity<PortalOnboardingDtos.PortalClientEscalationResponse> escalate(
            @PathVariable long stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PortalOnboardingDtos.PortalEscalationRaiseRequest request,
            Authentication caller) {

        passwordChangeGate.require(caller);
        long obClientId = obClientId(caller);
        PortalEscalationStepReader.StepContext step = escalationSteps.stepContextFor(obClientId, stepId)
                .orElseThrow(PortalOnboardingNotFoundException::new);
        if (!"IN_PROGRESS".equals(step.status())) {
            throw new PortalStepNotRunningException(stepId);
        }
        long contactId = requirePrimaryContact(obClientId);

        ObClientEscalationService.RaiseResult result = clientEscalations.raise(new ObClientEscalationService.RaiseCommand(
                obClientId, step.clientName(), step.journeyId(), stepId, step.stepName(), step.productName(),
                step.ownerUserId(), contactId, request.comment(), Instant.now()));

        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new PortalOnboardingDtos.PortalClientEscalationResponse(
                new PortalOnboardingDtos.PortalClientEscalation(
                        result.id(), result.comment(), result.raisedAt(), result.isNew())));
    }

    @GetMapping(path = "/prereq-tasks/{prereqTaskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPortalPrereqTask", summary = "One prerequisite task in full (CP-04)")
    ResponseEntity<PortalOnboardingDtos.PortalPrereqTaskDetailResponse> task(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            Authentication caller) {

        passwordChangeGate.require(caller);
        ObClientPrereqTask task = requireOwnTask(prereqTaskId, caller);

        PortalOnboardingDtos.PortalPrereqTaskDetail detail = PortalOnboardingDtos.PortalPrereqTaskDetail.of(
                assembler.task(task),
                referenceDocs.referenceDocsOf(task.getTemplateTaskId()),
                attachments.submissionsOf(prereqTaskId));
        String etag = Integer.toHexString(detail.hashCode());
        if (etag.equals(unquoted(ifNoneMatch))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag)
                .body(new PortalOnboardingDtos.PortalPrereqTaskDetailResponse(detail));
    }

    @PostMapping(path = "/prereq-tasks/{prereqTaskId}/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitPortalPrereqTask",
            summary = "Mark a task done and send it for verification (CP-04)")
    ResponseEntity<PortalOnboardingDtos.PortalPrereqTaskResponse> submit(
            @PathVariable long prereqTaskId,
            @RequestBody(required = false) @Valid PortalOnboardingDtos.PortalPrereqSubmitRequest request,
            Authentication caller) {

        passwordChangeGate.require(caller);
        requireOwnTask(prereqTaskId, caller);
        long contactId = requirePrimaryContact(obClientId(caller));

        ObClientPrereqTask updated = tasks.submit(prereqTaskId, null, contactId,
                request == null ? null : request.note());
        return ResponseEntity.ok(new PortalOnboardingDtos.PortalPrereqTaskResponse(assembler.task(updated)));
    }

    @GetMapping(path = "/prereq-tasks/{prereqTaskId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listPortalPrereqComments", summary = "The task's comment thread, oldest first (CP-04)")
    ResponseEntity<PortalOnboardingDtos.PortalPrereqCommentListResponse> comments(
            @PathVariable long prereqTaskId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            Authentication caller) {

        passwordChangeGate.require(caller);
        requireOwnTask(prereqTaskId, caller);
        return ResponseEntity.ok(thread.comments(prereqTaskId, cursor, limit));
    }

    @PostMapping(path = "/prereq-tasks/{prereqTaskId}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addPortalPrereqComment", summary = "Say something about this task (CP-04)")
    ResponseEntity<PortalOnboardingDtos.PortalPrereqCommentResponse> addComment(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PortalOnboardingDtos.PortalPrereqCommentCreateRequest request,
            Authentication caller) {

        passwordChangeGate.require(caller);
        requireOwnTask(prereqTaskId, caller);
        long contactId = requirePrimaryContact(obClientId(caller));

        // ObPrereqTaskService.addComment is the one door into ob_prereq_comments
        // (append-only); this route only chooses CLIENT/contactId over
        // STAFF/userId, exactly as its own javadoc anticipates.
        long id = tasks.addComment(prereqTaskId, ObPrereqActorType.CLIENT, null, contactId, request.body(), false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PortalOnboardingDtos.PortalPrereqCommentResponse(thread.comment(id)));
    }

    @PostMapping(path = "/prereq-tasks/{prereqTaskId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "uploadPortalPrereqAttachment",
            summary = "Attach evidence to a prerequisite task (CP-04)")
    ResponseEntity<PortalOnboardingDtos.PortalAttachmentResponse> upload(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file,
            Authentication caller) {

        passwordChangeGate.require(caller);
        requireOwnTask(prereqTaskId, caller);
        long contactId = requirePrimaryContact(obClientId(caller));

        PortalOnboardingDtos.PortalSubmissionFile saved =
                attachments.upload(prereqTaskId, contactId, originalName(file), bytesOf(file));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PortalOnboardingDtos.PortalAttachmentResponse(saved));
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    /**
     * 404 for a task on another client, and the same 404 for one that does
     * not exist, and the same 404 again for a caller whose token carries no
     * onboarding client at all — {@code ObPrereqTaskController.requireVisible}'s
     * pattern, restated for a principal with no scope resolver to delegate
     * to (there is nothing to scope: {@code obClientId} is read from the
     * token, not supplied).
     */
    private ObClientPrereqTask requireOwnTask(long prereqTaskId, Authentication caller) {
        long obClientId = obClientId(caller);
        ObClientPrereqTask task = tasks.require(prereqTaskId);
        if (task.getObClientId() == null || task.getObClientId() != obClientId) {
            throw new PortalOnboardingNotFoundException();
        }
        return task;
    }

    private long requirePrimaryContact(long obClientId) {
        return clients.activePrimaryContactId(obClientId).orElseThrow(PortalNoPrimaryContactException::new);
    }

    private static long obClientId(Authentication caller) {
        ClientPrincipal principal = ClientPrincipal.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated portal-onboarding route reached with no resolvable CLIENT principal"));
        Long obClientId = principal.obClientId();
        if (obClientId == null) {
            throw new PortalOnboardingNotFoundException();
        }
        return obClientId;
    }

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

    private static String unquoted(String etag) {
        return etag == null ? null : etag.replace("W/", "").replace("\"", "").trim();
    }
}
