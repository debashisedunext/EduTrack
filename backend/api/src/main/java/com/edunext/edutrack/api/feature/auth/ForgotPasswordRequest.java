package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A-027 · the body of {@code POST /auth/forgot-password}.
 *
 * <p>As with {@link LoginRequest}, the Bean Validation annotations <b>are</b> the
 * schema (PLAN.md §2.2, deviation D-4) — springdoc emits them, orval turns them
 * into Zod, and the rule is written once.
 *
 * <p><b>{@code @Email} is a shape check, not an existence check</b>, and the
 * distinction is the whole security property of this endpoint. Rejecting
 * {@code "not-an-address"} with a 400 tells the caller only that they typed
 * something that is not an email — a fact they already had. Whether a
 * well-formed address belongs to anyone is never revealed: that request is
 * accepted, answered 202, and quietly does nothing. See
 * {@link ForgotPasswordService}.
 *
 * <p>The 150 ceiling matches {@code users.email VARCHAR(150)} from A-003.
 * Without it an unbounded string is hashed into a Redis rate-limit key on every
 * request, which is a cheap way to make the server do pointless work.
 */
record ForgotPasswordRequest(

        @NotBlank
        @Email
        @Size(max = 150)
        @Schema(description = "The address the reset link is sent to. Whether an account exists "
                + "for it is never revealed — the response is the same either way.",
                example = "asha.rao@edunext.test")
        String email
) {

    /**
     * <b>Trimmed here, in the constructor, so that {@code @Email} validates the
     * trimmed value.</b>
     *
     * <p>Not cosmetic. Bean Validation runs against the constructed record, and
     * Jakarta's {@code @Email} rejects any value with surrounding whitespace — so
     * without this, an address pasted out of a mail client or a spreadsheet with
     * one trailing space is answered {@code 400 Bad Request}. That is a real
     * thing people do on a form they have arrived at because they are already
     * locked out, and "your email address is invalid" is an unhelpful thing to
     * tell them about an address that is perfectly valid.
     *
     * <p>Caught by {@code AuthControllerTest.normalisesTheAddress}, which sent a
     * padded address and got the 400 rather than the 202 the endpoint promises.
     */
    ForgotPasswordRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    /**
     * Lower-cased, because this value is both a database lookup and a rate-limit
     * key and the two must agree on what "the same address" means.
     *
     * <p>MySQL's {@code utf8mb4_0900_ai_ci} collation already matches
     * case-insensitively, so {@code Asha@x} and {@code asha@x} find the same
     * user — but {@code Digests.sha256Hex} does not, so without folding case
     * here the same person alternating capitalisation would get a fresh
     * rate-limit budget each time and the per-address cap would be trivially
     * bypassed.
     */
    String normalisedEmail() {
        return email == null ? "" : email.toLowerCase(java.util.Locale.ROOT);
    }
}
