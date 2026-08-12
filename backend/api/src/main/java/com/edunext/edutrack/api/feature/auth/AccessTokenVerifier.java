package com.edunext.edutrack.api.feature.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * A-026 · the one place an {@code Authorization: Bearer …} header is turned into
 * a verified token, for the endpoints that must authenticate their own caller
 * because A-032's filter chain does not exist yet.
 *
 * <p><b>Extracted rather than copied.</b> A-025 did this inline in
 * {@link LogoutService}; A-026 needs the identical five lines on
 * {@code PATCH /me/password}, and a second hand-rolled copy of a security check
 * is how the two drift — one gains a clock-skew allowance, the other does not,
 * and nobody notices because both still pass their own tests. There will be a
 * third caller only if A-032 slips further; when it lands, the chain replaces
 * every caller of this class at once, which is much easier to do against one
 * implementation than three.
 *
 * <p><b>It verifies, it does not parse.</b> Signature, algorithm, issuer and
 * expiry are all {@code JwtDecoder}'s business — see {@code JwtDecoderConfig}.
 * Nothing here reads a JWT by hand, because a token accepted without
 * verification lets a caller forge any {@code sub} they like, which on the two
 * endpoints using this class means logging out or re-passwording a stranger.
 *
 * <h2>A-032 landed, and this class survives it — deliberately</h2>
 *
 * <p>The note above anticipated "the chain replaces every caller of this class
 * at once". It did not, and the reason is worth stating so the next reader does
 * not mistake this for a leftover. The chain answers <i>whether</i> a caller is
 * authenticated; these five services need the <i>token itself</i> — the
 * {@code jti} to blacklist, the {@code exp} to size the blacklist entry, the
 * {@code sub} to resolve. Rewriting them to pull a {@code Jwt} back out of the
 * {@code SecurityContext} would be the same work through a longer route.
 *
 * <p><b>What did change is that this is no longer a second security decision.</b>
 * It shares the one {@code JwtDecoder} bean with the chain, and A-032 put the
 * revocation check inside that bean rather than on the chain — so a token
 * refused at the perimeter is refused here too, by the same code, for the same
 * reasons. Every caller of this class now sits behind the chain as well, making
 * these calls a second, redundant verification rather than the only one. That is
 * cheap and honest; what it must never become is a check the chain does not
 * make, or vice versa.
 */
@Component
class AccessTokenVerifier {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;

    AccessTokenVerifier(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * @param authorizationHeader the raw header value, or null when absent
     * @return the verified token
     * @throws InvalidAccessTokenException if the header is missing, not Bearer,
     *                                     malformed, unsigned by us, or expired —
     *                                     deliberately flattened into one refusal,
     *                                     because naming the failed check tells
     *                                     someone probing with forged tokens
     *                                     exactly how close they came
     */
    Jwt verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            throw new InvalidAccessTokenException();
        }
        try {
            return jwtDecoder.decode(authorizationHeader.substring(BEARER.length()).trim());
        } catch (JwtException e) {
            throw new InvalidAccessTokenException();
        }
    }

    /**
     * The {@code sub} claim as the numeric user id {@code AccessTokenIssuer} put
     * there.
     *
     * <p>A token that reaches here is signed by us, so a non-numeric subject is
     * not a forgery — it is our own issuer having changed what {@code sub} means
     * without this side being updated. Refused rather than allowed to become a
     * 500, because the alternative to a clean 401 is an unhandled
     * {@link NumberFormatException} on a security path.
     */
    long userIdOf(Jwt accessToken) {
        try {
            return Long.parseLong(accessToken.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidAccessTokenException();
        }
    }
}
