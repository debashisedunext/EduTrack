package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObCategory;

/**
 * B-112 · one rendered bell entry, on its way to {@code ob_notifications}.
 *
 * <p>{@link ObMailContent}'s counterpart, and the same reason for existing: the
 * renderer produces several values that only make sense together, and returning
 * them as a record keeps {@link InAppChannelAdapter} from re-deriving any of
 * them.
 *
 * @param title    plain text, already truncated to the column
 * @param body     plain text, one line; never null — a template that lost every
 *                 value falls back rather than dropping
 * @param category OB-13's tab, taken from the event and not from the payload
 * @param linkUrl  an in-app relative path, or null when the row names no client
 */
record ObInAppContent(String title, String body, ObCategory category, String linkUrl) {
}
