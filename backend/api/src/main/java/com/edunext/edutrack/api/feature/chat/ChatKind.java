package com.edunext.edutrack.api.feature.chat;

/**
 * The three surfaces of blueprint §7.6, and the reason there is one engine
 * rather than three.
 *
 * <p>They differ in exactly two things: what the thread hangs off, and which
 * §9.3 room its messages are broadcast to. Everything else — posting, paging,
 * membership, unread counts, the five-minute edit window — is identical. Three
 * separate implementations would have to keep all of that in step, and the one
 * that drifts is the one nobody notices until a message is delivered to a room
 * its participants are not in.
 */
public enum ChatKind {

    /** Attached to a ticket. Everything said stays with the ticket forever. */
    TICKET,

    /** Manager ↔ resource, 1:1. No anchor other than its participants. */
    DIRECT,

    /** Team-wide channel for a project. */
    PROJECT
}
