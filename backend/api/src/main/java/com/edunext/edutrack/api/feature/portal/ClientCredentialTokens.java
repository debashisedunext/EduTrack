package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * B-126 · mints the one-time link a credential mail carries.
 *
 * <h2>A link rather than a password, and B-111 already decided why</h2>
 *
 * <p>{@code ObNotificationEvent.CLIENT_LOGIN_CREATED} "declares no password
 * variable — the payload is JSON on a row that outlives the send, so a
 * temporary password in it is a live credential in the database indefinitely".
 * {@code ob_notification_outbox} keeps its payload after sending. So what goes
 * into the mail is a URL, what goes into this table is a SHA-256 of the token
 * inside it, and the plaintext exists only between here and the client's inbox.
 *
 * <h2>The account is created with a password nobody knows</h2>
 *
 * <p>{@code client_accounts.password_hash} is {@code NOT NULL}, so an account
 * has to be created with something. It is created with a hash of 32 random
 * bytes that are never returned, never logged and immediately discarded — an
 * unguessable password with no plaintext anywhere. The link is the only way in,
 * and {@code must_change_password} defaults to 1 so redeeming it is the only
 * thing that can happen next.
 *
 * <p>The alternative — a nullable {@code password_hash} meaning "not set yet" —
 * would put a row in the table that {@code ClientAccountRepository}'s login path
 * has to defend against, on a query whose own javadoc explains that it must not
 * short-circuit before hashing. A null there is a branch before the hash.
 *
 * <h2>Seven days</h2>
 *
 * <p>Longer than A-027's reset window, and the reason is who is reading it: a
 * staff member asks for their own reset and acts on it within minutes, whereas
 * a credential mail lands with a client contact who was not expecting it, may
 * be away, and has to be chased. A window short enough to expire before the
 * first chase produces a second issue, and the second link is no safer than the
 * first — it is the same mailbox.
 */
@Component
class ClientCredentialTokens {

    /** {@code INITIAL} — the credential mail for a newly created login. */
    static final String PURPOSE_INITIAL = "INITIAL";

    /** {@code RESET} — a staff-initiated reset from the OB-05 panel. */
    static final String PURPOSE_RESET = "RESET";

    static final Duration TTL = Duration.ofDays(7);

    /** 256 bits, the size the stored SHA-256 protects. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ClientCredentialTokenRepository tokens;
    private final Clock clock;

    ClientCredentialTokens(ClientCredentialTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Retires whatever was outstanding, mints a new token, and returns the
     * plaintext — the only time it exists outside the client's hands.
     *
     * <p>The retirement is not optional and is not a tidy-up. Issuing a reset
     * while the previous link still works means the old mail is a second way in
     * that nobody remembers exists, which is the opposite of what somebody
     * pressing "reset" is asking for.
     */
    Minted mint(long clientAccountId, String purpose, Long createdBy) {
        Instant now = clock.instant();
        tokens.retireOutstanding(clientAccountId, now);

        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);

        Instant expiresAt = now.plus(TTL);
        tokens.insert(clientAccountId, Digests.sha256Hex(token), purpose, expiresAt, createdBy);
        return new Minted(token, expiresAt);
    }

    /**
     * A password nobody knows, for the {@code NOT NULL} column.
     *
     * <p>Returned as plaintext only so the caller can hand it straight to the
     * encoder. It is never stored, never mailed and never logged; the link is
     * what the client receives.
     */
    static String unguessablePlaceholderPassword() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }

    record Minted(String token, Instant expiresAt) {
    }
}
