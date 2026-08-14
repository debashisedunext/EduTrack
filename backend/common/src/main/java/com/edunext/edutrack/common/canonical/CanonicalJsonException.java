package com.edunext.edutrack.common.canonical;

/**
 * A payload that has no canonical form.
 *
 * <p>Unchecked, and deliberately so: every rejection below is a programming
 * mistake at the call site rather than a condition a caller can recover from at
 * runtime. A value of an unsupported type, a null key, a timestamp carrying
 * precision the database cannot store — none of those become writable by being
 * caught, and a checked exception would only spread {@code throws} across the
 * append path and invite an empty catch block in the one place that must never
 * have one.
 *
 * <p>It fires at the append, before the row exists. That is the point: a hash
 * computed over something the canonicaliser was unsure about is a row A-044's
 * nightly verifier reports as tampering, months later, with nothing left to
 * explain it.
 */
public class CanonicalJsonException extends RuntimeException {

    public CanonicalJsonException(String message) {
        super(message);
    }
}
