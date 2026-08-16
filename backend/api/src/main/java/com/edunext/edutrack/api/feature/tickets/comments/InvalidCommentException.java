package com.edunext.edutrack.api.feature.tickets.comments;

/**
 * C-029 · a comment the server will not store, reported against the field that
 * has to change.
 *
 * <p>One class for three refusals rather than three classes, because the
 * contract gives {@code createComment} exactly one failure shape —
 * {@code 400 ValidationFailed}, whose {@code errors} is field-keyed — and the
 * remedy in every case is "change this field and resend". {@code PriorityService}
 * splits its refusals into separate types for the opposite reason: its four
 * remedies genuinely differ ("pick another code", "you cannot change this one",
 * "move the escalation flag first"), and the S-12 form tells them apart by URI.
 * Here there is nothing to tell apart.
 *
 * <h2>Why these are not Bean Validation</h2>
 *
 * <p>{@code @NotBlank} and {@code @Size} on {@link CommentDtos.CommentWriteRequest}
 * already cover what can be judged from the submitted string. None of these three
 * can be:
 *
 * <ul>
 *   <li>{@link #emptyBody()} is about what survives §3.9. A body of
 *       {@code <script>alert(1)</script>} is a non-blank 27-character string that
 *       {@code @NotBlank} accepts and that sanitises to nothing.</li>
 *   <li>{@link #tooLong(int)} is about the <em>sanitised</em> length, which can
 *       exceed the submitted one — see {@link CommentSanitizer}.</li>
 *   <li>{@link #attachmentsNotSupported()} is about what this build implements,
 *       which is not a property of the value at all.</li>
 * </ul>
 */
class InvalidCommentException extends RuntimeException {

    private final transient String field;

    private InvalidCommentException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** Which input the message belongs under, so the box can mark itself. */
    String field() {
        return field;
    }

    /**
     * Nothing survived §3.9's allow-list.
     *
     * <p>The wording avoids naming what was stripped. Telling someone their
     * {@code <script>} tag was removed is a useful hint to the one person in a
     * thousand who meant it maliciously and no help at all to the other 999,
     * who pasted something odd out of an email client and want to know the post
     * did not go through.
     */
    static InvalidCommentException emptyBody() {
        return new InvalidCommentException("body",
                "A comment needs some text. Formatting on its own is not enough.");
    }

    /**
     * @param length the sanitised length, which is the number the user cannot
     *               see. Named in the message anyway: "too long" against a box
     *               that shows 19 000 of 20 000 is the kind of refusal people
     *               retry verbatim
     */
    static InvalidCommentException tooLong(int length) {
        return new InvalidCommentException("body",
                "That comment is too long once its formatting is stored — %,d characters against a limit of %,d. Shortening it, or pasting it as plain text, will both work."
                        .formatted(length, CommentSanitizer.MAX_LENGTH));
    }

    /**
     * §4B.5 does let a comment carry files and this build does not yet.
     *
     * <p>Refused rather than dropped. C-028's own notes call "accepted and
     * ignored" a defect on the mock's mirror-image field, and a 201 is a promise
     * that what was sent was stored.
     */
    static InvalidCommentException attachmentsNotSupported() {
        return new InvalidCommentException("attachmentIds",
                "Files cannot be attached to a comment yet. Attach them to the ticket instead — they will appear in its gallery.");
    }
}
