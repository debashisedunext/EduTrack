package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientPortalLoginIssuer;
import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import org.springframework.stereotype.Component;

/**
 * Supplies {@link ObClientPortalLoginIssuer} from the package that owns portal
 * accounts.
 *
 * <h2>An adapter, and nothing else</h2>
 *
 * <p>There is no logic here on purpose. {@link ClientAccountAdminService#create}
 * already refuses a client with no primary SPOC, already refuses a second
 * account, already mints the credential and queues the mail, and already
 * applies the development password where one is configured. A second entry
 * point that did any of that itself would be a second place for those rules to
 * drift out of agreement — and the one the add dialog used would be the one
 * nobody looked at.
 *
 * <p>What this class does do is narrow the return: the panel's
 * {@code Account} carries {@code lastLoginAt}, {@code lockedUntil} and the rest
 * of a read model that the add dialog has no use for, and it is package-private
 * besides. Two fields cross the package boundary.
 */
@Component
class ObClientPortalLoginAdapter implements ObClientPortalLoginIssuer {

    private final ClientAccountAdminService accounts;

    ObClientPortalLoginAdapter(ClientAccountAdminService accounts) {
        this.accounts = accounts;
    }

    /**
     * <p>Every exception {@code create} throws travels out untranslated —
     * {@code NoPrimaryContactException}, {@code ClientAccountAlreadyExistsException}
     * and {@code WeakPortalPassword} all already have handlers that render them
     * as the problem types the contract declares. Wrapping them here would mean
     * the same failure answered differently depending on which screen asked for
     * the login, which is precisely the inconsistency the shared path exists to
     * avoid.
     */
    @Override
    public IssuedLogin issueFor(ObClientScope scope, long obClientId, Long actorUserId) {
        ClientAccountAdminDtos.Account account = accounts.create(scope, obClientId, actorUserId);
        return new IssuedLogin(account.username(), account.devPassword());
    }
}
