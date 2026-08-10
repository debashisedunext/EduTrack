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
 * <p><b>Only the bounds, not the whole policy.</b> The contract's {@code Password}
 * schema (openapi.yaml) says "upper, lower, digit and symbol required; the last
 * three are not reusable" in its <i>description</i> and carries no {@code pattern}
 * — the composition rules and the reuse history are A-028, which also has to
 * create the history table that does not exist yet. Min 8 / max 128 is what the
 * contract actually constrains today and is what this enforces. Until A-028
 * lands, a forced change can set a weak-but-long password; that gap is named
 * here rather than left to be discovered.
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
        @Schema(description = "The replacement. Must differ from the current password.")
        String newPassword
) {
}
