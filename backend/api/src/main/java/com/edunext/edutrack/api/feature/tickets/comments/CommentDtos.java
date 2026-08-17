package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.tickets.TicketComment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * @param mentions        C-030 · the active project members the body named,
     *                        resolved from {@code mentioned_user_ids}. The column
     *                        is written as null rather than as an empty array, so
     *                        "nobody was mentioned" and "mentions were never
     *                        parsed" stay distinguishable in the data; both
     *                        render here as an empty list, because a client has
     *                        no use for the difference. An id that no longer
     *                        names a user is dropped rather than rendered as a
     *                        placeholder, which is {@link CommentUserRefs}' rule
     *                        for the author and holds for the same reason
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
     * @param editableUntil   when the author's edit expires, or <b>null when it
     *                        never does</b> — which is the default since the five
     *                        minutes were lifted (D-14). Also null on a
     *                        tombstone, where there is nothing left to edit.
     *
     *                        <p><b>A null therefore does not mean "cannot
     *                        edit".</b> That is the one dangerous reading of this
     *                        field and it was the client's rule until D-14: with
     *                        no limit configured every comment would lose its
     *                        Edit button. Whether the caller may edit is decided
     *                        by authorship and {@code isDeleted}; this field only
     *                        ever adds a deadline on top, and only when one is
     *                        configured.
     *
     *                        <p>Still <b>derived rather than stored</b>:
     *                        {@code createdAt} plus the configured window, so an
     *                        operator restoring §4B.5's five minutes at deploy
     *                        cannot leave stored deadlines disagreeing with the
     *                        rule the server actually applies
     * @param editedAt        when it was last rewritten, null if never.
     *                        <b>Added with D-14 and load-bearing because of
     *                        it</b>: while the window was five minutes, "edited"
     *                        implied "moments after posting" and the timestamp
     *                        was noise. With no limit it does not — a reader
     *                        cannot otherwise tell a typo fixed a minute later
     *                        from a claim rewritten three months on, and those
     *                        are very different facts about a record colleagues
     *                        have acted upon
     * @param originalBody    C-033 · what the author first posted, written once
     *                        on the first edit and never touched again. Null when
     *                        the comment has never been edited, <b>and cleared on
     *                        a tombstone</b>: leaving it would serve the exact
     *                        text a deletion was meant to remove, through a field
     *                        whose whole purpose is preserving text
     * @param deletedBy       C-033 · who tombstoned it. Null unless
     *                        {@code isDeleted}, and additionally null when that
     *                        account no longer exists — the client renders
     *                        "removed on 17 Aug" rather than inventing a name,
     *                        which is {@code CommentUserRefs}' rule throughout
     * @param deletedAt       C-033 · when. The other half of "removed by X on
     *                        date"; both are stamped by one write, so a row
     *                        carrying one without the other is not a state this
     *                        server produces
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
            @Schema(description = """
                    When the author's edit expires. **Null means there is no deadline** — the default \
                    since D-14 lifted §4B.5's five minutes — and null on a tombstone. Never read a \
                    null as "cannot edit"; authorship and `isDeleted` decide that.""",
                    nullable = true)
            Instant editableUntil,
            @Schema(description = "When it was last rewritten. Null if never edited.", nullable = true)
            Instant editedAt,
            @Schema(description = "C-033 · who removed it. Null unless `isDeleted`, and null again if that account is gone.",
                    nullable = true)
            UserRef deletedBy,
            @Schema(description = "C-033 · when it was removed. Null unless `isDeleted`.", nullable = true)
            Instant deletedAt,
            @Schema(description = "Stage at time of writing.", nullable = true)
            String stageCode,
            Integer cycleNo,
            @Schema(description = "Iteration at time of writing. Null until C-042 makes it readable.", nullable = true)
            Integer iterationNo,
            List<UserRef> mentions,
            List<Object> attachments,
            Instant createdAt) {

        /**
         * @param people     resolved ids from {@link CommentUserRefs#resolve},
         *                   which is given every id in the listing at once. A
         *                   missing id yields null rather than a placeholder name
         * @param roles      role codes by user id, from the same lookup
         * @param editWindow <b>the same {@link CommentProperties#editWindow} the
         *                   service enforces</b>, passed in rather than a constant
         *                   here. C-029 held §4B.5's five minutes as a literal,
         *                   which was correct while nothing enforced anything;
         *                   from C-033 there are two numbers deciding one rule,
         *                   and C-027 is the cautionary tale — its client-side
         *                   copy of a server cap did not disagree with the server
         *                   so much as silently override it, and the failure was
         *                   invisible in every log. A deployment that shortens the
         *                   window would otherwise show every author a countdown
         *                   running past a deadline the server has already refused.
         *
         *                   <p><b>Null when no window is configured</b>, which is
         *                   the default since D-14. The same parameter therefore
         *                   carries the absence of a limit, so restoring §4B.5's
         *                   five minutes at deploy moves the client's countdown
         *                   with it and needs no release
         */
        static CommentDto of(TicketComment row,
                             Map<Long, UserRef> people,
                             Map<Long, String> roles,
                             Duration editWindow) {
            // C-033 · a tombstone carries no text, in either field.
            //
            // `bodyHtml` is already emptied by the delete, and `originalBody`
            // would otherwise be a hole straight through it: a comment posted
            // client-visible by mistake, edited, and then removed would still
            // serve its first wording here — the very text the deletion was for,
            // through the one field designed to preserve text. Cleared on the way
            // out as well as on the way in, so a row written before this task, or
            // by a fixture, cannot leak either.
            boolean tombstoned = row.isDeleted();
            return new CommentDto(
                    row.getId(),
                    tombstoned ? "" : row.getBodyHtml(),
                    tombstoned ? null : row.getOriginalBody(),
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
                    tombstoned,
                    // Null when there is no deadline at all (D-14's default) and
                    // null on a tombstone, where a countdown beside "removed by
                    // Priya" would invite the one thing the server refuses.
                    tombstoned || editWindow == null || row.getCreatedAt() == null
                            ? null
                            : row.getCreatedAt().plus(editWindow),
                    // Not cleared on a tombstone, unlike the two body fields.
                    // "This was edited before it was removed" is part of the
                    // record rather than part of the content, and it carries no
                    // text — the whole reason the bodies go is that they do.
                    row.getEditedAt(),
                    tombstoned ? people.get(row.getDeletedBy()) : null,
                    tombstoned ? row.getDeletedAt() : null,
                    row.getStageCode(),
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    row.getIterationNo() == null ? null : (int) row.getIterationNo(),
                    mentionsOf(row, people),
                    List.of(),
                    row.getCreatedAt());
        }

        /**
         * C-030 · {@code mentioned_user_ids} rendered as the contract's
         * {@code UserRef[]}.
         *
         * <p>Order follows the column, which follows the id order
         * {@code CommentMentions} resolves in — stable across reads, which is
         * what stops a thread reshuffling its own chips between refreshes.
         *
         * <p>An unresolvable id is <b>dropped</b>. The alternative is a chip
         * reading "Unknown user", which is a name-shaped string where the
         * product means it does not know; the body still carries the
         * {@code @handle} verbatim, so nothing about what was written is lost.
         */
        private static List<UserRef> mentionsOf(TicketComment row, Map<Long, UserRef> people) {
            List<Long> ids = row.getMentionedUserIds();
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream()
                    .map(people::get)
                    .filter(Objects::nonNull)
                    .toList();
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
     * @param mentionUserIds  <b>accepted and deliberately not consulted, from
     *                        C-030.</b> The server parses the body and resolves
     *                        against the ticket's project members instead —
     *                        {@code CommentService#mentioned} carries the
     *                        argument, which is D-052's: a caller-supplied
     *                        recipient list is a notification-and-email fan-out
     *                        anybody can aim at anybody. Kept on the request
     *                        because the contract declares it and older clients
     *                        send it; refusing a request that is redundant
     *                        rather than wrong would break them for nothing
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

    /**
     * C-033 · {@code editComment}'s body — one field, and the contract says so.
     *
     * <p><b>Visibility is deliberately not editable.</b> §4B.5 makes
     * internal-versus-client-visible a decision taken once, before posting, and
     * C-031 draws it in a colour nobody can miss for exactly that reason. Letting
     * a PATCH flip it would make the five-minute window a way to publish an
     * internal note to a client — or worse, to <em>unpublish</em> one, which the
     * client has already received by email and which the thread would then
     * misreport for ever. A comment sent to the wrong audience is deleted and
     * rewritten, in the open, which is what the tombstone is for.
     *
     * <p>Neither is the stamp. Cycle and stage are what the ticket was doing when
     * the words were written, and an edit does not travel back to change that.
     *
     * @param body the new wording, sanitised server-side before it is stored. The
     *             {@code @Size} bound is over what was <em>sent</em>;
     *             {@link CommentSanitizer} applies the same bound to what is
     *             stored, which can be longer — an ampersand is one character in
     *             and five out
     */
    record EditCommentRequest(
            @NotBlank
            @Size(max = CommentSanitizer.MAX_LENGTH)
            String body) {
    }

    record CommentResponse(CommentDto data) {
    }

    record CommentListResponse(List<CommentDto> data, PageMeta meta) {
    }
}
