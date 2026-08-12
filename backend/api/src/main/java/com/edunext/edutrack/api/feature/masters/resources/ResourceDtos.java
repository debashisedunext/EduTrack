package com.edunext.edutrack.api.feature.masters.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * B-010 · the wire shapes for S-07, matching {@code contracts/openapi.yaml}
 * §users.
 *
 * <p>Grouped in one file for the reason {@code CalendarDtos} gives: they are one
 * contract read together.
 *
 * <p><b>{@code @JsonInclude(NON_NULL)} on every response record is load-bearing,
 * not tidiness.</b> The generated Zod types most of these fields
 * {@code .optional()} rather than {@code .nullish()} — {@code role},
 * {@code username}, {@code isActive}, {@code projects} among them — so an
 * explicit {@code null} on the wire is a value the frontend's own schema
 * rejects, and the grid would fail to parse a response the backend considers
 * perfectly valid. Omitting the key is what {@code .optional()} means.
 */
public final class ResourceDtos {

    private ResourceDtos() {
    }

    // ------------------------------------------------------------------
    // The grid
    // ------------------------------------------------------------------

    /**
     * {@code ProjectRef} — the label half of a project.
     *
     * <p>Names, not ids. S-07's Projects column would otherwise need a second
     * request per row, or a client-side join against a project list the grid has
     * no other reason to fetch.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectRef(
            long id,
            String projectCode,
            String name,
            String colourTag) {
    }

    /**
     * {@code UserRef} — enough to name and link a person, no more.
     *
     * <p>Used here for {@code reportingManager}. {@code avatarUrl} and
     * {@code handle} are left null and dropped by {@code NON_NULL}: neither is
     * stored on {@code users} today, and inventing a value would be worse than
     * omitting an optional field.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRef(
            long id,
            String displayName,
            String role) {
    }

    /**
     * {@code User} — one row of the S-07 grid.
     *
     * <p>Carries every column blueprint §7.4 lists: emp code, name, email, role,
     * department, reporting manager, projects, status, last login. {@code id} and
     * {@code displayName} come from the contract's {@code UserRef} base, which is
     * why they lead.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(
            long id,
            String displayName,
            String role,
            String username,
            String email,
            String employeeCode,
            String department,
            String designation,
            UserRef reportingManager,
            List<Long> projectIds,
            List<ProjectRef> projects,
            Boolean isActive,
            Integer openTicketCount,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    /** {@code Meta} — cursor pagination, per CONVENTIONS.md §6. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String nextCursor, boolean hasMore, Long totalCount) {
    }

    public record ResourceListResponse(List<Resource> data, Meta meta) {
    }

    // ------------------------------------------------------------------
    // B-011 · the S-08 form
    // ------------------------------------------------------------------

    /**
     * {@code ProjectAssignment} — one {@code project_members} row, ids only.
     *
     * <p>The project's name and code are already on {@link Resource#projects()};
     * repeating them here would give the response two places a rename has to
     * reach.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectAssignment(
            @NotNull(message = "projectId is required")
            Long projectId,

            @Pattern(regexp = "PM|DEVELOPER|SUPPORT|QA|DEPLOYMENT|VIEWER",
                    message = "roleInProject must be PM, DEVELOPER, SUPPORT, QA, DEPLOYMENT or VIEWER")
            String roleInProject) {
    }

    /**
     * {@code UserDetail} — one resource in full, for the S-08 form.
     *
     * <p>Composes {@link Resource} rather than extending it, because a Java
     * record cannot extend anything. The contract expresses the same
     * relationship as {@code allOf}, and {@code @JsonUnwrapped} would flatten
     * this into exactly that shape on the wire — but it does not compose with
     * {@code NON_NULL} on the inner record, so the fields are restated. The
     * duplication is held by {@code ResourceDetailShapeTest}, which fails if
     * {@code Resource} grows a component this does not carry.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceDetail(
            long id,
            String displayName,
            String role,
            String username,
            String email,
            String employeeCode,
            String department,
            String designation,
            UserRef reportingManager,
            List<Long> projectIds,
            List<ProjectRef> projects,
            Boolean isActive,
            Integer openTicketCount,
            Instant lastLoginAt,
            Instant createdAt,

            // ── the S-08-only half ────────────────────────────────────────
            String mobile,
            String avatarUrl,
            LocalDate dateOfJoining,
            String location,
            String timezone,
            BigDecimal dailyCapacityHrs,
            List<Integer> weeklyOff,
            List<String> skills,
            List<ProjectAssignment> projectAssignments,
            Boolean mustChangePassword) {

        /**
         * The value the {@code ETag} is derived from.
         *
         * <p>Content, not {@code updated_at} — the reason
         * {@code CalendarController} gives: a save that rewrites identical
         * values would otherwise invalidate a concurrent edit that conflicts
         * with nothing.
         *
         * <p><b>Three components are excluded, and they are the three that move
         * without anybody editing this resource.</b> {@code openTicketCount}
         * changes when somebody else closes one of their tickets;
         * {@code lastLoginAt} and {@code mustChangePassword} both change the
         * moment the resource logs in. Any of the three inside the tag would
         * silently invalidate an admin's open form and produce a 412 naming a
         * conflict that does not exist — and the login case is the likeliest of
         * all, because creating somebody and then editing them is exactly when
         * they are being told to log in.
         *
         * <p>The rule is: the tag covers what the {@code PATCH} can change, and
         * no more. Everything left in it is a field the form writes.
         */
        String etag() {
            ResourceDetail stable = new ResourceDetail(
                    id, displayName, role, username, email, employeeCode,
                    department, designation, reportingManager, projectIds, projects,
                    isActive, null, null, createdAt,
                    mobile, avatarUrl, dateOfJoining, location, timezone,
                    // BigDecimal.hashCode distinguishes 8 from 8.00; the column is
                    // DECIMAL(4,2) so a re-read is always scaled, but a value
                    // constructed in a test is not, and an ETag that depends on
                    // trailing zeros is one that changes for no reason.
                    dailyCapacityHrs == null ? null : dailyCapacityHrs.stripTrailingZeros(),
                    weeklyOff, skills, projectAssignments, null);
            return Integer.toHexString(stable.hashCode());
        }
    }

    public record ResourceDetailResponse(ResourceDetail data) {
    }

    /** {@code POST /users} only — see {@link CreatedMeta}. */
    public record ResourceCreatedResponse(ResourceDetail data, CreatedMeta meta) {
    }

    /**
     * The one place the generated password is ever readable.
     *
     * <p>A separate response type rather than a nullable field on
     * {@link ResourceDetail}, so that no read operation has a type that
     * <i>could</i> carry a credential. "Not populated by the list query" is a
     * property of today's SQL; "not on the type the list returns" is a property
     * of the code.
     */
    public record CreatedMeta(String temporaryPassword) {
    }

    /**
     * The S-08 form as one body, for both {@code POST} and {@code PATCH}.
     *
     * <h2>Why the optional fields are {@link Optional}, and the required ones
     * are not</h2>
     *
     * <p>A {@code PATCH} has to distinguish three states per key, and plain
     * nullable fields carry only two. {@code Optional} gives the third: the
     * field stays {@code null} when the key is absent, and Jackson's
     * {@code Jdk8Module} deserialises an explicit JSON {@code null} to
     * {@link Optional#empty()}. So {@code null} means <i>leave it alone</i> and
     * {@code empty} means <i>clear it</i> — which for {@code reportingManagerId}
     * is the difference between "I am not editing their manager" and "their
     * manager has left, detach them".
     *
     * <p>The five required fields are plain, because they map to {@code NOT NULL}
     * columns and have no third state to model. S-08 marks all five with an
     * asterisk.
     *
     * <p><b>This is a deliberate departure from B-006's MapStruct default</b>,
     * which ignores null source properties so that "a {@code PATCH} cannot blank
     * a field it never sent". That strategy gets the common case right and makes
     * clearing a nullable field impossible — there is no way to detach a manager
     * under it. This feature writes through {@code JdbcClient} rather than a
     * mapper, so it is not bound by that config; the intent is the same and the
     * expressiveness is strictly greater.
     *
     * <p>There is no password field on either verb. S-08 auto-generates it, and
     * an admin who could set someone else's password directly would make
     * {@code mustChangePassword} mean nothing.
     */
    public static final class ResourceWriteRequest {

        // ── required · Personal and Access ────────────────────────────────
        @NotBlank(message = "full name is required")
        @Size(max = 120)
        private String displayName;

        @NotBlank(message = "employee code is required")
        @Size(max = 20)
        private String employeeCode;

        @NotBlank(message = "email is required")
        @Email(message = "that is not a valid email address")
        @Size(max = 150)
        private String email;

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 50)
        private String username;

        @NotBlank(message = "role is required")
        @Pattern(regexp = "ADMIN|PM|DEVELOPER|QA|DEPLOYMENT|SUPPORT",
                message = "role must be ADMIN, PM, DEVELOPER, QA, DEPLOYMENT or SUPPORT")
        private String role;

        // ── optional · Personal ───────────────────────────────────────────
        private Optional<@Size(max = 20)
                @Pattern(regexp = "^[0-9+][0-9 ()-]{5,19}$",
                        message = "mobile must be 6-20 characters of digits, spaces, brackets or dashes")
                String> mobile;

        private Optional<@Size(max = 500) String> avatarUrl;

        private Optional<LocalDate> dateOfJoining;

        // ── optional · Access ─────────────────────────────────────────────
        /**
         * Never cleared — the column is {@code NOT NULL}. An explicit null is a
         * 400 rather than a silent default, because "deactivate" and "I forgot
         * to send this" must not be the same request.
         */
        private Optional<@NotNull(message = "isActive cannot be null; omit it to leave the status alone")
                Boolean> isActive;

        // ── optional · Org ────────────────────────────────────────────────
        private Optional<@Size(max = 80) String> department;

        private Optional<@Size(max = 80) String> designation;

        private Optional<@Min(value = 1, message = "reportingManagerId must be a real id") Long> reportingManagerId;

        private Optional<@Size(max = 120) String> location;

        private Optional<@NotBlank(message = "timezone cannot be blank; omit it to leave it alone")
                @Size(max = 50) String> timezone;

        // ── optional · Work ───────────────────────────────────────────────
        private Optional<@NotNull(message = "dailyCapacityHrs cannot be null; omit it to leave it alone")
                @DecimalMin(value = "0.5", message = "daily capacity must be at least half an hour")
                @DecimalMax(value = "24.0", message = "a day has 24 hours")
                BigDecimal> dailyCapacityHrs;

        /**
         * ISO-8601 day numbers, 1=Mon … 7=Sun.
         *
         * <p>{@code empty} — an explicit JSON null — means "inherit the org
         * working week". An empty <i>list</i> is the different answer "this
         * person has no weekly off", which a support rota is a real reason to
         * want. B-023's note records what one extra day-numbering convention
         * cost last time, which is why {@code @Min(1)} is here and not merely in
         * the database.
         */
        private Optional<List<@Min(value = 1, message = "days are ISO 1=Mon … 7=Sun; 0 is not a day")
                @Max(value = 7, message = "days are ISO 1=Mon … 7=Sun")
                Integer>> weeklyOff;

        private Optional<@Size(max = 30, message = "30 skills is plenty")
                List<@NotBlank @Size(max = 60) String>> skills;

        // ── optional · Projects ───────────────────────────────────────────
        /**
         * The complete membership set when present, not a delta. Absent leaves
         * memberships alone.
         */
        private Optional<@Size(max = 100) List<@Valid @NotNull ProjectAssignment>> projects;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getEmployeeCode() {
            return employeeCode;
        }

        public void setEmployeeCode(String employeeCode) {
            this.employeeCode = employeeCode;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public Optional<String> getMobile() {
            return mobile;
        }

        public void setMobile(Optional<String> mobile) {
            this.mobile = mobile;
        }

        public Optional<String> getAvatarUrl() {
            return avatarUrl;
        }

        public void setAvatarUrl(Optional<String> avatarUrl) {
            this.avatarUrl = avatarUrl;
        }

        public Optional<LocalDate> getDateOfJoining() {
            return dateOfJoining;
        }

        public void setDateOfJoining(Optional<LocalDate> dateOfJoining) {
            this.dateOfJoining = dateOfJoining;
        }

        public Optional<Boolean> getIsActive() {
            return isActive;
        }

        public void setIsActive(Optional<Boolean> isActive) {
            this.isActive = isActive;
        }

        public Optional<String> getDepartment() {
            return department;
        }

        public void setDepartment(Optional<String> department) {
            this.department = department;
        }

        public Optional<String> getDesignation() {
            return designation;
        }

        public void setDesignation(Optional<String> designation) {
            this.designation = designation;
        }

        public Optional<Long> getReportingManagerId() {
            return reportingManagerId;
        }

        public void setReportingManagerId(Optional<Long> reportingManagerId) {
            this.reportingManagerId = reportingManagerId;
        }

        public Optional<String> getLocation() {
            return location;
        }

        public void setLocation(Optional<String> location) {
            this.location = location;
        }

        public Optional<String> getTimezone() {
            return timezone;
        }

        public void setTimezone(Optional<String> timezone) {
            this.timezone = timezone;
        }

        public Optional<BigDecimal> getDailyCapacityHrs() {
            return dailyCapacityHrs;
        }

        public void setDailyCapacityHrs(Optional<BigDecimal> dailyCapacityHrs) {
            this.dailyCapacityHrs = dailyCapacityHrs;
        }

        public Optional<List<Integer>> getWeeklyOff() {
            return weeklyOff;
        }

        public void setWeeklyOff(Optional<List<Integer>> weeklyOff) {
            this.weeklyOff = weeklyOff;
        }

        public Optional<List<String>> getSkills() {
            return skills;
        }

        public void setSkills(Optional<List<String>> skills) {
            this.skills = skills;
        }

        public Optional<List<ProjectAssignment>> getProjects() {
            return projects;
        }

        public void setProjects(Optional<List<ProjectAssignment>> projects) {
            this.projects = projects;
        }
    }

    // ------------------------------------------------------------------
    // Activate / deactivate — one resource, and a selection
    // ------------------------------------------------------------------

    /**
     * B-014 · the body of {@code PATCH /users/{userId}/status}.
     *
     * <p>Same two fields as {@link BulkStatusRequest} minus the ids, and
     * {@code isActive} is boxed for the same reason: an omitted flag must be a
     * 400 naming the field, not a silent {@code false} that deactivates
     * somebody.
     *
     * <p><b>{@code reason} is accepted, validated and not persisted yet</b>, and
     * that is deliberate rather than overlooked. The place it belongs is
     * {@code audit_logs} — the table and {@code domain.audit.AuditLog} both
     * exist — but nothing in this repository writes a row to it, and
     * {@code actor_id} wants a principal that {@code dev-noauth} does not
     * supply. Introducing the first writer to a shared audit table from a
     * masters branch, with a null actor, would make the audit trail's first
     * entries the ones nobody can attribute. Dropping the field from the
     * contract instead would mean re-adding it later as a breaking change to
     * every client that had learned to omit it. <b>Flagged for Stream A</b>
     * with A-016; this route starts recording the moment there is a writer and
     * an actor.
     */
    public record StatusRequest(
            @NotNull(message = "say which way to set it")
            Boolean isActive,

            @Size(max = 500)
            String reason) {
    }

    /**
     * {@code BulkUserStatusRequest}.
     *
     * <p>{@code isActive} is a boxed {@link Boolean} so that omitting it is a 400
     * naming the field rather than a silent {@code false} — which would
     * deactivate the whole selection on a malformed request.
     */
    public record BulkStatusRequest(
            @NotEmpty(message = "select at least one resource")
            @Size(max = 200, message = "a selection is made on a page; 200 is the page maximum")
            List<@NotNull Long> userIds,

            @NotNull(message = "say which way to set them")
            Boolean isActive,

            @Size(max = 500)
            String reason) {
    }

    /**
     * What happened to one resource.
     *
     * <p>Four outcomes rather than a boolean because the caller renders all four
     * differently, and collapsing {@code UNCHANGED} into {@code CHANGED} would
     * make the summary line overstate the work done.
     */
    public enum BulkStatusOutcomeCode {

        /** The flag moved. */
        CHANGED,

        /** Already in the requested state. Not an error, and not a change. */
        UNCHANGED,

        /**
         * Holds open tickets and was being deactivated. Left active — the S-24
         * reassignment wizard (Stream C's C-063) runs first, through
         * {@code POST /tickets/bulk-reassign}, and then
         * {@code PATCH /users/{userId}/status} finishes the job.
         */
        BLOCKED_OPEN_TICKETS,

        /** No such resource. Deleted between the grid rendering and the click. */
        NOT_FOUND
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkStatusOutcome(
            long userId,
            String displayName,
            BulkStatusOutcomeCode outcome,
            Integer openTicketCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkStatusData(
            List<BulkStatusOutcome> results,
            int changed,
            int unchanged,
            int blocked,
            int notFound,
            String reassignUrl) {
    }

    public record BulkStatusResponse(BulkStatusData data) {
    }
}
