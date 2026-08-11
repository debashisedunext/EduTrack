package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A-029 · authenticates the caller of the {@code /me/2fa} endpoints and
 * delegates the enrolment itself to {@link TotpService}.
 *
 * <p>Split out for the reason {@link PasswordChangeService} is: the controller
 * does no security thinking, and A-032's filter chain does not exist yet, so
 * each authenticated route still verifies its own bearer through the shared
 * {@link AccessTokenVerifier}. When the chain lands it replaces this class's
 * first two lines and nothing else.
 *
 * <h2>Why disabling costs a password and enrolling does not</h2>
 *
 * <p>The asymmetry is deliberate and is the security of the whole feature.
 *
 * <p><b>Enrolling adds protection</b>, so an access token is enough — the worst
 * an attacker achieves by enabling 2FA on an account they have already
 * compromised is locking themselves in alongside the owner, which helps nobody
 * and is quickly noticed.
 *
 * <p><b>Disabling removes it</b>, and is the first thing somebody holding a
 * stolen fifteen-minute token would do. Requiring the password means that
 * caller has to hold the first factor as well — which, if they had, they would
 * not have needed to steal a token. {@link TotpService#beginEnrolment} refuses
 * to re-enrol an already-enabled account for the same reason, so there is no
 * route that swaps the second factor without the password.
 */
@Service
class TwoFactorEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorEnrolmentService.class);

    private final AccessTokenVerifier accessTokens;
    private final AuthUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;

    TwoFactorEnrolmentService(AccessTokenVerifier accessTokens,
                              AuthUserRepository users,
                              PasswordEncoder passwordEncoder,
                              TotpService totp) {
        this.accessTokens = accessTokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.totp = totp;
    }

    TotpService.Enrolment begin(String authorizationHeader) {
        return totp.beginEnrolment(callerIdOf(authorizationHeader));
    }

    List<String> confirm(String authorizationHeader, String code) {
        return totp.confirmEnrolment(callerIdOf(authorizationHeader), code);
    }

    /**
     * @throws InvalidCurrentPasswordException if the password does not match —
     *         reusing A-026's exception because it is the same failure with the
     *         same message, and a second type meaning "your password is wrong"
     *         would be one more branch for S-04 to handle
     */
    @Transactional
    void disable(String authorizationHeader, String password) {
        long userId = callerIdOf(authorizationHeader);

        AuthUserRow user = users.findById(userId).orElse(null);
        if (user == null || !user.active()) {
            throw new InvalidAccessTokenException();
        }

        if (!passwordEncoder.matches(password, user.passwordHash())) {
            // Not counted towards A-021's lockout, for PasswordChangeService's
            // reason: a token holder could otherwise lock the real owner out of
            // the login form with five wrong guesses.
            log.info("auth: two-factor disable refused for user {} — password incorrect", userId);
            throw new InvalidCurrentPasswordException();
        }

        totp.disable(userId);
    }

    private long callerIdOf(String authorizationHeader) {
        Jwt accessToken = accessTokens.verify(authorizationHeader);
        return accessTokens.userIdOf(accessToken);
    }
}
