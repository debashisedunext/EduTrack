package com.edunext.edutrack.api.feature.masters.statuses;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * B-039 · S-13 tab 1 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request types is the single source of truth for the
 * field rules — the contract's {@code pattern}/{@code maxLength} and these
 * annotations describe the same constraint, and only one of them is the one that
 * actually runs. The same call {@code PriorityDtos} and {@code TaskTypeDtos}
 * make, one package over each.
 *
 * <p><b>The wire name matches the column here, unlike {@code PriorityDtos}.</b>
 * That class carries a {@code level}/{@code code} mismatch because the contract
 * had said {@code Priority.level} since D-001 and two shipped Stream C screens
 * already read it. Nothing had ever read a status master, so there was no
 * existing name to preserve and no reason to invent a divergence.
 */
final class StatusDtos {

    private StatusDtos() {
    }

    /** The colour rule, stated once and shared by both write shapes. */
    static final String COLOUR_PATTERN = "^#[0-9A-Fa-f]{6}$";

    static final String COLOUR_MESSAGE =
            "colour must be a #RRGGBB token from blueprint §12.1";

    static final String CATEGORY_PATTERN = "^(TODO|IN_PROGRESS|DONE)$";

    static final String CATEGORY_MESSAGE =
            "category must be TODO, IN_PROGRESS or DONE";

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * A status as the S-13 tab 1 grid renders it.
     *
     * <p><b>Two counts, and only one of them refuses.</b> The asymmetry is the
     * opposite way round from {@code PriorityView}'s, and worth stating because
     * the intuition points the wrong way:
     *
     * <ul>
     *   <li>{@code ticketCount} — tickets currently in this status. <b>Blocks</b>
     *       a retire. On the priority master the equivalent count never blocks,
     *       because a ticket keeps rendering a retired level and nothing else
     *       changes. A status is not decoration: it is the left-hand side of
     *       every transition lookup, and retiring one deactivates the rows out
     *       of it. A ticket left sitting there has no move offered on any screen
     *       and no way out except a database edit.</li>
     *   <li>{@code transitionCount} — active {@code workflow_transitions} rows
     *       naming this status on either side. Never blocks, and is the number
     *       the retire dialog quotes, because those are exactly the rows the
     *       retire will deactivate.</li>
     * </ul>
     *
     * @param deactivatedTransitions non-null <b>only</b> on the response to a
     *                               {@code PATCH} that retired this status. It
     *                               describes an event rather than the row, so
     *                               every read leaves it null — and it is
     *                               deliberately outside the {@code ETag}
     *                               computation, since a tag that changed
     *                               depending on how the row was last written
     *                               would fail an edit that conflicts with
     *                               nothing.
     */
    record StatusView(
            int id,
            String code,
            String name,
            String category,
            String colour,
            short seq,
            boolean isOpen,
            boolean isTerminal,
            boolean isActive,
            long ticketCount,
            int transitionCount,
            Integer deactivatedTransitions) {

        /** The same row with the event field cleared — what a read returns. */
        StatusView asRead() {
            return new StatusView(id, code, name, category, colour, seq,
                    isOpen, isTerminal, isActive, ticketCount, transitionCount, null);
        }

        /**
         * What the {@code ETag} is taken over.
         *
         * <p>{@code deactivatedTransitions} is excluded on purpose — see the
         * record's javadoc. Everything else is in, the two counts included,
         * because they are what the retire decision was made against.
         */
        StatusView etagBasis() {
            return asRead();
        }
    }

    record StatusListResponse(List<StatusView> data) {
    }

    record StatusResponse(StatusView data) {
    }

    /**
     * One cell of the matrix.
     *
     * <p>{@code fromStatus} is null for "on creation" — the only way into
     * {@code NEW}, and the row whose disappearance would leave nobody able to
     * raise a ticket.
     */
    record TransitionView(
            int id,
            String fromStatus,
            String toStatus,
            String roleCode,
            boolean requiresReason,
            boolean requiresEffort,
            boolean isActive) {
    }

