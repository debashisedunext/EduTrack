package com.edunext.edutrack.api.feature.auth;

/**
 * A-027 · the reset token was unknown, expired, or has already been redeemed.
 *
 * <p><b>One exception for all three, and the contract asks for one status.</b>
 * {@code POST /auth/reset-password} answers {@code 410 Gone} for "token expired
 * or already used", and an unknown token joins them here rather than getting a
 * 404 of its own. Telling the three apart would let someone holding a token
 * learn whether it was ever real — and, worse, whether the account it belongs to
 * has already recovered — from an endpoint that requires no authentication at
 * all. The same flattening {@link InvalidRefreshTokenException} applies to
 * refresh refusals, for the same reason.
 *
 * <p>The <i>server</i> still tells them apart: {@link ResetPasswordService} logs
 * each case distinctly, because "this link was redeemed twice" is a security
 * event and "this link expired" is a Tuesday. The distinction belongs in the log,
 * where it informs an investigation, not in the response, where it informs an
 * attacker.
 *
 * <p>410 rather than 401 or 404, per the contract. It is the honest status: the
 * token was a real resource with a fixed lifetime, and that lifetime is over.
 * A 401 would invite the frontend's interceptor to redirect to login, which is
 * wrong for a flow whose entire premise is that the user cannot log in.
 */
class InvalidResetTokenException extends RuntimeException {

    InvalidResetTokenException() {
        super("Reset token is unknown, expired or already used", null, false, false);
    }
}
