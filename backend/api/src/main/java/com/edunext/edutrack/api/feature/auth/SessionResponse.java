package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A-020 · the {@code { data }} envelope every 2xx body carries
 * ({@code contracts/CONVENTIONS.md} §2).
 *
 * <p>The wrapper is not ceremony: a response that returns the session object
 * bare cannot later grow a sibling — {@code meta}, a deprecation notice — without
 * breaking every generated client at once.
 */
@Schema(description = "Envelope for a successful login.")
record SessionResponse(Session data) {
}
