package com.edunext.edutrack.api.feature.masters.projects;

import com.edunext.edutrack.api.feature.masters.ProjectRoles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * B-017 · the S-10 Team tab — "add resources + project role + allocation %".
 *
 * <h2>The rules that live here</h2>
 *
 * <ul>
 *   <li><b>A member must be a real, active resource.</b> 400, keyed on the
 *       field. Adding somebody who has left puts a name on a roster that
 *       Stream D's scanners and Stream C's assignment pickers both read.</li>
 *   <li><b>Somebody already on the team cannot be added again.</b> 409 — the
 *       request has nothing to do, and quietly overwriting their role and
 *       allocation from an "add" dialog is not what the click meant.</li>
 *   <li><b>Somebody who was removed comes back rather than conflicting.</b>
 *       Removal deactivates the row; treating the survivor as a conflict would
 *       make every removal permanent.</li>
 *   <li><b>A member holding open tickets on the project cannot be removed.</b>
 *       409, carrying the count and the reassignment route — the same refusal
 *       and the same exit B-014 built for resource deactivation.</li>
 * </ul>
 *
 * <p>None of the four is enforced by the schema. {@code uq_project_members} and
 * {@code ck_project_members_role} hold the shape; every rule about what the
 * organisation means is here.
 *
 * <h2>What is deliberately not a rule</h2>
 *
 * <p><b>The project manager is not forced onto their own team.</b> S-10 keeps
 * the two apart — {@code projects.manager_id} is who escalations reach and
 * {@code project_members} is who works on it, and the manager of six projects
 * is not on six teams in any sense the capacity report should count. Auto-adding
 * them would put an allocation nobody entered onto every project they own.
 *
 * <p><b>The project role is not checked against the person's global role.</b> A
 * Developer mapped as QA here is the case the column exists for (B-011), so
 * there is nothing to reconcile.
 *
 * <p><b>Allocations are not made to sum to 100.</b> The tab shows the total
 * across a resource's active memberships and says when it exceeds 100, because
 * over-allocation is a real state an organisation gets into and one it needs to
 * see rather than be prevented from recording. A server that refused it would
 * mean the true figure could not be written down anywhere.
 */
@Service
class ProjectMemberService {

    private final ProjectTeamRepository repository;

