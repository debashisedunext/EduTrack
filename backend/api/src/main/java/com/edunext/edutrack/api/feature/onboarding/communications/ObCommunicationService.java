package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * C-112 · communication capture (plan §6) — the per-step timeline, the
 * client-level stitched view, and the one append that writes to either.
 *
 * <h2>There is no update and no delete here, and there will not be</h2>
 *
 * <p>{@code ob_step_communications} is append-only. CLAUDE.md names this
 * service as layer 1 of the four that protect it, and the rule that follows
 * from that is short: <b>this class exposes {@link #record(ObCommunicationScope,
 * long, long, ObCommunicationDtos.ObStepCommunicationCreateRequest)} and two
 * reads.</b> A method that edited or removed an entry would be the design
 * being wrong, not this file being incomplete.
 *
 * <h2>Capture, not delivery</h2>
 *
 * <p>Recording that a call happened is not sending anything. §7's outbox is
 * what actually mails and messages a client, and conflating the two would put
 * a note somebody typed into a client's inbox. Nothing in this service
 * enqueues a notification, deliberately.
 *
 * <h2>{@code isClientVisible} defaults to false, in exactly one place</h2>
 *
 * <p>The column defaults to 0, the contract says false, and {@link
 * #record} resolves the boxed request field with the same answer — three
 * layers agreeing rather than one relying on the others. The DDL's own note
 * is the reason it is worth stating three times: an internal note that
 * reaches the portal because a default went the other way is not recoverable
 * by deleting it afterwards.
 */
@Service
public class ObCommunicationService {

    private final ObCommunicationRepository repository;

    ObCommunicationService(ObCommunicationRepository repository) {
        this.repository = repository;
    }

    /**
     * One service's own timeline, oldest first.
     *
     * <p>A caller with no onboarding standing gets an <b>empty list</b>, not a
     * 403 — {@code ObEscalationService}'s own fast path and the reason for it:
     * a 403 on a scoped read confirms the row exists.
     */
    @Transactional(readOnly = true)
    public ObCommunicationDtos.ObStepCommunicationListResponse listForStep(
            ObCommunicationScope scope, long stepId, String cursor, Integer limit) {
        if (scope.deniesEverything()) {
            return new ObCommunicationDtos.ObStepCommunicationListResponse(List.of(), PageMeta.last());
        }

        int clamped = PageLimit.clamp(limit);
        List<ObCommunicationRepository.Row> rows =
                repository.listForStep(scope, stepId, cursor, PageLimit.fetchSize(clamped));

        CursorPage<ObCommunicationRepository.Row> page = CursorPage.of(rows, clamped, ObCommunicationService::cursorOf);

        return new ObCommunicationDtos.ObStepCommunicationListResponse(
                page.data().stream().map(ObCommunicationDtos.ObStepCommunication::of).toList(),
                page.meta());
    }

    /**
     * The stitched view — every communication on every service of one client,
     * newest first. Plan §6's other half, and the one no screen draws.
     */
    @Transactional(readOnly = true)
    public ObCommunicationDtos.ObClientCommunicationListResponse listForClient(
            ObCommunicationScope scope, long obClientId, Long journeyId,
            Boolean clientVisibleOnly, String cursor, Integer limit) {
        if (scope.deniesEverything()) {
            return new ObCommunicationDtos.ObClientCommunicationListResponse(List.of(), PageMeta.last());
        }

        int clamped = PageLimit.clamp(limit);
        List<ObCommunicationRepository.Row> rows = repository.listForClient(
                scope, obClientId, journeyId, Boolean.TRUE.equals(clientVisibleOnly),
                cursor, PageLimit.fetchSize(clamped));

        CursorPage<ObCommunicationRepository.Row> page = CursorPage.of(rows, clamped, ObCommunicationService::cursorOf);

        return new ObCommunicationDtos.ObClientCommunicationListResponse(
                page.data().stream().map(ObCommunicationDtos.ObClientCommunication::of).toList(),
                page.meta());
    }

    /**
     * Appends one entry to a service's timeline.
     *
     * <p>The step is resolved under the caller's own scope first, which is
     * what makes an out-of-scope or unknown step id answer 404 rather than
     * writing a row somewhere the caller cannot see. {@code journey_id} and
     * {@code ob_client_id} come from that lookup rather than from the request
     * — a caller cannot file a communication against another client's journey
     * by naming it.
     *
     * <p><b>Written as {@code STAFF}, always.</b> This route is a person at a
     * desk recording a conversation. A client's own comment arrives through
     * the portal (CP-03) and an escalation mirror through C-126; both write
     * their own author type, and neither goes through here.
     */
    @Transactional
    public ObCommunicationDtos.ObStepCommunication record(
            ObCommunicationScope scope, long stepId, long callerId,
            ObCommunicationDtos.ObStepCommunicationCreateRequest request) {
        if (scope.deniesEverything()) {
            throw new CommunicationStepNotFoundException(stepId);
        }

        ObCommunicationRepository.StepContext context = repository.stepContext(scope, stepId)
                .orElseThrow(() -> new CommunicationStepNotFoundException(stepId));

        Instant occurredAt = request.occurredAt();
        long id = repository.insert(
                context,
                request.channel(),
                request.summary(),
                callerId,
                // The default lives here, and here only. See the class javadoc.
                Boolean.TRUE.equals(request.isClientVisible()),
                occurredAt);

        return repository.findById(scope, id)
                .map(ObCommunicationDtos.ObStepCommunication::of)
                .orElseThrow(() -> new IllegalStateException(
                        "ob_step_communications row " + id + " was inserted and immediately unreadable"));
    }

    /**
     * {@code occurred_at|id} — when the conversation happened, not when it was
     * typed. Both reads sort on it, so both cursor on it; the direction
     * differs between them and lives in the SQL, not here.
     */
    private static Cursor cursorOf(ObCommunicationRepository.Row row) {
        return new Cursor(row.occurredAt().toString(), row.id());
    }
}
