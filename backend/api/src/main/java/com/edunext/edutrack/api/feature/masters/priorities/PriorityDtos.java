package com.edunext.edutrack.api.feature.masters.priorities;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * B-021 · S-12 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request types is the single source of truth for the
 * field rules — the contract's {@code pattern}/{@code maxLength} and these
 * annotations describe the same constraint, and only one of them is the one that
 * actually runs. Same call {@code TaskTypeDtos} makes one package over.
 *
 * <p><b>The wire calls the code {@code level} and the column calls it
 * {@code code}.</b> That mismatch is deliberate and predates this task: the
 * contract has said {@code Priority.level} since D-001, and Stream C's
 * {@code CreateTicketPage} and {@code TicketListPage} both read {@code p.level}
 * today. Renaming the property to match the column would break two shipped
 * screens to make one mapper shorter.
 */
final class PriorityDtos {

    private PriorityDtos() {
    }

    /** The colour rule, stated once and shared by both write shapes. */
    static final String COLOUR_PATTERN = "^#[0-9A-Fa-f]{6}$";

    static final String COLOUR_MESSAGE =
            "colour must be a #RRGGBB token from blueprint §12.1's level chips";

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * A level as the S-12 grid renders it.
     *
     * <p><b>Three counts, and they are not three shades of the same number.</b>
     * Each one is a different consequence of retiring the level, and two of them
     * behave differently at the refusal:
     *
     * <ul>
     *   <li>{@code ticketCount} — tickets at this level. Never blocks. They keep
     *       the level and go on rendering it, which is the entire reason
     *       {@code tickets.level} is a {@code VARCHAR} holding the code rather
     *       than a foreign key.</li>
     *   <li>{@code taskTypeCount} — task types defaulting to it. <b>Blocks</b>,
     *       because {@code TaskTypeService.normaliseLevel} refuses a retired
     *       level as a {@code defaultLevel}: retiring would leave those types
     *       unsaveable on their own screen, a broken state created from a
     *       different screen.</li>
     *   <li>{@code slaPolicyCount} — {@code sla_policies} rows written at this
     *       level, across every project. Never blocks, and is the count most
     *       likely to surprise: retiring drops the level's whole column out of
     *       {@code SlaMatrixService}'s grid, so those rows stop resolving
     *       without being deleted.</li>
     * </ul>
     *
     * @param level the {@code code} column — see this class's javadoc for why
     *              the wire name differs
     */
    record PriorityView(
            int id,
            String level,
            String name,
            String colour,
            BigDecimal defaultSlaHrs,
            boolean autoEscalates,
            short seq,
            boolean isActive,
            long ticketCount,
            int taskTypeCount,
            int slaPolicyCount) {
    }

    record PriorityListResponse(List<PriorityView> data) {
    }

    record PriorityResponse(PriorityView data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code colour} is required here and nullable on the column.
     *
     * <p>The four seeded rows all carry one, and §12.1 pins their hexes exactly.
     * A level with no colour is a hole in the level picker, in the ticket grid's
     * chip and in the Priority Split chart at once — three places that each
     * render something wrong rather than one that renders nothing.
     *
     * <p>{@code seq} is optional and an omitted one sorts the new level to the
     * end. Zero is legitimate — least severe, first in the picker — so it cannot
     * be the sentinel for "not stated", which is why it is a boxed
     * {@link Integer}. {@code TaskTypeWrite} makes the same call for the same
     * reason.
     */
    record PriorityWrite(
            @NotBlank(message = "level is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,9}$",
                    message = "level must start with a letter and use only letters, digits and underscores")
            @Size(min = 2, max = 10, message = "level must be 2-10 characters")
            String level,

            @NotBlank(message = "name is required")
            @Size(max = 40, message = "name must be at most 40 characters")
            String name,

            @NotBlank(message = "colour is required")
            @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
            String colour,

            @DecimalMin(value = "0", message = "defaultSlaHrs cannot be negative")
            BigDecimal defaultSlaHrs,

            Boolean autoEscalates,

            Integer seq,

            Boolean isActive) {
    }

    /**
     * Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>{@code level} is here only so that sending a different one can be
     * refused.</b> Leaving it off the type means Jackson discards it silently
     * and a caller who believed they had renamed the code is told the save
     * succeeded — {@code TaskTypePatch} carries {@code code} for exactly this
     * reason, and here the stake is higher: {@code tickets.level} stores this
     * string and is not a foreign key, so a rename does not cascade, it orphans.
     *
     * <p><b>A POJO, not a record, and B-017 wrote down why.</b> One field is
     * genuinely clearable — {@code defaultSlaHrs}, a nullable column whose null
     * means "this level contributes no rung 4" — so "absent" and "explicitly
     * null" have to mean different things. Jackson binds a record through its
     * canonical constructor and fills an absent {@code Optional} creator
     * property with {@code Optional.empty()}, the same value an explicit null
     * produces; both would collapse into "clear it", and a
     * {@code PATCH {"name": "…"}} would silently drop the level out of the §6
     * ladder while echoing back a response that looked correct.
     *
     * <p>The other five stay plain and nullable, where null unambiguously means
     * "leave alone": {@code level} is refused rather than applied; {@code seq},
     * {@code autoEscalates} and {@code isActive} are {@code NOT NULL} columns;
     * and {@code name} and {@code colour} are values this screen never offers to
     * clear.
     */
    static final class PriorityPatch {

        private String level;

        @Size(min = 1, max = 40, message = "name must be 1-40 characters")
        private String name;

        @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
        private String colour;

        private Optional<@DecimalMin(value = "0", message = "defaultSlaHrs cannot be negative")
                BigDecimal> defaultSlaHrs;

        private Boolean autoEscalates;

        private Integer seq;

        private Boolean isActive;

        PriorityPatch() {
        }

        /** For the tests, which have no reason to go through Jackson to build one. */
        PriorityPatch(String level, String name, String colour,
                      Optional<BigDecimal> defaultSlaHrs, Boolean autoEscalates,
                      Integer seq, Boolean isActive) {
            this.level = level;
            this.name = name;
            this.colour = colour;
            this.defaultSlaHrs = defaultSlaHrs;
            this.autoEscalates = autoEscalates;
            this.seq = seq;
            this.isActive = isActive;
        }

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getColour() {
            return colour;
        }

        public void setColour(String colour) {
            this.colour = colour;
        }

        public Optional<BigDecimal> getDefaultSlaHrs() {
            return defaultSlaHrs;
        }

        public void setDefaultSlaHrs(Optional<BigDecimal> defaultSlaHrs) {
            this.defaultSlaHrs = defaultSlaHrs;
        }

        public Boolean getAutoEscalates() {
            return autoEscalates;
        }

        public void setAutoEscalates(Boolean autoEscalates) {
            this.autoEscalates = autoEscalates;
        }

        public Integer getSeq() {
            return seq;
        }

        public void setSeq(Integer seq) {
            this.seq = seq;
        }

        public Boolean getIsActive() {
            return isActive;
        }

        public void setIsActive(Boolean isActive) {
            this.isActive = isActive;
        }

        /**
         * Accessors named the way the rest of the feature's DTOs read, so the
         * service does not have to know this one is a POJO.
         */
        String level() {
            return level;
        }

        String name() {
            return name;
        }

        String colour() {
            return colour;
        }

        Optional<BigDecimal> defaultSlaHrs() {
            return defaultSlaHrs;
        }

        Boolean autoEscalates() {
            return autoEscalates;
        }

        Integer seq() {
            return seq;
        }

        Boolean isActive() {
            return isActive;
        }
    }
}
