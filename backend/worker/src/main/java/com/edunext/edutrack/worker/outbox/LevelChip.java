package com.edunext.edutrack.worker.outbox;

import java.util.Locale;

/**
 * D-030 · the colours of the level chip, per blueprint §12.1.
 *
 * <p>Hard-coded hex, and it has to be. §12.1's tokens live in
 * {@code frontend/src/styles/tokens.css} as CSS custom properties, and a mail
 * cannot reach them: Gmail strips {@code <style>} blocks and {@code <head>}
 * entirely, so every colour in an email is an inline literal or it is nothing.
 * These are the same four values the blueprint names —
 * Low {@code #10B981} · Medium {@code #3B82F6} · High {@code #F59E0B} ·
 * Critical {@code #EF4444} — with the soft background and solid text pairs
 * §12.1 pins alongside them.
 *
 * <p><strong>Soft background, solid text — never a heavy solid block.</strong>
 * That is §12.1 stated as a rule rather than a suggestion, and it matters more
 * in mail than in the app: a solid red band at the top of an alert is what
 * makes people filter the alert.
 *
 * <p>In Java rather than in the template because it is a mapping with a
 * fallback, and a {@code th:switch} over four cases in a file nobody can unit
 * test is where an unknown level silently renders as no chip at all. The
 * priority master is Admin-extensible (S-12), so an unknown level is a normal
 * future state, not a bug.
 */
enum LevelChip {

    LOW("#ECFDF5", "#047857"),
    MEDIUM("#EFF6FF", "#1D4ED8"),
    HIGH("#FFFBEB", "#B45309"),
    CRITICAL("#FEF2F2", "#B91C1C"),

    /**
     * Anything an Admin has added since. Neutral, so a level this build has
     * never heard of still renders as a legible chip rather than vanishing or
     * borrowing another level's meaning — grey says "no opinion", and red
     * would say something false.
     */
    UNKNOWN("#F7F8FB", "#475069");

    private final String background;
    private final String text;

    LevelChip(String background, String text) {
        this.background = background;
        this.text = text;
    }

    String background() {
        return background;
    }

    String text() {
        return text;
    }

    static LevelChip of(String level) {
        if (level == null || level.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownLevel) {
            return UNKNOWN;
        }
    }
}
