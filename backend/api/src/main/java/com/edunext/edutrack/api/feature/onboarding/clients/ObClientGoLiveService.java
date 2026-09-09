package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import com.edunext.edutrack.domain.onboarding.ObClientStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-118 · plan §5.9's go-live flip — the only writer of
 * {@code ob_clients.overall_status = 'LIVE'}, per {@link ObClientStatus}'s own
 * rule that the value is earned, never set.
 *
 * <h2>Per journey, not per client</h2>
 *
 * <p>{@code ObSignoff} is requested under
 * {@code /onboarding/journeys/{journeyId}/signoffs}, and A-107's own account
 * of {@code kind = GO_LIVE} calls it "the journey-wide sign-off at Live-Green" —
 * one per journey, not one per client. A client with three purchased products
 * has three journeys and three {@code GO_LIVE} sign-offs, and the flip this
 * class performs fires on whichever acceptance turns out to be the client's
 * last one, not on a single client-wide sign-off that does not exist. This is
 * the reading B-118's own task brief asked to be stated explicitly, because
 * B-119 (CSAT) and everything downstream of {@code clientWentLive} depends on
 * it being right.
 *
 * <h2>Why this class reads {@code ob_journeys} with a plain query rather than
 * {@code ObJourneyRepository}</h2>
 *
 * <p>{@code ScopeGuardRulesTest} fails the build on any class outside
 * {@code api.security.scope} that depends on {@code ObJourneyRepository} — a
 * caller-scoped read is exactly what "every journey of this client" is not.
 * {@code ObPrerequisiteGateService} (C-118) made the identical call for the
 * identical reason one module over: a plain guarded query, not a second
 * {@code @UnscopedAccess} class for a read that already has a proven caller —
 * the caller into {@link #flipIfEarned} has already recorded a real
 * acceptance against {@code obClientId} in the same transaction, so there is
 * no caller identity left to scope by.
 *
 * <h2>The lock, and the order it is taken in</h2>
 *
 * <p>{@link ObClientRepository#findByIdForUpdate} is taken <b>before</b> the
 * completeness check, not after. Two journeys of the same client can each be
 * signed off within moments of each other, on two different requests, on two
 * different connections — taking the lock first means the second transaction
 * blocks until the first has committed (or not) the flip, and when it resumes
 * it re-reads a client that may already say {@code LIVE}. That is what makes
 * the idempotency guard below a guard against a real race, not merely
 * defensive style.
 */
@Service
public class ObClientGoLiveService {

    private final ObClientRepository clients;
    private final JdbcClient jdbc;
    private final ObOutboxEnqueuer outbox;

    ObClientGoLiveService(ObClientRepository clients, JdbcClient jdbc, ObOutboxEnqueuer outbox) {
        this.clients = clients;
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /**
     * Flips {@code obClientId} to {@code LIVE} if, and only if, every one of
     * its other live journeys already carries a {@code SIGNED GO_LIVE}
     * sign-off. Called from {@code ObSignoffAcceptService.accept} right after
     * the {@code GO_LIVE} row for {@code justSignedJourneyId} has itself been
     * recorded {@code SIGNED} and saved — deliberately not re-read from the
     * database here, so this method never races the flush of that very save;
     * it is taken as already true and excluded from the query below by id
     * instead.
     *
     * @param at the timestamp to stamp as {@code live_at} — the accepting
     *           sign-off's own {@code signed_at}, so the flip and the
     *           acceptance that earned it carry the same instant
     * @return true exactly when this call is the one that performed the flip
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean flipIfEarned(long obClientId, long justSignedJourneyId, Instant at) {
        ObClient client = clients.findByIdForUpdate(obClientId)
                .orElseThrow(() -> new IllegalStateException(
                        "client " + obClientId + " went live and cannot be loaded to record it"));

        // Idempotency: "at most once in a client's life" is the contract's own
        // claim (ObClientStatus), and this is what makes a defensive second
        // call — or the second half of the race the lock above serialises —
        // a silent no-op rather than a second stamp on live_at.
        if (client.getOverallStatus() == ObClientStatus.LIVE) {
            return false;
        }
        if (!everyOtherLiveJourneySignedGoLive(obClientId, justSignedJourneyId)) {
            return false;
        }

        client.goLive(at);
        clients.save(client);

        notifyGoLive(obClientId);
        return true;
    }

    /**
     * "Every journey the client has, complete with its own sign-off" —
     * checked as its negation, because "no counterexample" is the question a
     * {@code NOT EXISTS} answers directly and a {@code COUNT}-and-compare
     * answers only after two round trips. {@code justSignedJourneyId} is
     * excluded rather than relied upon: the caller's own save of that row may
     * not yet be visible to a second statement on this connection, and the
     * caller already knows it is {@code SIGNED} without asking again.
     *
     * <p>Archived journeys are excluded on {@code ObClientReadRepository
     * .journeysOf}'s own precedent — an archived journey is a template
     * version that was replaced, not a live commitment still waiting on a
     * sign-off.
     */
    private boolean everyOtherLiveJourneySignedGoLive(long obClientId, long justSignedJourneyId) {
        Boolean allSigned = jdbc.sql("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM ob_journeys j
                     WHERE j.ob_client_id = :clientId
                       AND j.archived_at IS NULL
                       AND j.id <> :excludeJourneyId
                       AND NOT EXISTS (
                            SELECT 1 FROM ob_signoffs s
                             WHERE s.journey_id = j.id
                               AND s.kind = 'GO_LIVE'
                               AND s.status = 'SIGNED'
                       )
                )
                """)
                .param("clientId", obClientId)
                .param("excludeJourneyId", justSignedJourneyId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(allSigned);
    }

    /**
     * {@code GO_LIVE} (B-111, already catalogued): "goes to the SPOC and to
     * the staff who got them there." The SPOC is the client's primary
     * contact; "the staff who got them there" is read as the owners of the
     * steps that were actually run — the delivery team — on
     * {@code ObPrerequisiteGateService#notifyGateOpened}'s own precedent for
     * the same phrase one module over ({@code GATE_OPENED} notifies step
     * owners, not the salesperson who is not who ran the journey).
     */
    private void notifyGoLive(long obClientId) {
        String clientName = jdbc.sql("SELECT name FROM ob_clients WHERE id = :id")
                .param("id", obClientId).query(String.class).optional().orElse("this client");
        List<String> productNames = jdbc.sql("""
                SELECT p.name FROM ob_journeys j
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.ob_client_id = :id AND j.archived_at IS NULL
                 ORDER BY p.name
                """).param("id", obClientId).query(String.class).list();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", clientName);
        payload.put("product_names", String.join(", ", productNames));

        Long spocContactId = jdbc.sql("""
                SELECT id FROM ob_client_contacts WHERE ob_client_id = :id AND is_primary_key = 1
                """).param("id", obClientId).query(Long.class).optional().orElse(null);
        if (spocContactId != null) {
            outbox.enqueue(ObNotification.aboutClient(
                    ObNotificationEvent.GO_LIVE.key(), ObChannel.EMAIL,
                    new ObRecipient.Client(spocContactId), obClientId, payload));
        }

        Set<Long> ownerIds = new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT s.owner_user_id FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = :id AND j.archived_at IS NULL AND s.owner_user_id IS NOT NULL
                """).param("id", obClientId).query(Long.class).list());
        for (long ownerId : ownerIds) {
            ObRecipient.Staff staff = new ObRecipient.Staff(ownerId);
            for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
                outbox.enqueue(ObNotification.aboutClient(
                        ObNotificationEvent.GO_LIVE.key(), channel, staff, obClientId, payload));
            }
        }
    }
}
