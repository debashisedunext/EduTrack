package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * A-130 · redeeming the one-time link B-126 mails.
 *
 * <h2>The link is the only way in</h2>
 *
 * <p>B-126 creates an account whose password is a hash of 32 random bytes that
 * were discarded — see {@code ClientCredentialTokens.unguessablePlaceholderPassword}.
 * Nobody, including us, knows it. So this is not "change your password": it is
 * the only path from a created account to a usable one, and until it runs the
 * account cannot be signed in to at all.
 *
 * <h2>Two calls, because the form has to render before it can be submitted</h2>
 *
 * <p>{@link #describe} answers whether a link is still good and who it is for,
 * so the page can greet the contact and say "this link expired" without asking
 * them to choose a password first and refusing afterwards. {@link #redeem}
 * spends it.
 *
 * <p>Both take the plaintext token and hash it here. The plaintext is never
 * stored, never logged and never returned — {@code client_credential_tokens}
 * holds only the SHA-256, which is the whole reason the mail can be the only
 * place the token ever existed.
 *
 * <p>SHA-256 rather than Argon2id, matching A-027's reset tokens: this is a
 * 256-bit random value from a CSPRNG, not a human-chosen secret, so there is no
 * dictionary to slow down and a per-request KDF on a public route would be a
 * denial-of-service budget rather than a defence.
 */
@Service
class PortalCredentialService {

    private static final Logger log = LoggerFactory.getLogger(PortalCredentialService.class);

    private final ClientCredentialTokenRepository tokens;
    private final ClientAccountRepository accounts;
    private final PortalPasswordRules passwordRules;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    PortalCredentialService(ClientCredentialTokenRepository tokens,
                            ClientAccountRepository accounts,
                            PortalPasswordRules passwordRules,
                            PasswordEncoder passwordEncoder,
                            Clock clock) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.passwordRules = passwordRules;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Who this link is for, if it is still good.
     *
     * @throws PortalAuthExceptions.InvalidCredentialLink for expired, spent and
     *         never-issued alike
     */
    @Transactional(readOnly = true)
    PortalAuthDtos.CredentialLink describe(String plaintextToken) {
        Resolved resolved = resolve(plaintextToken);
        return new PortalAuthDtos.CredentialLink(
                resolved.account().username(),
                resolved.account().displayName(),
                resolved.token().expiresAt());
    }

    /**
     * Sets the password the link was issued to let somebody choose, and spends
     * the link.
     *
     * <p>Order matters and is the opposite of the obvious one: the link is
     * spent <b>first</b>, and only a caller who wins that update sets a
     * password. Setting the password first and marking the token afterwards
     * leaves a window in which two concurrent redemptions both succeed and the
     * second one silently overwrites the first — so whichever request lost the
     * race would hand its user a password that no longer works, with no error
     * to explain it.
     *
     * <p>Both statements are in one transaction, so a failure between them
     * cannot leave a spent link over an unchanged password — which would brick
     * the account with no way back except a fresh link.
     */
    @Transactional
    void redeem(String plaintextToken, String newPassword) {
        // Validated before the token is spent: refusing a weak password must
        // leave the link usable, or a typo costs the client a support call.
        passwordRules.enforce(newPassword);

        Resolved resolved = resolve(plaintextToken);

        if (!tokens.markUsed(resolved.token().id(), clock.instant())) {
            // Lost the race. The row was live when it was read and is not now,
            // which is precisely the double-submit this predicate exists for.
            throw new PortalAuthExceptions.InvalidCredentialLink();
        }

        accounts.setPassword(resolved.account().id(), passwordEncoder.encode(newPassword));
        log.info("portal: credential link redeemed for account {}", resolved.account().id());
    }

    /**
     * The shared lookup.
     *
     * <p>Expired, spent and unknown are told apart here and folded into one
     * answer on the way out. The distinction is kept this long so the log line
     * can be honest about which of the three happened, which is the difference
     * between diagnosing "our mail took nine days to be read" and guessing.
     */
    private Resolved resolve(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new PortalAuthExceptions.InvalidCredentialLink();
        }

        CredentialTokenRow token = tokens.findByHash(Digests.sha256Hex(plaintextToken))
                .orElseThrow(PortalAuthExceptions.InvalidCredentialLink::new);

        Instant now = clock.instant();
        if (!token.redeemableAt(now)) {
            log.info("portal: credential link refused for account {} — {}",
                    token.clientAccountId(), token.spent() ? "already used" : "expired");
            throw new PortalAuthExceptions.InvalidCredentialLink();
        }

        ClientAccountRow account = accounts.findById(token.clientAccountId())
                .orElseThrow(PortalAuthExceptions.InvalidCredentialLink::new);

        /*
          A deactivated account is refused here rather than at login. Somebody
          whose access was withdrawn between the mail being sent and being read
          should be told the link is no good, not allowed to choose a password
          and then refused with the same words at the next screen.
        */
        if (!account.active()) {
            log.info("portal: credential link refused for account {} — account is inactive", account.id());
            throw new PortalAuthExceptions.InvalidCredentialLink();
        }

        return new Resolved(token, account);
    }

    private record Resolved(CredentialTokenRow token, ClientAccountRow account) {
    }
}
