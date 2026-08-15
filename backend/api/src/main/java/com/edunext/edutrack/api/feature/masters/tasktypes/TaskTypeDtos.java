package com.edunext.edutrack.api.feature.masters.tasktypes;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * B-020 · S-11 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request records is the single source of truth for
 * the field rules — the contract's {@code pattern}/{@code maxLength} and these
 * annotations describe the same constraint, and only one of them can be the one
 * that actually runs.
 */
final class TaskTypeDtos {

    private TaskTypeDtos() {
    }

    /** The colour rule, stated once and shared by both write shapes. */
    static final String COLOUR_PATTERN = "^#[0-9A-Fa-f]{6}$";

    static final String COLOUR_MESSAGE =
            "colour must be a #RRGGBB hex token from blueprint §12.1";

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * A task type as the S-11 grid renders it.
     *
     * @param ticketCount tickets currently carrying the type — the figure that
     *                    makes deactivating an informed decision rather than one
     *                    whose size is discovered afterwards, the same call
     *                    B-015 made with {@code userCount} on a role
     */
    record TaskTypeView(
            int id,
            String code,
            String name,
            String icon,
            String colour,
            String defaultLevel,
            BigDecimal defaultSlaHrs,
            short seq,
            boolean isActive,
            long ticketCount) {
    }

    record TaskTypeListResponse(List<TaskTypeView> data) {
    }

    record TaskTypeResponse(TaskTypeView data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code defaultLevel} is required here and nullable on the column.
     *
     * <p>The eleven seeded rows all have one, and a type without one silently
     * stops pre-filling the create form's level — blueprint §4B.1's "pre-filled
     * from the task type master" quietly not happening, which looks like a bug
     * in the form rather than a gap in the master. Nothing stops a future
     * migration storing null; nothing this screen creates will.
     *
     * <p>{@code seq} is optional and an omitted one sorts the new type to the
     * end. Zero is a legitimate value — first in the picker — so it cannot be
     * the sentinel for "not stated", which is why it is a boxed
     * {@link Integer}.
     */
    record TaskTypeWrite(
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,39}$",
                    message = "code must start with a letter and use only letters, digits and underscores")
            @Size(min = 2, max = 40, message = "code must be 2-40 characters")
            String code,

            @NotBlank(message = "name is required")
            @Size(max = 80, message = "name must be at most 80 characters")
            String name,

            @Size(max = 30, message = "icon must be at most 30 characters")
            String icon,

            @NotBlank(message = "colour is required")
            @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
            String colour,

            @NotBlank(message = "defaultLevel is required")
            String defaultLevel,

            @DecimalMin(value = "0", message = "defaultSlaHrs cannot be negative")
            BigDecimal defaultSlaHrs,

            Integer seq,

            Boolean isActive) {
    }

    /**
     * Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>{@code code} is here only so that sending a different one can be
     * refused.</b> Leaving it off the type means Jackson discards it silently
     * and a caller who believed they had renamed the code is told the save
     * succeeded — {@code RolePatch} carries {@code code} for exactly this
     * reason and {@code ProjectPatchRequest} carries {@code projectCode} for
     * the mirror of it.
     *
     * <p><b>A POJO, not a record, and B-017 wrote down why.</b> Two of these
     * fields are genuinely clearable — {@code icon} and {@code defaultSlaHrs},
     * both nullable columns — so "absent" and "explicitly null" have to mean
     * different things. Jackson binds a record through its canonical
     * constructor and fills an absent {@code Optional} creator property with
     * {@code Optional.empty()}, which is exactly the value an explicit null
     * produces; both would collapse into "clear it", and
     * {@code PATCH {"name": "…"}} would silently wipe the type's icon and its
     * default SLA while echoing back a response that looked correct.
     * {@link TaskTypePatchTest} pins both directions.
     *
     * <p>The other five stay plain and nullable, where null unambiguously means
     * "leave alone": {@code code} is refused rather than applied, {@code seq}
     * and {@code isActive} are {@code NOT NULL} columns, and {@code name},
     * {@code colour} and {@code defaultLevel} are values this screen never
     * offers to clear — a type with no name or no colour is a hole in every
     * picker that renders it.
     */
    static final class TaskTypePatch {

        private String code;

        @Size(min = 1, max = 80, message = "name must be 1-80 characters")
        private String name;

        private Optional<@Size(max = 30, message = "icon must be at most 30 characters")
                String> icon;

        @Pattern(regexp = COLOUR_PATTERN, message = COLOUR_MESSAGE)
        private String colour;

        private String defaultLevel;

        private Optional<@DecimalMin(value = "0", message = "defaultSlaHrs cannot be negative")
                BigDecimal> defaultSlaHrs;

        private Integer seq;

        private Boolean isActive;

        TaskTypePatch() {
        }

        /** For the tests, which have no reason to go through Jackson to build one. */
        TaskTypePatch(String code, String name, Optional<String> icon, String colour,
                      String defaultLevel, Optional<BigDecimal> defaultSlaHrs,
                      Integer seq, Boolean isActive) {
            this.code = code;
            this.name = name;
            this.icon = icon;
            this.colour = colour;
            this.defaultLevel = defaultLevel;
            this.defaultSlaHrs = defaultSlaHrs;
            this.seq = seq;
            this.isActive = isActive;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Optional<String> getIcon() {
            return icon;
        }

        public void setIcon(Optional<String> icon) {
            this.icon = icon;
        }

        public String getColour() {
            return colour;
        }

        public void setColour(String colour) {
            this.colour = colour;
        }

        public String getDefaultLevel() {
            return defaultLevel;
        }

        public void setDefaultLevel(String defaultLevel) {
            this.defaultLevel = defaultLevel;
        }

        public Optional<BigDecimal> getDefaultSlaHrs() {
            return defaultSlaHrs;
        }

        public void setDefaultSlaHrs(Optional<BigDecimal> defaultSlaHrs) {
            this.defaultSlaHrs = defaultSlaHrs;
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
        String code() {
            return code;
        }

        String name() {
            return name;
        }

        Optional<String> icon() {
            return icon;
        }

        String colour() {
            return colour;
        }

        String defaultLevel() {
            return defaultLevel;
        }

        Optional<BigDecimal> defaultSlaHrs() {
            return defaultSlaHrs;
        }

        Integer seq() {
            return seq;
        }

        Boolean isActive() {
            return isActive;
        }
    }
}
