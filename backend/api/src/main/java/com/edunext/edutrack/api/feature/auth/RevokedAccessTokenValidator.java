package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * A-032 · the reader {@link AccessTokenBlacklist} was written for.
 *
 * <h2>Why a signature and an expiry are not enough</h2>
 *
 * <p>A JWT is valid because it is signed and unexpired, not because anything
 * server-side still says so — that statelessness is the whole appeal, and the
 * exact reason a token cannot be called back. Spring's stock validators check
 * {@code exp}, {@code nbf} and {@code iss}, all of which are answered from
 * inside the token. None of them has any way to know a user pressed Sign out.
 *
 * <p>Without this class, A-025's blacklist is written on every logout and read
 * by nothing: the key lands in Redis, the token keeps working for its remaining
 * fifteen minutes, and logout is advisory. On a shared machine that is fifteen
 * minutes of somebody else's session after they believed they closed it.
 *
 * <h2>Registered on the decoder, not on the filter chain</h2>
 *
 * <p>{@link com.edunext.edutrack.api.security.jwt.JwtDecoderConfig} composes this
 * into the single {@code JwtDecoder} bean, so <b>every</b> path that reads an
 * access token enforces revocation — the filter chain and the five services that
 * still call {@code AccessTokenVerifier} directly. Adding it only to the chain
 * would leave those services honouring a token the chain would have refused, and
 * two verification paths that can drift is precisely what
 * {@code JwtDecoderConfig} warns against when it says A-032 must consume that
 * bean rather than introduce a second one.
 *
 * <p>One consequence, accepted deliberately: {@code POST /auth/logout} is itself
 * an authenticated route, so logging out twice with the same token answers 401
 * the second time. That is correct — a revoked token must not act, and logout is
 * not special — and it is invisible in practice because {@code useSignOut}
 * issues the request without awaiting it and clears local state regardless. A
 * logout that reported an error would invite a second click with nothing left to
 * do.
 *
 * <h2>Fail closed, breaking the pattern its neighbours set</h2>
 *
 * <p>{@code LoginRateLimiter}, {@code PasswordResetRateLimiter} and
 * {@code RefreshTokenIssuer} all fail <i>open</i> when Redis is unreachable: a
 * degraded cache must not become a total outage, and each of those protects a
 * defence-in-depth measure whose absence is survivable.
 *
 * <p><b>This one fails closed, and the difference is what is being protected.</b>
 * Those three guard against something that might happen. This one enforces a
 * decision somebody has already made and been told took effect — an admin
 * revoking a compromised session, a user signing out of a shared machine.
 * "The revocation did not apply because the cache was down" is an incident, not
 * a degradation. The cost is that a Redis outage signs everybody out, which is
 * loud, obvious and recoverable; the alternative silently un-revokes every
 * logged-out token at once, which is none of those things.
 *
 * <p>{@link AccessTokenBlacklist#isRevoked} states this direction from its own
 * side: it reports <i>revoked</i>, and an exception must be treated as
 * un-answerable rather than as "not revoked".
 */
@Component
class RevokedAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenValidator.class);

    /**
     * The error code is deliberately the same for a revoked token, a token with
     * no {@code jti} and an unreachable Redis. Naming which check failed tells
     * someone probing with a stolen token whether it was revoked or merely
     * malformed, and the entry point flattens every one of them into the same
     * {@code invalid-access-token} problem anyway.
     */
    private static final String INVALID_TOKEN = "invalid_token";

    private final AccessTokenBlacklist blacklist;

    RevokedAccessTokenValidator(AccessTokenBlacklist blacklist) {
        this.blacklist = blacklist;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();
        if (jti == null || jti.isBlank()) {
            // AccessTokenIssuer always mints one, so this is either a token from
            // before that was true or one this application did not issue. Either
            // way it cannot be revoked, and a token that cannot be revoked must
            // not be trusted on the strength of that.
            log.warn("auth: access token has no jti and cannot be checked for revocation - refusing");
            return refuse("The access token cannot be checked for revocation.");
        }

        try {
            if (blacklist.isRevoked(jti)) {
                return refuse("The access token has been revoked.");
            }
            return OAuth2TokenValidatorResult.success();
        } catch (DataAccessException e) {
            // ERROR, not WARN: every authenticated request in the system is now
            // failing, and this is the only line that says why.
            log.error("auth: revocation store unreachable - refusing every access token until it recovers", e);
            return refuse("The access token cannot be checked for revocation.");
        }
    }

    private static OAuth2TokenValidatorResult refuse(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_TOKEN, description, null));
    }
}
