package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Deciding a sign-off from inside the client portal, where the caller has
 * already authenticated.
 *
 * <h2>What this replaces, and what it deliberately does not</h2>
 *
 * <p>OB-09's flow proves two separate things in two separate steps: the mailed
 * link proves possession of a mailbox, and the OTP proves who is holding it —
 * {@code PublicSignoffPage}'s own summary of A-121's reasoning, "a link on its
 * own proves possession of an email; it does not prove identity". <b>Both of
 * those steps exist because that surface has no authenticated principal.</b> A
 * portal caller does: they signed in against {@code client_accounts} with a
 * password, and A-130 hands this request a verified {@code ClientPrincipal}
 * carrying the {@code obClientId} the row is then checked against. The link and
 * the OTP are not being weakened here, they are being <em>replaced by a
 * stronger proof of the same two facts</em> — which is exactly the substitution
 * CP-04's prerequisite submission already makes, and for the same reason.
 *
 * <p>So this is an addition, not a replacement.
 * {@code PublicSignoffAcceptController} and the rest of the
 * {@code /public/onboarding/signoff/**} tree are untouched and still work: plan
 * §8 says in as many words that "the link+OTP path still works without a portal
 * login and remains the legal record", and a client without a portal account —
 * which is most of them, since the login is an explicit per-client option (plan
 * §2) — has no other way through.
 *
 * <h2>The gate is satisfied, never bypassed</h2>
 *
 * <p>Nothing here writes {@code SIGNED} or {@code OBJECTED} itself. It mints
 * the same Redis session a verified OTP mints and hands it straight to
 * {@link ObSignoffAcceptService#accept} / {@link ObSignoffObjectService#object}
 * — the same door OB-09 comes through, and the same door
 * {@code DevSignoffSimulationService} deliberately goes the long way round to
 * use. PHASE-2-BUILD-PLAN §3 #4 found the prototype enforcing different rules
 * on two completion paths and ruled that there is <em>one</em> gate; a second
 * accept written in this class would be that bug reintroduced with better
 * manners. One gate, one accept, one revert.
 *
 * <p>The minted session never leaves this class — it is spent in the next
 * statement and then invalidated. It is an internal handle to the one door, not
 * a credential the portal client is given, so it is never on the wire and never
 * in a response.
 *
 * <h2>{@code token_expires_at} does not gate this path, on purpose</h2>
 *
 * <p>A {@code PENDING} row whose emailed link has expired is refused by
 * {@code ObSignoffTokens} and answers OB-09's generic 401. It is <b>not</b>
 * refused here, and the reason is what that column actually governs: it caps
 * how long a link sitting in a mailbox stays usable if that mailbox is later
 * compromised. A portal caller is not using the link — they authenticated — so
 * applying the link's expiry to them would be refusing a signature over the age
 * of a credential that was never presented, and would leave the client waiting
 * on a staff resend through the mint-and-mail path ({@code resendObSignoff})
 * that no controller implements yet. The row's own status is the authority on
 * whether a sign-off is still open, and staff withdrawing one moves that status
 * to {@code CANCELLED}, which <em>is</em> refused below.
 *
 * <h2>Who the acceptance is attributed to</h2>
 *
 * <p>{@link ObSignoffAcceptService} copies {@code sent_to_contact_id} into
 * {@code signed_by_contact_id} and refuses to take it from a caller — its own
 * note explains that naming anybody else would record a fact that was never
 * established, and that the column's foreign key would happily accept an id
 * belonging to a different client. That holds unchanged here. The typed name is
 * what distinguishes a signature from a click, and the portal collects it
 * exactly as OB-09 does; the IP and user agent are recorded from the portal
 * request, which is stronger provenance than the public page's rather than
 * weaker.
 */
@Service
public class PortalSignoffDecisionService {

    private final ObSignoffRepository signoffs;
    private final ObSignoffSessions sessions;
    private final ObSignoffAcceptService accepts;
    private final ObSignoffObjectService objects;
    private final ObSignoffCsatService csat;
    private final ObSignoffPageReader pages;

    PortalSignoffDecisionService(ObSignoffRepository signoffs,
                                 ObSignoffSessions sessions,
                                 ObSignoffAcceptService accepts,
                                 ObSignoffObjectService objects,
                                 ObSignoffCsatService csat,
                                 ObSignoffPageReader pages) {
        this.signoffs = signoffs;
        this.sessions = sessions;
        this.accepts = accepts;
        this.objects = objects;
        this.csat = csat;
        this.pages = pages;
    }

    /**
     * What the portal renders before the client decides.
     *
     * <p>Served for <b>any</b> status this client owns, not only
     * {@code PENDING}, with {@link Review#canDecide()} carrying the difference.
     * A client who opens a sign-off that staff withdrew while the page was
     * loading should read "this is no longer open" rather than a 404 on a row
     * they were looking at a moment ago; the decision methods below do the
     * strict check, because a disabled form in one browser is not an
     * authorization check.
     */
    public Review review(long signoffId, long obClientId) {
        ObSignoff signoff = require(signoffId, obClientId);
        ObSignoffPageReader.Page page = pages.read(signoff);

        return new Review(
                signoff.getId(),
                signoff.getKind(),
                signoff.getStatus(),
                page.clientName(),
                page.productName(),
                page.stepTitle(),
                signoff.getRequestedAt(),
                signoff.getStatus() == ObSignoffStatus.PENDING,
                csatOffered(signoff),
                page.checklist().stream()
                        .map(item -> new ChecklistItem(
                                item.id(), item.sequence(), item.label(),
                                item.isMandatory(), item.isDone()))
                        .toList());
    }

    /**
     * Records the acceptance through the public flow's own accept.
     *
     * <p>Not {@code @Transactional}. {@link ObSignoffAcceptService#accept} is,
     * and it schedules its session invalidation for after <em>its</em> commit;
     * wrapping it in an outer transaction here would move that commit past the
     * invalidation below and defeat the ordering that method's javadoc is
     * explicit about.
     */
    public Decision accept(long signoffId, long obClientId, String acceptedName, String note,
                           HttpServletRequest http) {

        ObSignoff signoff = requirePending(signoffId, obClientId);

        ObSignoffSessions.Minted session = sessions.mint(signoff.getId());
        PublicSignoffAcceptDtos.AcceptResult result;
        try {
            result = accepts.accept(session.token(), acceptedName, note, http);
        } catch (InvalidSignoffTokenException raced) {
            // The session was minted two statements ago and cannot have
            // expired, so the only thing accept's own guard can be refusing is
            // a row that stopped being PENDING between requirePending and here
            // — a second tab, or staff withdrawing it. Translated rather than
            // allowed to surface, because that exception is the public page's
            // generic 401 and 401 on an authenticated portal route reads as
            // "your login has gone", which would be a lie the client acts on by
            // signing in again.
            throw notPendingNow(signoffId, raced);
        } finally {
            // A GO_LIVE acceptance deliberately leaves its session alive for
            // the public page's CSAT question (ObSignoffAcceptService's own
            // note). The portal does not ride that session — submitCsat below
            // mints its own against a caller it can re-authorise — so this one
            // is spent here rather than left resolvable for its fifteen
            // minutes. In a finally because a session minted for an accept that
            // then threw is a session nobody will ever spend.
            sessions.invalidate(session.token());
        }

        return decisionOf(reread(signoffId, signoff),
                result.stepCompleted(), result.gateFailures(), result.clientWentLive());
    }

    /**
     * Records the objection through the public flow's own object.
     *
     * <p>There is no un-object here either — {@link ObSignoffObjectService}'s
     * own line, from the contract: "a client who changes their mind is a new
     * sign-off request, which is a staff action with its own record". The
     * portal offers no path back, and {@link #requirePending} is what enforces
     * that rather than the screen.
     */
    public Decision object(long signoffId, long obClientId, String note) {
        ObSignoff signoff = requirePending(signoffId, obClientId);

        ObSignoffSessions.Minted session = sessions.mint(signoff.getId());
        try {
            objects.object(session.token(), note);
        } catch (InvalidSignoffTokenException raced) {
            throw notPendingNow(signoffId, raced);
        } finally {
            sessions.invalidate(session.token());
        }

        return decisionOf(reread(signoffId, signoff), false, List.of(), false);
    }

    /**
     * B-119's go-live survey, reached from the portal instead of from the
     * accept session.
     *
     * <p>The public page rides the acceptance session because the contract
     * would otherwise have to mail a second link — "a second link emailed
     * afterwards is a second thing to ignore". The portal has no such problem:
     * the client is still signed in, so a fresh session is minted against a row
     * whose ownership has just been re-checked. Every eligibility rule still
     * belongs to {@link ObSignoffCsatService#submit} — GO_LIVE, actually
     * {@code SIGNED}, not already answered — and is not restated here.
     */
    public void submitCsat(long signoffId, long obClientId, int score, String comment) {
        ObSignoff signoff = require(signoffId, obClientId);

        ObSignoffSessions.Minted session = sessions.mint(signoff.getId());
        try {
            csat.submit(session.token(), score, comment);
        } catch (CsatNotOfferedException | CsatAlreadySubmittedException unavailable) {
            // Both are package-private to this feature, and the portal's
            // handler is one package over. Translated rather than widened:
            // making them public would let any caller anywhere catch on the
            // distinction between "not a completed go-live" and "already
            // answered", which is a distinction the survey itself does not
            // draw — CsatNotOfferedException's own javadoc collapses two cases
            // into one for exactly that reason.
            throw new ObSignoffCsatUnavailableException(unavailable.getMessage(), unavailable);
        } finally {
            sessions.invalidate(session.token());
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    /**
     * This client's sign-off, or the same 404 for one that belongs to another
     * client and for one that does not exist.
     *
     * <p>The no-existence-leak rule every portal route follows. The ownership
     * check is an equality on {@code ob_client_id} rather than a scope
     * resolver, on {@code PortalOnboardingController}'s own reasoning:
     * {@code obClientId} is read from the caller's own verified token, so there
     * is nothing to resolve and nothing a caller could widen.
     */
    private ObSignoff require(long signoffId, long obClientId) {
        ObSignoff signoff = signoffs.findById(signoffId)
                .orElseThrow(ObSignoffNotForClientException::new);
        Long owner = signoff.getObClientId();
        if (owner == null || owner.longValue() != obClientId) {
            throw new ObSignoffNotForClientException();
        }
        return signoff;
    }

    /**
     * As {@link #require}, and still open.
     *
     * <p>Separated from the not-found case rather than folded into it: a client
     * looking at their own sign-off who finds it already decided — in another
     * tab, or withdrawn by staff a moment ago — is owed "this is no longer
     * open" and not "no such thing". The public surface answers one generic 401
     * for both because <em>there</em> the two are indistinguishable to an
     * unauthenticated caller and telling them apart would build the enumeration
     * oracle {@code PublicSignoffAccess} exists to refuse to be; here the caller
     * has already proved they own the row, so there is nothing left to
     * enumerate.
     */
    private ObSignoff requirePending(long signoffId, long obClientId) {
        ObSignoff signoff = require(signoffId, obClientId);
        if (signoff.getStatus() != ObSignoffStatus.PENDING) {
            throw new ObSignoffNotPendingException(signoff.getStatus());
        }
        return signoff;
    }

    /**
     * The row as it stands after the decision committed.
     *
     * <p>The instance held here was loaded in this method's own call and the
     * accept ran in its own transaction, so it may be a detached copy carrying
     * the pre-decision status. Falling back to the stale instance rather than
     * throwing keeps a successful decision from being reported as a failure
     * over a re-read — the write is already committed by this point, and losing
     * the response would tell the client to try again on a sign-off that is no
     * longer {@code PENDING}.
     */
    private ObSignoff reread(long signoffId, ObSignoff fallback) {
        return signoffs.findById(signoffId).orElse(fallback);
    }

    /**
     * The refusal to raise when a decision lost a race to another tab.
     *
     * <p>Re-reads rather than assuming, so the problem document can name the
     * status the row actually reached. A row that has somehow gone missing
     * between the two reads reports {@code CANCELLED} — the one status that
     * describes "this is not there to decide any more" without claiming an
     * acceptance or an objection that may not have happened.
     */
    private ObSignoffNotPendingException notPendingNow(long signoffId, RuntimeException cause) {
        ObSignoff current = signoffs.findById(signoffId).orElse(null);
        ObSignoffStatus status = current == null ? ObSignoffStatus.CANCELLED : current.getStatus();
        ObSignoffNotPendingException refusal = new ObSignoffNotPendingException(status);
        refusal.initCause(cause);
        return refusal;
    }

    /** {@code ObSignoffOtpService.csatOffered}, restated for a row this caller owns. */
    private boolean csatOffered(ObSignoff signoff) {
        return signoff.getKind() == ObSignoffKind.GO_LIVE
                && !signoffs.existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull(
                        signoff.getObClientId(), ObSignoffKind.GO_LIVE);
    }

    private Decision decisionOf(ObSignoff signoff, boolean stepCompleted,
                                List<String> gateFailures, boolean clientWentLive) {
        return new Decision(
                signoff.getId(),
                signoff.getStatus(),
                signoff.getSignedAt(),
                signoff.getSignedName(),
                signoff.getAcceptanceNote(),
                signoff.getObjectedAt(),
                signoff.getObjectionNote(),
                stepCompleted,
                gateFailures,
                clientWentLive,
                signoff.getPdfStorageKey() != null,
                csatOffered(signoff));
    }

    /** One Task List row, as the portal's review screen renders it. */
    public record ChecklistItem(long id, int sequence, String label,
                                boolean isMandatory, boolean isDone) {
    }

    /**
     * What is being signed.
     *
     * <p>No {@code sentToEmail}, no contact card, no {@code requestedBy}: the
     * first two the reader already knows, and the third names a member of our
     * staff, which CP-03 withholds from the portal on principle rather than
     * because the caller is unauthenticated.
     */
    public record Review(long id,
                         ObSignoffKind kind,
                         ObSignoffStatus status,
                         String clientName,
                         String productName,
                         String stepTitle,
                         Instant requestedAt,
                         boolean canDecide,
                         boolean csatOffered,
                         List<ChecklistItem> checklist) {
    }

    /**
     * How it was decided, and what our own side still owes.
     *
     * <p>{@code stepCompleted} false is a normal outcome, not an error —
     * {@code PublicSignoffAcceptDtos.AcceptResult}'s own contract, kept
     * deliberately. The acceptance is recorded and the row is {@code SIGNED}
     * either way; {@code gateFailures} names what we have not finished.
     */
    public record Decision(long id,
                           ObSignoffStatus status,
                           Instant signedAt,
                           String signedName,
                           String acceptanceNote,
                           Instant objectedAt,
                           String objectionNote,
                           boolean stepCompleted,
                           List<String> gateFailures,
                           boolean clientWentLive,
                           boolean hasCertificate,
                           boolean csatOffered) {
    }
}
