package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.api.feature.onboarding.communications.ObCommunicationService;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-126 · {@code ob_client_escalations} — raised by a client from CP-03,
 * seen and resolved by staff on OB-05 (and, per the contract's own comment,
 * counted on OB-02 — that card's own query is A-118/B-127's, not built
 * here).
 *
 * <h2>One service, two callers</h2>
 *
 * <p>{@link #list} and {@link #resolve} are called from {@link
 * ObClientEscalationController} (staff, module-role scoped). {@link #raise}
 * is called directly from {@code PortalOnboardingController} — {@code
 * ObClientPrereqService}'s own precedent for a portal route reaching a
 * non-portal feature service rather than a parallel one being built. The
 * portal controller resolves and validates the calling client's own step
 * context (ownership, {@code IN_PROGRESS} status) before calling {@link
 * #raise}; this class has no module-role scope to apply to that path — see
 * {@link ObClientEscalationRepository}'s own note on why {@code insert} and
 * {@code findOpenByStep} are unscoped.
 *
 * <h2>Raising is idempotent in effect, not just DB-guarded</h2>
 *
 * <p>{@code uq_ob_client_escalations_open} exists so "a client tapping
 * Escalate twice on a slow connection" (the migration's own words) cannot
 * queue two unmutable notifications for one complaint. {@link #raise}
 * checks for an existing open row first and, failing that, catches the
 * {@link DuplicateKeyException} a lost race throws — either way the caller
 * gets the one true open escalation back rather than an error, exactly
 * {@code acknowledgeObEscalation}'s "idempotent in effect" reasoning.
 *
 * <h2>The two notification events already exist</h2>
 *
 * <p>{@code ObNotificationEvent.CLIENT_ESCALATION_RAISED}/{@code
 * _RESOLVED} are B-111's catalogue entries, written ahead of this task.
 * Raising notifies the onboarding manager and the step's owner, immediately
 * and on two channels (plan §7); resolving notifies the client contact who
 * raised it, on {@code EMAIL} only — plan §7 calls resolving an
 * acknowledgement back to the client, not a second incident, and nothing in
 * the plan asks for it on WhatsApp too. Neither payload carries {@code
 * action_url} — {@code ObEscalationRaise}/{@code ObTatBreach} in the worker
 * module omit it from their own payloads the same way, on the same
 * precedent: the composer resolves the deep link from {@code stepId}, not
 * from the enqueuer's payload.
 *
 * <h2>What this class deliberately does not write</h2>
 *
 * <p>No {@code ob_step_history} row. Plan §4's own account of this table
 * names exactly one side effect beyond the row itself — the communications
 * mirror — unlike the internal ladder ({@code ObEscalationRaise}), which the
 * plan explicitly puts on the hash-chained journal. Adding one here would be
 * inventing a record the spec does not ask for.
 */
@Service
public class ObClientEscalationService {

    private static final Logger log = LoggerFactory.getLogger(ObClientEscalationService.class);

    private final ObClientEscalationRepository repository;
    private final ObCommunicationService communications;
    private final ObOutboxEnqueuer outbox;

    ObClientEscalationService(ObClientEscalationRepository repository,
                              ObCommunicationService communications,
                              ObOutboxEnqueuer outbox) {
        this.repository = repository;
        this.communications = communications;
        this.outbox = outbox;
    }

    /** The staff read — empty for a caller with no onboarding standing, {@code ObEscalationService.list}'s own fast path. */
    @Transactional(readOnly = true)
    public ObClientEscalationDtos.ObClientEscalationListResponse list(
            ObEscalationScope scope, Long obClientId, Long journeyId, String state, String cursor, Integer limit) {
        if (scope.deniesEverything()) {
            return new ObClientEscalationDtos.ObClientEscalationListResponse(List.of(), PageMeta.last());
        }

        int clamped = PageLimit.clamp(limit);
        List<ObClientEscalationRepository.Row> rows = repository.list(
                scope, obClientId, journeyId, state, cursor, PageLimit.fetchSize(clamped));

        CursorPage<ObClientEscalationRepository.Row> page = CursorPage.of(rows, clamped,
                row -> new Cursor(row.raisedAt().toString(), row.id()));

        return new ObClientEscalationDtos.ObClientEscalationListResponse(
                page.data().stream().map(ObClientEscalationDtos.ObClientEscalation::of).toList(),
                page.meta());
    }

    /**
     * The client's own raise, called from the portal. See the class javadoc
     * for why no {@link ObEscalationScope} is threaded through this path.
     *
     * <p>Answers the caller's own small {@link RaiseResult} rather than the
     * staff-shaped {@link ObClientEscalationDtos.ObClientEscalation} —
     * deliberately: that record (and the contact/user refs it carries) is
     * package-private, matching {@code PortalPrereqComment}'s own precedent
     * of a genuinely separate, narrower portal shape rather than a shared one
     * widened for a second caller.
     */
    @Transactional
    public RaiseResult raise(RaiseCommand cmd) {
        var alreadyOpen = repository.findOpenByStep(cmd.stepId());
        if (alreadyOpen.isPresent()) {
            var row = alreadyOpen.get();
            return new RaiseResult(row.id(), row.comment(), row.raisedAt(), false);
        }

        long id;
        try {
            id = repository.insert(cmd.obClientId(), cmd.journeyId(), cmd.stepId(),
                    cmd.raisedByContactId(), cmd.comment(), cmd.now());
        } catch (DuplicateKeyException raceLost) {
            // Another request won uq_ob_client_escalations_open between our
            // check and our insert. Not ours to announce a second time.
            ObClientEscalationRepository.Row row = repository.findOpenByStep(cmd.stepId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ob_client_escalations insert lost a race but no open row exists for step "
                                    + cmd.stepId()));
            return new RaiseResult(row.id(), row.comment(), row.raisedAt(), false);
        }

        ObClientEscalationRepository.Row row = repository.findOpenByStep(cmd.stepId())
                .orElseThrow(() -> new IllegalStateException(
                        "ob_client_escalations row " + id + " was inserted and immediately unreadable"));

        communications.recordEscalationRaised(
                cmd.stepId(), cmd.journeyId(), cmd.obClientId(), cmd.raisedByContactId(), cmd.comment(), cmd.now());
        enqueueRaiseNotifications(cmd);

        return new RaiseResult(row.id(), row.comment(), row.raisedAt(), true);
    }

    /**
     * Answers the client's escalation. Resolution note mandatory, and it
     * goes to the client — the contract's own description of {@code
     * resolveObClientEscalation}.
     */
    @Transactional
    public ObClientEscalationDtos.ObClientEscalation resolve(
            ObEscalationScope scope, long escalationId, long resolvedByUserId, String note) {
        ObClientEscalationRepository.Row row = repository.findById(scope, escalationId)
                .orElseThrow(() -> new ObClientEscalationNotFoundException(escalationId));
        if (row.resolvedAt() != null) {
            throw new ObClientEscalationAlreadyResolvedException(escalationId);
        }

        Instant now = Instant.now();
        boolean won = repository.resolve(escalationId, resolvedByUserId, note, now);
        if (!won) {
            // Somebody else's resolve landed between our read and our update.
            throw new ObClientEscalationAlreadyResolvedException(escalationId);
        }

        communications.recordEscalationResolved(
                row.stepId(), row.journeyId(), row.obClientId(), resolvedByUserId, note, now);
        enqueueResolveNotification(row, note);

        ObClientEscalationRepository.Row resolved = repository.findById(scope, escalationId)
                .orElseThrow(() -> new ObClientEscalationNotFoundException(escalationId));
        return ObClientEscalationDtos.ObClientEscalation.of(resolved);
    }

    /**
     * The onboarding manager (earliest live {@code OB_MANAGER} grant — {@code
     * ObEscalationLadderRepository.resolveObAdmin}'s own idiom, one role
     * over) and the step's own owner. Plan §7 names both by role rather than
     * by "whoever the ladder's L2 would reach", which is the step owner's
     * <em>reporting</em> manager and a different person.
     */
    private void enqueueRaiseNotifications(RaiseCommand cmd) {
        Set<Long> recipients = new LinkedHashSet<>();
        Long manager = repository.resolveOnboardingManager();
        if (manager != null) {
            recipients.add(manager);
        }
        if (cmd.ownerUserId() != null) {
            recipients.add(cmd.ownerUserId());
        }

        if (recipients.isEmpty()) {
            log.warn("ob-client-escalation: step {} escalated with no onboarding manager or owner resolved to tell",
                    cmd.stepId());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", cmd.clientName());
        payload.put("escalation_comment", cmd.comment());
        payload.put("product_name", cmd.productName());
        payload.put("raised_at", cmd.now().toString());

        for (long recipient : recipients) {
            ObRecipient.Staff staff = new ObRecipient.Staff(recipient);
            for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.WHATSAPP}) {
                outbox.enqueue(ObNotification.aboutStep(
                        ObNotificationEvent.CLIENT_ESCALATION_RAISED.key(), channel, staff,
                        cmd.obClientId(), cmd.journeyId(), cmd.stepId(), payload));
            }
        }
    }

    /** Back to the SPOC who raised it, {@code EMAIL} only — see the class javadoc. */
    private void enqueueResolveNotification(ObClientEscalationRepository.Row row, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", row.obClientName());
        payload.put("resolution_note", note);

        ObRecipient.Client client = new ObRecipient.Client(row.contactId());
        outbox.enqueue(ObNotification.aboutStep(
                ObNotificationEvent.CLIENT_ESCALATION_RESOLVED.key(), ObChannel.EMAIL, client,
                row.obClientId(), row.journeyId(), row.stepId(), payload));
    }

    /**
     * @param clientName  denormalised in rather than looked up again — the
     *                    caller (the portal step reader) already has it
     * @param ownerUserId the step's own owner, {@code null} if unassigned
     */
    public record RaiseCommand(
            long obClientId, String clientName, long journeyId, long stepId,
            String stepName, String productName, Long ownerUserId,
            long raisedByContactId, String comment, Instant now) {
    }

    /**
     * The portal's own answer to a raise — id, comment and when, nothing
     * else. {@code isNew} is false when an already-open escalation on this
     * step was returned instead of a new one (see {@link #raise}'s javadoc).
     */
    public record RaiseResult(long id, String comment, Instant raisedAt, boolean isNew) {
    }
}
