package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A-026 · the body of {@code PATCH /api/v1/me/password}, matching the contract's
 * inline schema for {@code changeOwnPassword}.
 *
 * <p>As with {@link LoginRequest}, the Bean Validation annotations <b>are</b> the
 * schema (PLAN.md §2.2, deviation D-4): springdoc turns them into
 * {@code required}/{@code minLength}/{@code maxLength}, orval turns that into
 * Zod, and one rule written here is enforced in the browser and on the server.
 *
 * <p><b>The asymmetry between the two fields is deliberate.</b>
 * {@code currentPassword} carries only {@code @NotBlank} — it is a password that
 * already exists, and applying today's length rule to it would reject the very
 * users a policy change is meant to migrate, telling them their real password is
 * invalid. {@code newPassword} carries the contract's {@code Password} bounds,
 * because policy applies when a password is <i>set</i>.
 *
 * <p><b>A-028 completed the policy.</b> This previously carried only the
 * contract's 8–128 bounds, and the gap was named here rather than hidden:
 * composition and the no-reuse rule were deferred because the latter needed a
 * history table that did not exist. {@link ValidPassword} now adds the four
 * character classes, and {@link PasswordPolicy} adds the reuse check — which
 * cannot live on this record, because it needs the account's identity and three
 * database rows that a constraint validator has no way to reach.
 *
 * <p>The 128 ceiling is not decoration. Argon2id hashes whatever it is given, so
 * an unbounded field is a CPU-and-memory amplifier: one request with a megabyte
 * password costs the server far more than it costs the client to send.
 */
record ChangePasswordRequest(

        @NotBlank
        @Schema(description = "The password being replaced. Verified before anything is written.")
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 128)
        @ValidPassword
        @Schema(description = "The replacement. Must differ from the current password, must not "
                + "match one of the last few used, and must contain an upper-case letter, a "
                + "lower-case letter, a digit and a symbol.")
        String newPassword
) {
}
