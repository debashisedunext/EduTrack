package com.edunext.edutrack.api.feature.onboarding.settings;

import java.util.Set;

/**
 * B-113 · 400 — the body names a merge tag no event declares.
 *
 * <p>Refused at write time because this is the last place to catch it: an
 * unknown tag renders as literal braces in a client's inbox, and the person who
 * reads it is the one customer who was going to notice. The alternative —
 * substituting it to empty — is worse, because a sentence that silently loses
 * its subject still looks like a sentence.
 */
class UnknownMergeTagException extends RuntimeException {

    private final Set<String> tags;

    UnknownMergeTagException(Set<String> tags) {
        super("unknown merge tag(s): " + String.join(", ", tags));
        this.tags = Set.copyOf(tags);
    }

    Set<String> tags() {
        return tags;
    }
}
