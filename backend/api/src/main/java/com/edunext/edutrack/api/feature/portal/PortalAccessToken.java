package com.edunext.edutrack.api.feature.portal;

/**
 * C-121 · a minted CLIENT access token and the lifetime it was minted with.
 * {@code AccessToken}'s shape, one principal type over.
 */
record PortalAccessToken(String value, int expiresInSeconds) {
}
