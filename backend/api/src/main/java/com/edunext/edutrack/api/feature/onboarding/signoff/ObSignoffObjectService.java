package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyStepLifecycleService;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * B-117 · the client objects, and the service reverts.
 *
 * <h2>No un-object, and the row says so once it is written</h2>
 *
 * <p>The contract is explicit: "There is no un-object. A client who changes
 * their mind is a new sign-off request, which is a staff action with its own
 * record." So this method, like {@link ObSignoffAcceptService#accept}, only
 * ever moves a {@code PENDING} row forward — here to {@code OBJECTED} — and
 * refuses (the surface's one generic 401) anything that is not still
 * {@code PENDING}, on that method's own reasoning for why the check is not
 * redundant with the session lookup: the row can be cancelled or decided by
 * a second tab inside the session's fifteen minutes.
 *
 * <h2>The revert is not re-implemented here</h2>
 *
 * <p>{@link ObJourneyStepLifecycleService#revertOnClientObjection} is the one
 * place a step comes back from a client's sign-off, on {@code accept}'s own
 * precedent for {@code completeOnClientAcceptance}: the gate — here, which
 * states are revertible — lives in one class, not copied into this package.
 *
 * <h2>The owner is notified in the same transaction the objection is recorded in</h2>
 *
 * <p>{@link ObOutboxEnqueuer#enqueue} joins the caller's transaction by
 * design, so a queued {@code SIGNOFF_OBJECTED} mail commits if and only if
 * the objection does — the outbox's own stated reason for the propagation it
 * uses.
 */
@Service
public class ObSignoffObjectService {

    private final ObSignoffRepository signoffs;
    private final ObSignoffSessions sessions;
    private final ObSignoffContactReader contacts;
    private final ObSignoffPageReader pages;
    private final ObJourneyStepLifecycleService stepLifecycle;
    private final ObOutboxEnqueuer outbox;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing</b>, on {@code
     * ObSignoffAcceptService}'s own precedent one class over: two
     * constructors and no {@code Clock} bean in the context means Spring
     * looks for a no-arg constructor that does not exist, and the whole
     * application context fails to start rather than this one bean.
     */
    @Autowired
    ObSignoffObjectService(ObSignoffRepository signoffs,
                           ObSignoffSessions sessions,
                           ObSignoffContactReader contacts,
                           ObSignoffPageReader pages,
                           ObJourneyStepLifecycleService stepLifecycle,
                           ObOutboxEnqueuer outbox) {
        this(signoffs, sessions, contacts, pages, stepLifecycle, outbox, Clock.systemUTC());
    }

    /** Test seam, on {@code ObSignoffAcceptService}'s own precedent. */
    ObSignoffObjectService(ObSignoffRepository signoffs,
                           ObSignoffSessions sessions,
                           ObSignoffContactReader contacts,
                           ObSignoffPageReader pages,
                           ObJourneyStepLifecycleService stepLifecycle,
                           ObOutboxEnqueuer outbox,
                           Clock clock) {
        this.signoffs = signoffs;
        this.sessions = sessions;
        this.contacts = contacts;
        this.pages = pages;
        this.stepLifecycle = stepLifecycle;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Records the objection and reverts the step, if there is one and it is
     * in a state this can revert.
     *
     * @throws InvalidSignoffTokenException for an unknown, expired or already
     *                                      spent session, and for a sign-off
     *                                      that is no longer {@code PENDING}
     */
    @Transactional
    public PublicSignoffObjectDtos.SignoffDetail object(String sessionToken, String note) {
        ObSignoff signoff = requireSession(sessionToken);

        // Not redundant with the session lookup — ObSignoffAcceptService#accept
        // makes the same point about the same fifteen-minute window: the row
        // can be cancelled, or accepted in another tab, inside it.
        if (signoff.getStatus() != ObSignoffStatus.PENDING) {
            throw new InvalidSignoffTokenException();
        }

        signoff.setStatus(ObSignoffStatus.OBJECTED);
        signoff.setObjectedAt(clock.instant());
        signoff.setObjectionNote(note.trim());
        signoffs.save(signoff);

        ObJourneyStepLifecycleService.ObjectionResult reverted = revertStepIfAny(signoff);
        notifyOwner(signoff, reverted);

        invalidateSessionAfterCommit(sessionToken);

        return detailOf(signoff);
    }

    /**
     * A {@code GO_LIVE} sign-off has no step to revert — {@code
     * ObSignoffAcceptService}'s identical {@code completeStepIfAny} check,
     * restated here.
     */
    private ObJourneyStepLifecycleService.ObjectionResult revertStepIfAny(ObSignoff signoff) {
        if (signoff.getKind() != ObSignoffKind.STEP || signoff.getStepId() == null) {
            return null;
        }
        return stepLifecycle.revertOnClientObjection(signoff.getStepId(), signoff.getSentToContactId(),
                signoff.getObjectionNote());
    }

    /**
     * {@code SIGNOFF_OBJECTED} (§8, B-117) to the step's owner — "goes to the
     * step owner" is the catalogue's own line for this event.
     *
     * <p>Nothing is sent for a {@code GO_LIVE} sign-off (no step, no owner) or
     * for a step with no owner assigned — {@code ObPrerequisiteGateService}'s
     * own precedent one module over for the identical shape of gap: there is
     * nobody to notify, not a failure to report.
     */
    private void notifyOwner(ObSignoff signoff, ObJourneyStepLifecycleService.ObjectionResult reverted) {
        if (reverted == null || reverted.ownerUserId() == null) {
            return;
        }

        ObSignoffPageReader.Page page = pages.read(signoff);
        PublicSignoffAcceptDtos.Contact objector = contacts.find(signoff.getSentToContactId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", page.clientName());
        payload.put("step_title", page.stepTitle());
        payload.put("product_name", page.productName());
        payload.put("objection_reason", signoff.getObjectionNote());
        payload.put("objected_by", objector == null ? null : objector.name());

        ObRecipient.Staff owner = new ObRecipient.Staff(reverted.ownerUserId());
        for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
            outbox.enqueue(ObNotification.aboutStep(ObNotificationEvent.SIGNOFF_OBJECTED.key(), channel, owner,
                    signoff.getObClientId(), signoff.getJourneyId(), signoff.getStepId(), payload));
        }
    }

    private ObSignoff requireSession(String sessionToken) {
        OptionalLong signoffId = sessions.resolve(sessionToken);
        if (signoffId.isEmpty()) {
            throw new InvalidSignoffTokenException();
        }
        return signoffs.findById(signoffId.getAsLong())
                .orElseThrow(InvalidSignoffTokenException::new);
    }

    /**
     * Invalidating the Redis session only once the row is committed — {@code
     * ObSignoffAcceptService#invalidateSessionAfterCommit}'s own reasoning,
     * copied rather than shared because neither class may depend on the
     * other's private method and a third class for one six-line helper would
     * be its own kind of indirection.
     */
    private void invalidateSessionAfterCommit(String sessionToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessions.invalidate(sessionToken);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.invalidate(sessionToken);
            }
        });
    }

    private PublicSignoffObjectDtos.SignoffDetail detailOf(ObSignoff signoff) {
        PublicSignoffAcceptDtos.Contact contact = contacts.find(signoff.getSentToContactId());
        return new PublicSignoffObjectDtos.SignoffDetail(
                signoff.getId(),
                signoff.getObClientId(),
                signoff.getJourneyId(),
                signoff.getStepId(),
                signoff.getKind(),
                signoff.getStatus(),
                null,                       // requestedBy — see SignoffDetail's javadoc
                signoff.getRequestedAt(),
                contact,
                signoff.getTokenExpiresAt(),
                signoff.getSignedAt(),
                signoff.getObjectedAt(),
                signoff.getPdfStorageKey() != null);
    }
}
