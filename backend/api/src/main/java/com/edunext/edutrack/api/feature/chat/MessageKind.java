package com.edunext.edutrack.api.feature.chat;

/**
 * {@code ChatMessage.kind} in the contract.
 *
 * <p>Note this is a property of the <em>message</em>, not of the thread. The
 * baseline schema models "Ask Status" as a {@code thread_type}, but blueprint
 * §7.6 says the structured message is posted "into that ticket's thread" — so a
 * status request is one message inside the ordinary ticket conversation, not a
 * separate conversation. Where the two disagree the blueprint wins on
 * behaviour (CLAUDE.md), and it is also the better model: a status request and
 * its reply belong in the same scrollback as the work they are about.
 */
public enum MessageKind {

    /** Someone typed it. */
    TEXT,

    /** D-055's "Ask Status" card, with its reply actions. */
    STATUS_REQUEST,

    /** Posted by EduTrack — a handoff landing, a stage advancing. */
    SYSTEM
}
