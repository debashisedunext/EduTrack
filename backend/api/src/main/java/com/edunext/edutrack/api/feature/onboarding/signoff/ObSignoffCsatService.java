package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.OptionalLong;

/**
 * B-119 · the go-live survey, answered.
 *
 * <h2>Rides the accept session; does not mint its own</h2>
 *
 * <p>The contract is explicit about why: "the moment to ask is the moment
 * the client accepts the go-live, and a second link emailed afterwards is a
 * second thing to ignore." So this reads the same session
 * {@code verifyObSignoffOtp} minted and {@code acceptObSignoff} already
 * consumed for its own purpose, and it works only because of one change made
 * alongside this class: {@link ObSignoffAcceptService#invalidateSessionAfterCommit}
 * no longer invalidates a {@code GO_LIVE} session at the moment of accept.
 * See that method's own javadoc for why leaving it alive is still safe — the
 * short version is that {@code status != PENDING} already blocks a second
 * {@code accept} on the same token, so nothing is gained by killing the
 * session too, and something is lost: this route, which needs it.
 *
 * <p>The session's life still has a hard ceiling — {@code ObSignoffSessions}'
 * fifteen-minute TTL, unchanged. A client who accepts and never answers the
 * survey leaves the key to expire on its own, exactly as "optional by
 * construction... a client who closes the tab has still gone live" promises.
 * This class invalidates it early, on success, because once the one
 * question this session exists to ask afterwards has been answered there is
 * nothing left for the token to authorise — {@code ObSignoffAcceptService}'s
 * and {@code ObSignoffObjectService}'s own reasoning for spending a session
 * the moment its purpose is served, applied a second time.
 *
 * <h2>"Already surveyed" is a client-level fact, not a row-level one</h2>
 *
 * <p>{@code ObSignoffKind}'s own note and B-118's javadoc agree: a client
 * with several purchased products earns several {@code GO_LIVE} sign-offs,
 * one per journey. The contract's guard is "one answer per client" — not one
 * per journey — so {@link ObSignoffRepository#existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull}
 * is asked across every {@code GO_LIVE} row for {@link ObSignoff#getObClientId()},
 * not only the row this session addresses. A client who already answered
 * through one journey's session gets the same {@code 422} from a different,
 * never-before-used journey's session.
 */
@Service
public class ObSignoffCsatService {

    private final ObSignoffRepository signoffs;
    private final ObSignoffSessions sessions;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing</b>, on {@code ObSignoffAcceptService}'s
     * own precedent: two constructors and no {@code Clock} bean in the
     * context means Spring looks for a no-arg constructor that does not
     * exist, and the whole application context fails rather than this one
     * bean.
     */
    @Autowired
    ObSignoffCsatService(ObSignoffRepository signoffs, ObSignoffSessions sessions) {
        this(signoffs, sessions, Clock.systemUTC());
    }

    /** Test seam, on {@code ObSignoffAcceptService}'s own precedent. */
    ObSignoffCsatService(ObSignoffRepository signoffs, ObSignoffSessions sessions, Clock clock) {
        this.signoffs = signoffs;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Records the survey answer.
     *
     * @throws InvalidSignoffTokenException for an unknown, expired or
     *                                      already-invalidated session — the
     *                                      surface's one generic 401
     * @throws CsatNotOfferedException      the session is not a {@code SIGNED}
     *                                      {@code GO_LIVE} acceptance — a
     *                                      {@code STEP} session, or a
     *                                      {@code GO_LIVE} one that has not
     *                                      actually been accepted yet
     * @throws CsatAlreadySubmittedException this client has already answered,
     *                                       through this journey's session or
     *                                       another one of theirs
     */
    @Transactional
    public void submit(String sessionToken, int score, String comment) {
        ObSignoff signoff = requireSession(sessionToken);

        if (signoff.getKind() != ObSignoffKind.GO_LIVE || signoff.getStatus() != ObSignoffStatus.SIGNED) {
            throw new CsatNotOfferedException();
        }

        if (signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(
                signoff.getObClientId(), ObSignoffKind.GO_LIVE)) {
            throw new CsatAlreadySubmittedException();
        }

        signoff.setCsatScore(score);
        signoff.setCsatComment(blankToNull(comment));
        signoff.setCsatSubmittedAt(clock.instant());
        signoffs.save(signoff);

        invalidateSessionAfterCommit(sessionToken);
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
     * {@code ObSignoffAcceptService#invalidateSessionAfterCommit}'s own
     * reasoning, copied rather than shared — that method's own javadoc says
     * why a third class for one six-line helper is not worth it, and this is
     * the third.
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
