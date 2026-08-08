package com.edunext.edutrack.api.feature.auth;

/**
 * A-021 · an Admin to notify when an account locks. Blueprint §S-01.
 *
 * <p>Both fields are needed by {@code email_log}: the address to send to, and
 * the user id so the row can be attributed and later joined for the delivery
 * report (A-068).
 */
record AdminRecipient(long userId, String email) {
}
