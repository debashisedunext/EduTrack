package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.tickets.TicketComment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * C-029 · the wire shapes for {@code listComments} and {@code createComment},
 * per {@code contracts/openapi.yaml}'s {@code Comment} schema.
 *
 * <p>The contract needed no change for this task — all four comment operations
 * and all four schemas have been specified since D-001. What did not exist was
 * anything serving them.
 */
final class CommentDtos {

    private CommentDtos() {
    }

    /**
     * The contract's {@code UserRef}, as this feature needs it.
     *
     * <p>Declared locally rather than shared, following {@code ChatDtos.UserRef},
     * {@code ProjectDtos} and {@code AttachmentDtos.UserRef}: a common DTO across
     * four streams' features is shared surface that has to be renegotiated
     * whenever any one of them wants another field. The contract is the
     * agreement, not a Java class.
     */
    @Schema(name = "UserRef")
    record UserRef(long id, String displayName) {
    }

    /**
     * @param authorRole      <b>the author's role now, not the role they held when
     *                        they wrote this</b> — see {@link #of}. §4B.5 asks for
     *                        the latter and C-032 is the task that delivers it
     * @param stageCode       stamped at write time from the ticket's current stage
     * @param iterationNo     <b>always null today.</b> Nothing in the codebase can
     *                        yet read the open stage transition, which is where an
     *                        iteration number lives; C-042 is what makes it
     *                        readable and C-032 is what stamps it. Null is honest —
     *                        an invented {@code 1} would be indistinguishable from
     *                        a real first iteration
     * @param mentions        empty until C-030. The column is written as null
     *                        rather than as an empty array, so "nobody was
     *                        mentioned" and "mentions were never parsed" stay
     *                        distinguishable in the data
     * @param attachments     <b>empty in C-029, by decision rather than by
     *                        oversight.</b> §4B.5 does let a comment carry files
     *                        and {@code ticket_attachments.comment_id} exists for
     *                        it, but rendering them means minting signed download
     *                        URLs, and that lives behind {@code AttachmentService}
     *                        in the neighbouring package. Widening that class's
     *                        surface is its own change with its own review; doing
     *                        it as a side effect of the comment box is how a
     *                        signed-URL mint ends up somewhere nobody looks for
     *                        it. {@code attachmentIds} on the request is therefore
     *                        <b>rejected rather than silently dropped</b> — see
     *                        {@link CommentWriteRequest#attachmentIds}
     * @param editableUntil   five minutes after posting, per §4B.5. Sent from
     *                        C-029 even though nothing enforces it until C-033:
     *                        it is derived from {@code createdAt} and costs
     *                        nothing, and a client that has never seen the field
     *                        is a client that will not render the countdown when
     *                        the window becomes real
     * @param originalBody    null until C-033 writes one. The contract types it
     *                        nullable for exactly this reason
     */
    @Schema(name = "Comment")
    record CommentDto(
            Long id,
            String body,
            @Schema(description = "Preserved when edited.", nullable = true)
            String originalBody,
            @Schema(nullable = true)
            UserRef author,
            @Schema(description = """
                    The author's role. **C-029 sends their role as it is now, not the role they held \
                    when they wrote the comment** — `ticket_comments` has no `author_role` column and \
                    adding one is a migration, which is Stream A's path. §4B.5 asks for the stamped \
                    value and C-032 is the task that delivers it. The two differ only for someone \
                    whose role changed after commenting, which is rare and is exactly the case the \
                    stamp exists for.""",
                    allowableValues = {"ADMIN", "PM", "DEVELOPER", "QA", "DEPLOYMENT", "SUPPORT"},
                    nullable = true)
            String authorRole,
            boolean isClientVisible,
            boolean isEdited,
            @Schema(description = "Tombstone; the row survives.")
            boolean isDeleted,
            @Schema(description = "Five minutes after posting (§4B.5). Not enforced until C-033.", nullable = true)
            Instant editableUntil,
            @Schema(description = "Stage at time of writing.", nullable = true)
            String stageCode,
            Integer cycleNo,
            @Schema(description = "Iteration at time of writing. Null until C-042 makes it readable.", nullable = true)
            Integer iterationNo,
            List<UserRef> mentions,
            List<Object> attachments,
            Instant createdAt) {

        /** §4B.5: "Editable for 5 minutes; after that the comment is locked". */
        private static final long EDIT_WINDOW_MINUTES = 5;

        /**
         * @param people resolved ids from {@link CommentUserRefs#resolve}, which
         *               is given every id in the listing at once. A missing id
         *               yields null rather than a placeholder name
         * @param roles  role codes by user id, from the same lookup
         */
        static CommentDto of(TicketComment row, Map<Long, UserRef> people, Map<Long, String> roles) {
            return new CommentDto(
                    row.getId(),
                    row.getBodyHtml(),
                    row.getOriginalBody(),
                    people.get(row.getAuthorId()),
                    roles.get(row.getAuthorId()),
                    // The column is `is_internal` and the contract's field is
                    // `isClientVisible`. Inverted here, in one place, rather than
                    // renamed on either side: the column's default is what makes
                    // "internal unless someone said otherwise" true even for a row
                    // inserted by a fixture or a future email importer, and the
                    // contract's positive phrasing is what stops a client from
                    // reading a missing field as "safe to show the client".
                    !row.isInternal(),
                    row.getEditedAt() != null,
                    row.isDeleted(),
                    row.getCreatedAt() == null
                            ? null
                            : row.getCreatedAt().plusSeconds(EDIT_WINDOW_MINUTES * 60),
                    row.getStageCode(),
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    row.getIterationNo() == null ? null : (int) row.getIterationNo(),
                    List.of(),
                    List.of(),
                    row.getCreatedAt());
        }
    }

    /**
     * @param body            sanitised server-side before it is stored — the
     *                        {@code @Size} bound here is over what was
     *                        <em>sent</em>, and {@link CommentSanitizer} applies
     *                        the same bound to what is stored, which can be
     *                        longer. {@code @NotBlank} catches an empty
     *                        submission; it cannot catch a body that is
     *                        non-blank now and empty after §3.9 runs, which is
     *                        {@link CommentService}'s job
     * @param isClientVisible <b>defaults to false and the boxed type is the
     *                        reason.</b> A primitive {@code boolean} would make
     *                        an omitted field indistinguishable from an explicit
     *                        {@code false}, which is harmless in this direction
     *                        but hides the one thing worth knowing if the default
     *                        is ever revisited (§4B.5's own wording is "follows
     *                        whether the ticket is client-raised", which the
     *                        contract and §16 overrode with internal-always)
     * @param mentionUserIds  accepted and stored so the column is populated from
     *                        the first comment, but nothing is notified until
     *                        C-030 builds the type-ahead and D's fan-out
     * @param attachmentIds   <b>rejected with a 400 if non-empty</b>, rather than
     *                        accepted and ignored. C-028's notes flag "accepted
     *                        and ignored" as a real defect on the mock's upload
     *                        handler for the mirror-image field, and for the same
     *                        reason: a client that gets a 201 back is entitled to
     *                        believe its files are on the comment. Refusing says
     *                        so; ignoring lies
     */
    record CommentWriteRequest(
            @NotBlank
            @Size(max = CommentSanitizer.MAX_LENGTH)
            String body,
            Boolean isClientVisible,
            List<Long> mentionUserIds,
            List<Long> attachmentIds) {
    }

    record CommentResponse(CommentDto data) {
    }

    record CommentListResponse(List<CommentDto> data, PageMeta meta) {
    }
}
