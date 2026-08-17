package com.edunext.edutrack.api.feature.imports;

/**
 * B-032 · every staging slot is taken — answered 503.
 *
 * <p>{@link InMemoryImportStagingStore} has refused past its ceiling since
 * B-030, with a message written to be read by a user ("Try again shortly"). It
 * threw a bare {@link IllegalStateException}, which was harmless while nothing
 * called {@code stage()} — <b>B-032 is the first caller</b>, so the refusal is
 * now reachable over HTTP and would arrive as a 500 with a stack trace, for a
 * condition that is temporary, expected, and the caller's to wait out.
 *
 * <p>Extends {@code IllegalStateException} rather than replacing it so the
 * store's contract does not change under B-030's own tests, and so a handler can
 * catch <em>this</em> specifically. Catching {@code IllegalStateException} in the
 * advice would have been one line less and would quietly turn every genuine bug
 * on this path into a cheerful "try again shortly".
 */
class ImportStagingFullException extends IllegalStateException {

    private final int ceiling;

    ImportStagingFullException(int ceiling) {
        super("Too many imports in progress (" + ceiling + "). Try again shortly.");
        this.ceiling = ceiling;
    }

    int ceiling() {
        return ceiling;
    }
}
