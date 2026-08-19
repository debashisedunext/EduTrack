package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.security.ClientAddress;
import com.edunext.edutrack.domain.audit.AuditEntry;
import com.edunext.edutrack.domain.audit.AuditTrail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * A-071 · the login half of "every login, permission change, master change and
 * ticket action" — blueprint §10.1's "{@code audit: LOGIN_SUCCESS (ip,
 * user-agent)}", and the record A-020 deferred here by name.
 *
 * <h2>Why login is the one path that does not go through the interceptor</h2>
 *
 * <p>{@link AuditInterceptor} derives its term from the route, which for
 * {@code POST /auth/login} would be {@code LOGIN_CREATED} — the same row for a
 * successful sign-in, a wrong password, a locked account, a wrong second factor
 * and a throttled source, because from the route's point of view they are one
 * endpoint. Those five are the most-read rows in the table and they are the
 * reason S-16 exists, so login records itself and
 * {@code AuditInterceptor.SELF_RECORDED} keeps the derived row from being
 * written as well.
 *
 * <p>It lives here rather than in {@code feature/auth} so the vocabulary stays
 * in one package: {@code AuditActions} is package-private, and a copy of these
 * six strings in the auth package would be the second place a term is spelled
 * — which is how a filter for {@code LOGIN_FAILED} comes to miss half the rows
 * after somebody writes {@code LOGIN_FAILURE} elsewhere. The dependency is one
 * way, {@code auth} on {@code audit}, and both are Stream A's.
 *
 * <h2>A failed login records what was typed, and never resolves it</h2>
 *
 * <p>{@link #failed} takes the submitted identifier and writes it to
 * {@code new_value} with a null actor. It does <em>not</em> look the identifier
 * up to fill {@code actor_id}, and that restraint is the whole of A-020's
 * design surviving into this table: the endpoint answers identically whether or
 * not the account exists, and an audit row that resolved the name would record
 * the answer the endpoint refuses to give. An Admin reading the log sees
 * "somebody tried to sign in as {@code jsmith}", which is what they need, and
 * learns nothing about whether {@code jsmith} is a user that the
 * {@code users} table would not have told them anyway.
 *
 * <p>The identifier is stored raw. It is a username or an email, both of which
 * are already in the database, and never the password — no method here takes
 * one.
 */
@Component
public class LoginAudit {

    /** {@code users}, because the subject of a login event is the account. */
    private static final String MODULE = "users";

    private final AuditTrail audit;

    LoginAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /** The session was issued. One of only two of these with a known actor. */
    public void succeeded(long userId, HttpServletRequest request) {
        audit.record(origin(AuditEntry.of(userId, AuditActions.LOGIN_SUCCESS, MODULE, userId), request));
    }

    /**
     * A-020's single failure mode: unknown user, wrong password and deactivated
     * account all arrive here, and the row does not distinguish them either —
     * because the controller cannot, which is the point.
     */
    public void failed(String identifier, HttpServletRequest request) {
        audit.record(attempt(AuditActions.LOGIN_FAILED, identifier, request));
    }

    /**
     * A-076 refused the attempt before the KDF ran.
     *
     * <p>Worth its own term rather than folding into {@link #failed}: these two
     * mean opposite things about the caller. A run of {@code LOGIN_FAILED} is
     * somebody guessing; a run of {@code LOGIN_THROTTLED} is the limiter working,
     * and counting them together would make a defended system look like a
     * breached one.
     */
    public void throttled(String identifier, HttpServletRequest request) {
        audit.record(attempt(AuditActions.LOGIN_THROTTLED, identifier, request));
    }

    /**
     * A-021's lockout was reported.
     *
     * <p>Reachable only <em>after</em> the correct password, so unlike the other
     * two failures this one means somebody holds the credentials for a locked
     * account — either the owner returning after five typos, or the exact case
     * the lockout exists for. The actor is still null: the id is not in the
     * controller's hands at this point, and querying for it to fill the column
     * would put a "this name is a real account" answer into the table for a
     * request that has not been granted a session.
     */
    public void lockedOut(String identifier, HttpServletRequest request) {
        audit.record(attempt(AuditActions.LOGIN_LOCKED_OUT, identifier, request));
    }

    /**
     * A-029's second factor was wrong, after a correct password.
     *
     * <p>The gap this closes is narrow and unpleasant: without it, somebody who
     * has obtained a password and is grinding six digits leaves <b>no trace at
     * all</b> — the password check passed, so nothing counts a failure, and the
     * route records itself, so the interceptor writes nothing either.
     *
     * <p><b>A pending challenge is deliberately not recorded.</b> Being asked
     * for a code is not an outcome; it is the middle of one, and the attempt
     * ends in a row either way. Recording it would double every 2FA user's
     * sign-in and make the log read as though each of them tried twice.
     */
    public void secondFactorFailed(String identifier, HttpServletRequest request) {
        audit.record(attempt(AuditActions.LOGIN_2FA_FAILED, identifier, request));
    }

    /** A-024's logout. Recorded with the actor, who is by definition known here. */
    public void loggedOut(long userId, HttpServletRequest request) {
        audit.record(origin(AuditEntry.of(userId, AuditActions.LOGOUT, MODULE, userId), request));
    }

    /** An unattributed attempt: no actor, the identifier as the new value. */
    private static AuditEntry attempt(String action, String identifier, HttpServletRequest request) {
        return origin(new AuditEntry(null, action, MODULE, null, null, null, identifier, null, null),
                request);
    }

    private static AuditEntry origin(AuditEntry entry, HttpServletRequest request) {
        return entry.from(ClientAddress.of(request),
                request == null ? null : request.getHeader(HttpHeaders.USER_AGENT));
    }
}