    record TransitionMatrixResponse(List<TransitionView> data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code colour} is required here and nullable on the column — the same call
     * {@code PriorityWrite} makes and for the same reason: a status with no
     * colour is a hole in the ticket grid's chip, in the board column header and
     * in every status chart at once, which is three things rendering wrongly
     * rather than one rendering nothing.
     *
     * <p>{@code seq} is a boxed {@link Integer} because zero is a legitimate
     * value — first in the lifecycle — so it cannot double as the sentinel for
     * "not stated". {@code PriorityWrite} and {@code TaskTypeWrite} both make the
     * same call.
     *
     * <p>{@code category} is a {@code String} with a {@code @Pattern} rather than
     * a Java enum, matching how the column is stored. An enum here would give
     * Jackson a 400 with a message naming Java type names on a value the contract
     * describes as a closed set — the pattern's message is the sentence the
     * screen can show.
     */
    record StatusWrite(
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,19}$",
                    message = "code must start with a letter and use only letters, digits and underscores")
            @Size(min = 2, max = 20, message = "code must be 2-20 characters")
            String code,

            @NotBlank(message = "name is required")
            @Size(max = 40, message = "name must be at most 40 characters")
            String name,

            @NotBlank(message = "category is required")
            @Pattern(regexp = CATEGORY_PATTERN, message = CATEGORY_MESSAGE)
            String category,

            @NotBlank(message = "colour is required")
            @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
            String colour,

            Integer seq,

            Boolean isOpen,

            Boolean isTerminal,

            Boolean isActive) {
    }

    /**
     * Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>A record, where {@code PriorityPatch} had to be a POJO.</b> That one
     * has {@code defaultSlaHrs} — a nullable column whose null means something
     * ("this level contributes no rung 4") — so "absent" and "explicitly null"
     * had to differ, and a record's canonical constructor cannot express that.
     * <b>No column on {@code statuses} is clearable.</b> {@code colour} is
     * nullable in the DDL and the write shape has required it since this task, so
     * there is no state in which a caller means "remove the colour"; every other
     * field is {@code NOT NULL}. With nothing to clear, null unambiguously means
     * "leave alone" and the simpler shape is the correct one.
     *
     * <p><b>{@code code} is here only so that sending a different one can be
     * refused.</b> Leaving it off the type means Jackson discards it silently and
     * a caller who believed they had renamed the code is told the save succeeded.
     * {@code tickets.status} stores this string and is not a foreign key, so a
     * rename does not cascade — it orphans every ticket ever raised.
     */
    record StatusPatch(
            String code,

            @Size(min = 1, max = 40, message = "name must be 1-40 characters")
            String name,

            @Pattern(regexp = CATEGORY_PATTERN, message = CATEGORY_MESSAGE)
            String category,

            @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
            String colour,

            Integer seq,

            Boolean isOpen,

            Boolean isTerminal,

            Boolean isActive) {
    }

    /**
     * One cell of a matrix replace.
     *
     * <p><b>Absent and explicitly-null {@code fromStatus} are the same event
     * here, and this is the one place in the contract where that is correct.</b>
     * Everywhere else the distinction matters because a patch leaves a stored
     * value alone; this is a {@code PUT} of the whole matrix, so a row is a
     * complete statement and there is no stored value an omission could preserve.
     * Both mean "on creation".
     */
    record TransitionWrite(
            String fromStatus,

            @NotBlank(message = "toStatus is required")
            String toStatus,

            @NotBlank(message = "roleCode is required")
            String roleCode,

            Boolean requiresReason,

            Boolean requiresEffort) {
    }

    /**
     * <b>The complete set of allowed moves.</b> Anything not listed is
     * deactivated.
     *
     * <p>{@code @NotNull} on the list and not {@code @NotEmpty}: an empty array
     * is a well-formed statement of "no move is permitted", and it is refused by
     * the service with a 409 that explains what it would do, rather than by a 400
     * that reads like a malformed body. The distinction matters because the two
     * have different remedies — one is "fix your JSON", the other is "you are
     * about to lock every role out of raising a ticket".
     */
    record TransitionMatrixWrite(
            @NotNull(message = "transitions is required")
            List<@Valid TransitionWrite> transitions) {
    }
}
