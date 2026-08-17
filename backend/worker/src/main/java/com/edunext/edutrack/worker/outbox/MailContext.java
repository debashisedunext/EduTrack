package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * D-029 · the values a template's {@code {{tags}}} resolve to, for one mail.
 *
 * <p>Keyed by {@link MergeTag} rather than by string. A {@code Map<String,
 * String>} would compile just as well and would let a caller populate
 * {@code "ticketId"} — a name no template can ever reference, because
 * {@link MergeTag} spells it {@code ticket_id}. That mistake produces a mail
 * with an empty field and no error anywhere, which is the failure mode this
 * whole chain is trying to avoid.
 *
 * <h2>Absent and empty are the same answer here</h2>
 *
 * <p>A tag with no value renders as nothing. It deliberately does <em>not</em>
 * render as {@code {{client}}}, and it does not throw.
 *
 * <p>Leaving the braces would put them in front of a client — exactly the
 * outcome {@link MergeTag}'s validation exists to prevent at save time, arriving
 * instead at send time where no Admin can see it. Throwing would be worse: an
 * internal ticket legitimately has no client, and a template that mentions one
 * would then fail to send a breach alert rather than send it with one line
 * blank.
 */
record MailContext(Map<MergeTag, String> values) {

    static MailContext empty() {
        return new MailContext(new EnumMap<>(MergeTag.class));
    }

    /** The value for {@code tag}, or {@code ""} — never null, never the braces. */
    String get(MergeTag tag) {
        String value = values.get(tag);
        return value == null ? "" : value;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private final Map<MergeTag, String> values = new EnumMap<>(MergeTag.class);

        /** Null and blank are dropped rather than stored, so {@link #get} is total. */
        Builder put(MergeTag tag, String value) {
            if (value != null && !value.isBlank()) {
                values.put(tag, value);
            }
            return this;
        }

        MailContext build() {
            return new MailContext(values);
        }
    }
}