    ProjectMemberService(ProjectTeamRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * @return empty when there is no such project — the controller turns that
     *         into a 404, never a 403 (CONVENTIONS.md §7)
     */
    List<ProjectMemberDtos.TeamMember> roster(long projectId) {
        requireProject(projectId);
        return repository.roster(projectId);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Add, or bring back somebody who was removed.
     *
     * <p>The membership state is read before the write rather than inferred
     * from the upsert's result, because the two outcomes the caller cares about
     * — refused as a duplicate, or added — are not distinguishable afterwards:
     * {@code ON DUPLICATE KEY UPDATE} reports the same thing for a row it
     * inserted and a row it reactivated.
     */
    @Transactional
    ProjectMemberDtos.TeamMember add(long projectId, ProjectMemberDtos.TeamMemberWrite write) {
        requireProject(projectId);

        long userId = validateCandidate(write.userId());
        if (Boolean.TRUE.equals(repository.membershipState(projectId, userId).orElse(null))) {
            throw new AlreadyOnTeamException(userId);
        }

        repository.upsert(projectId, userId,
                ProjectRoles.normalise(write.projectRole()),
                write.allocationPct());

        return repository.findMember(projectId, userId).orElseThrow(
                () -> new IllegalStateException(
                        "membership " + projectId + "/" + userId + " vanished between write and read"));
    }

    /**
     * The inline edit. Omitted keeps, explicit null clears.
     *
     * <p>Read-modify-write inside one transaction, so an omitted field is
     * resolved against the row as it is now rather than as the browser last saw
     * it — which is what makes the absence of an {@code If-Match} here
     * survivable: two people editing different fields of one membership both
     * land, and only two people editing the <i>same</i> field race.
     *
     * @return empty when there is no live membership for this pair, which the
     *         controller turns into a 404
     */
    @Transactional
    Optional<ProjectMemberDtos.TeamMember> update(
            long projectId, long userId, ProjectMemberDtos.TeamMemberPatch patch) {

        requireProject(projectId);

        Optional<ProjectMemberDtos.TeamMember> found = repository.findMember(projectId, userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProjectMemberDtos.TeamMember current = found.get();

        String projectRole = patch.projectRole() == null
                ? current.projectRole()
                : ProjectRoles.normalise(patch.projectRole().orElse(null));

        Integer allocationPct = patch.allocationPct() == null
                ? current.allocationPct()
                : patch.allocationPct().orElse(null);

        repository.update(projectId, userId, projectRole, allocationPct);
        return repository.findMember(projectId, userId);
    }

    /**
     * Remove — deactivate, refused while they hold open work here.
     *
     * <p><b>Removing somebody who is not on the team succeeds.</b> It is a
     * setter: a client retrying after a dropped response has to converge, and
     * the mirror ordering — 404 for a non-member — would make the second click
     * of a double-click an error message about a thing that did happen. Same
     * call B-014 made for {@code UNCHANGED} on the resource status route.
     *
     * <p>The open-ticket check runs <b>before</b> the "are they even a member"
     * outcome is returned, so somebody already removed from the team who still
     * holds tickets does not read as cleanly gone. It cannot: {@code findMember}
     * only sees live rows, so a removed member has no count to check — which is
     * itself the argument for the pre-flight the tab does with
     * {@code openTicketCount} on the roster.
     */
    @Transactional
    void remove(long projectId, long userId) {
        requireProject(projectId);

        Optional<ProjectMemberDtos.TeamMember> found = repository.findMember(projectId, userId);
        if (found.isEmpty()) {
            return;
        }
        ProjectMemberDtos.TeamMember member = found.get();
        if (member.openTicketCount() > 0) {
            throw new OpenTicketsException(member.displayName(), member.openTicketCount());
        }
        repository.deactivate(projectId, userId);
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /**
     * 404 for an unknown project, and it is checked first on every operation.
     *
     * <p>Answering "that resource is deactivated" for a project that does not
     * exist would tell the caller about a resource on a project they cannot see
     * — and the ordering matters more than it looks, because this is the only
     * operation set in the feature where the path carries two ids.
     */
    private void requireProject(long projectId) {
        if (!repository.projectExists(projectId)) {
            throw new NoSuchProjectException();
        }
    }

    /**
     * The person must exist and be reachable.
     *
     * <p>Inactive is refused for {@link ProjectService#validateManager}'s
     * reason: a deactivated resource is somebody who has left, and putting them
     * on a team means the auto-assign rule, the assignment picker and every
     * capacity figure count somebody who will never pick the work up.
     *
     * <p>The <i>role</i> is not checked. Any resource may hold any project role,
     * which is the whole point of {@code role_in_project} — and a hardcoded role
     * set is what B-015 removed from {@code ResourceController}.
     */
    private long validateCandidate(Long userId) {
        ProjectTeamRepository.Candidate candidate = repository.findCandidate(userId)
                .orElseThrow(() -> new MemberValidationException("userId", "no such resource"));

        if (!candidate.isActive()) {
            throw new MemberValidationException("userId",
                    candidate.displayName() + " is deactivated and cannot be added to a project team");
        }
        return candidate.id();
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /** 404 — the project, not the membership. */
    static class NoSuchProjectException extends RuntimeException {
    }

    /** 400, keyed on the field, so the message lands on the picker. */
    static class MemberValidationException extends RuntimeException {

        private final String field;

        MemberValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return this.field;
        }
    }

    /**
     * 409 with its own {@code type}.
     *
     * <p>Distinct from the open-tickets refusal, which is also a 409 and is
     * nothing like it: this one is fixed by closing the dialog, that one by
     * reassigning work. B-016 split {@code duplicate} from
     * {@code immutable-project-code} on the same reasoning.
     */
    static class AlreadyOnTeamException extends RuntimeException {

        static final String FIELD = "userId";

        AlreadyOnTeamException(long userId) {
            super("that resource is already on this project's team");
            this.userId = userId;
        }

        private final long userId;

        long userId() {
            return this.userId;
        }
    }

    /** 409, carrying the count and the way out — the contract's {@code OpenTicketsProblem}. */
    static class OpenTicketsException extends RuntimeException {

        private final int openTicketCount;

        OpenTicketsException(String displayName, int openTicketCount) {
            super(displayName + " holds " + openTicketCount + " open ticket"
                    + (openTicketCount == 1 ? "" : "s") + " on this project. "
                    + "Reassign them before removing them from the team.");
            this.openTicketCount = openTicketCount;
        }

        int openTicketCount() {
            return this.openTicketCount;
        }
    }
}
