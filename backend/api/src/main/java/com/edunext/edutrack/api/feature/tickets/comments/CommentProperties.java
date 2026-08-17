package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * C-033 · the one number blueprint §4B.5 puts on the comment box.
 *
 * @param editWindow how long after posting the author may still change the
 *                   wording — §4B.5's "editable for 5 minutes; after that the
 *                   comment is locked".
 *
 *                   <p><b>A property and not a row in {@code attachment_settings}
 *                   or any other settings table</b>, for the reason C-028 gave
 *                   about its own fifteen minutes: §4B.4's "all configurable in
 *                   system settings" is a sentence about the attachment
 *                   <em>limits</em>. This is a retention rule, and an operator
 *                   able to set it to a year could quietly reopen every comment
 *                   on the product — which is the opposite of what the sentence
 *                   beside it ("no role, including Admin, can silently rewrite a
 *                   comment") asks for. Overridable at deploy so a test need not
 *                   wait five minutes; not at runtime by an administrator.
 *
 *                   <p>It bounds the edit only. Deletion has no window at all —
 *                   see {@link CommentService#delete}.
 */
@ConfigurationProperties("edutrack.comments")
record CommentProperties(@DefaultValue("PT5M") Duration editWindow) {
}
