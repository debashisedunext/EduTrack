package com.edunext.edutrack.api.upload;

/**
 * B-107 · the extension is off the allow-list, the bytes are unrecognisable, or
 * the two disagree — 415.
 *
 * <p>The neutral form of C-025's own refusal. The <em>decision</em> is not
 * remade here: {@link UploadPipelineConfig} catches that package's exception and
 * rethrows this one carrying its message unchanged, so the sentence a user reads
 * is still the one written for the person who chose the file.
 *
 * <p>Translated rather than shared because the type would otherwise be the
 * dependency A-115 forbids — an onboarding exception handler naming a ticketing
 * class is exactly the shared type that rule exists to keep out.
 */
public class UnsupportedUploadTypeException extends RuntimeException {

    public UnsupportedUploadTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
