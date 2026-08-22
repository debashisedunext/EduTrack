package com.edunext.edutrack.api.feature.auth;

/**
 * A-020 · the {@code { data }} envelope for {@code GET /me}, matching
 * {@code MeResponse} in {@code contracts/openapi.yaml}.
 *
 * <p>Named for the contract schema for {@link Me}'s own reason: springdoc emits
 * this name, and D-005 reads a renamed schema as client drift.
 */
record MeResponse(Me data) {
}
