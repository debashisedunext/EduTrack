package com.edunext.edutrack.api.feature.masters.projects;

import com.edunext.edutrack.api.feature.masters.ProjectRoles;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * B-017 · S-10 Team tab wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request types is the source of truth for the field
 * rules, per the note on {@link ProjectDtos}. The rules that need a query — is
 * this a real resource, are they already on the team, do they hold open tickets
 * here — are in {@link ProjectMemberService}.
 */
final class ProjectMemberDtos {

    private ProjectMemberDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One roster row.
     *
     * <p>Named {@code TeamMember} rather than {@code ProjectMember}: the wire
     * shape is the contract's {@code ProjectMember}, but
     * {@link com.edunext.edutrack.domain.identity.ProjectMember} is the entity
     * over the same table and having both in scope in
     * {@link ProjectTeamRepository} would mean qualifying one of them at every
     * mention. The generated client is unaffected — the schema name comes from
     * the contract, not from this record.
     *
     * @param role         their <b>global</b> role, from {@code users.role_id}
     * @param projectRole  their role on this project; null means "same as their
     *                     global role", which is the column's null and the
     *                     common case
     * @param allocationPct null means <b>not stated</b>, never 100 — see
     *                     {@code V20260813_1810__project_member_allocation.sql}
     * @param isActive     the <b>resource's</b> status, not the membership's. A
     *                     deactivated membership is not returned at all; a
     *                     deactivated person still on the team is exactly what
     *                     the tab has to show, because that is what B-014's
     *                     reassignment flow exists to clear up
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TeamMember(
            long userId,
            String displayName,
            String email,
            String role,
            String projectRole,
            Integer allocationPct,
            boolean isActive,
            int openTicketCount,
            Instant addedAt) {
    }

    /**
     * No {@code meta}, and that is the signal.
     *
     * <p>CONVENTIONS.md §6: an unpaginated collection returns {@code data} with
     * no {@code meta}, which is how a client knows the list is complete. The
     * exemption is registered in {@code check-conventions.py} with its reason —
     * a project team is tens of people and the tab totals their allocations, so
     * it reads the whole set on every render regardless.
     */
    record TeamListResponse(List<TeamMember> data) {
    }

    record TeamMemberResponse(TeamMember data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Add one resource to the team.
     *
     * <p><b>{@code projectRole} is optional and {@code allocationPct} is
     * nullable</b>, which is the contract corrected rather than the contract
     * followed. Its first draft required {@code projectRole} and typed it as
     * {@code RoleCode} — an enum containing {@code ADMIN}, which
     * {@code ck_project_members_role} refuses, so a well-formed request could
     * only ever have arrived as a 500; and lacking {@code VIEWER}, which S-10
     * names and this screen must offer.
     */
    record TeamMemberWrite(
            @NotNull(message = "userId is required")
            @Min(value = 1, message = "userId must be a real id")
            Long userId,

            @Pattern(regexp = ProjectRoles.REGEX, message = ProjectRoles.MESSAGE)
            String projectRole,

            @Min(value = 0, message = "allocationPct cannot be negative")
            @Max(value = 100, message = "allocationPct cannot exceed 100")
            Integer allocationPct) {
    }

    /**
     * The inline edit. Omitted keeps, explicit null clears.
     *
     * <p>{@code Optional} rather than a boxed type, and the distinction is
     * load-bearing here in a way it was not on {@link ProjectDtos.ProjectPatch}:
     * both of these fields have a meaningful null. Clearing {@code projectRole}
     * is how a member goes back to "same as their global role" and clearing
     * {@code allocationPct} is how a figure entered by mistake becomes "not
     * stated" again — with boxed types both states would be write-once, settable
     * on the add and never reachable afterwards.
     *
     * <h2>Why this is a class and every other DTO here is a record</h2>
     *
     * <p><b>A record cannot express it.</b> Written as
     * {@code record TeamMemberPatch(Optional<String> projectRole, …)} it
     * compiles, reads better, and is wrong: Jackson binds a record through its
     * canonical constructor, and an <i>absent</i> {@code Optional} creator
     * property is filled with {@code Optional.empty()} — the same value an
     * explicit JSON null produces. Both cases collapse into "clear it", so
     * {@code PATCH {"allocationPct": 60}} would also wipe the member's project
     * role, and the response would look correct because it echoes what was
     * saved.
     *
     * <p>A POJO does not have the problem: an absent property means the setter
     * is never called and the field stays null. That is why
     * {@code ResourceDtos.ResourceWriteRequest} is one, and B-017 rediscovered
     * the reason the hard way. {@link ProjectMemberPatchTest} pins both
     * directions so the next person does not.
     */
    static final class TeamMemberPatch {

        private Optional<@Pattern(regexp = ProjectRoles.REGEX, message = ProjectRoles.MESSAGE)
                String> projectRole;

        private Optional<@Min(value = 0, message = "allocationPct cannot be negative")
                @Max(value = 100, message = "allocationPct cannot exceed 100")
                Integer> allocationPct;

        TeamMemberPatch() {
        }

        /** For the tests, which have no reason to go through Jackson to build one. */
        TeamMemberPatch(Optional<String> projectRole, Optional<Integer> allocationPct) {
            this.projectRole = projectRole;
            this.allocationPct = allocationPct;
        }

        public Optional<String> getProjectRole() {
            return projectRole;
        }

        public void setProjectRole(Optional<String> projectRole) {
            this.projectRole = projectRole;
        }

        public Optional<Integer> getAllocationPct() {
            return allocationPct;
        }

        public void setAllocationPct(Optional<Integer> allocationPct) {
            this.allocationPct = allocationPct;
        }

        /**
         * Accessors named for the record they replaced, so the service reads the
         * same either way and the swap did not ripple.
         */
        Optional<String> projectRole() {
            return projectRole;
        }

        Optional<Integer> allocationPct() {
            return allocationPct;
        }
    }
}
