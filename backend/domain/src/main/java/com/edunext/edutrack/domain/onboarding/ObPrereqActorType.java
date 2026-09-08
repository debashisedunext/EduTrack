package com.edunext.edutrack.domain.onboarding;

/**
 * B-125 · which of the two author columns {@code ob_prereq_comments} and
 * {@code ob_prereq_history} fill.
 *
 * <p>Both tables carry a {@code users} id and an {@code ob_client_contacts}
 * id and set exactly one — the shape {@code ob_step_communications} already
 * uses, and for the same reason: a staff member and a client contact are rows
 * in different tables, so a single polymorphic id would need a discriminator
 * anyway.
 *
 * <p>{@link #SYSTEM} is valid on history only, and is what a scanner or a
 * cascading gate evaluation writes. A comment always has a person behind it.
 */
public enum ObPrereqActorType {
    STAFF,
    CLIENT,
    SYSTEM
}
