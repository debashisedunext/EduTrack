package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * C-033 · how long an author may keep changing their own wording.
 *
 * <h2>Unlimited by default — a deviation from §4B.5, recorded as D-14</h2>
 *
 * <p>The blueprint says "editable for 5 minutes; after that the comment is
 * locked", and this shipped that way. It was changed on Divyansh's direction
 * after the feature was driven by hand, for a case the five minutes does not
 * survive: <em>a developer posts a root-cause note, remembers the missing half
 * an hour later, and has no way to add it.</em> The comment they are then forced
 * to write is a second card saying "correction to the above", which is worse for
 * the reader than the edit would have been — the thread now carries two
 * fragments to reconcile instead of one accurate note.
 *
 * <p><b>What the window protected is not lost, because it was never the
 * window's job.</b> §4B.5's actual guarantee is the sentence beside it — "no
 * role, including Admin, can silently rewrite a comment" — and every part of
 * that still holds: only the author may edit, an edit is stamped and marked, the
 * first wording is preserved, and deletion still leaves a tombstone. The clock
 * only ever decided <em>when</em> the author lost the ability, never whether the
 * change was recorded.
 *
 * @param editWindow how long after posting the author may still change the
 *                   wording. <b>Absent means no limit</b>, which is the default
 *                   and the shipped behaviour; the edit stays available for as
 *                   long as the comment exists.
 *
 *                   <p>The property is kept rather than deleted so §4B.5's five
 *                   minutes can be restored by configuration alone — set
 *                   {@code edutrack.comments.edit-window: PT5M} — without a code
 *                   change, a migration or a release. Everything that enforces
 *                   it is still here and still tested.
 *
 *                   <p>A property and not a settings row, for the reason C-028
 *                   gave about its own fifteen minutes: §4B.4's "all
 *                   configurable in system settings" is a sentence about the
 *                   attachment <em>limits</em>. Overridable at deploy; not at
 *                   runtime by an administrator.
 *
 *                   <p>It bounds the edit only. Deletion has never had a window
 *                   — see {@link CommentService#delete}.
 */
@ConfigurationProperties("edutrack.comments")
record CommentProperties(Duration editWindow) {

    /**
     * Whether a deadline applies at all.
     *
     * <p>Named rather than left as a null check at the two call sites, because
     * "no window configured" and "the window has expired" are the two states
     * this feature must never confuse — one means always editable and the other
     * means never again.
     */
    boolean hasEditWindow() {
        return editWindow != null;
    }
}
