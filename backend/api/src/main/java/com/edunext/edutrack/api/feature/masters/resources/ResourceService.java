package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * B-010 · the Resource Master's read model and its status writes (S-07).
 *
 * <p>Creating and editing a resource is B-011 and cycle detection is B-012.
 * This class deliberately holds neither: everything here either reads, or moves
 * one boolean.
 *
 * <p>B-014 added {@link #setStatus(long, boolean)} — the singular half of the
 * bulk route, sharing its decision rather than restating it. The wizard those
 * refusals send the caller to is <b>S-24, Stream C's C-063</b>; this stream owns
 * the refusal and the route back, not the reassignment itself.
 */
@Service
public class ResourceService {

    /** Matches {@code Limit} in the contract. */
    static final int MAX_LIMIT = 200;
    static final int DEFAULT_LIMIT = 50;

    /**
     * How many rows the export pulls per round trip.
     *
     * <p>Larger than a screen page because nobody is waiting on a first paint,
     * and small enough that the projects and counts for one batch stay a
     * bounded {@code IN} list. The export is not capped — it walks every
     * matching row — so this is a stride, not a limit.
     */
    static final int EXPORT_BATCH = 500;

    /**
     * Where the caller sends a blocked resource next — <b>the API operation, not
     * a browser route</b>.
     *
     * <p>B-014 keeps it that way on purpose. The screen that acts on this
     * refusal navigates somewhere, and the somewhere is a client route that
     * belongs to whichever client is asking; a server that emitted
     * {@code /tickets/bulk-reassign?fromUserId=…} would be shipping one web
     * app's routing table to every consumer of the API, including the ones with
     * no router at all. The React side owns its own URL, in
     * {@code features/masters/resources/reassignHandoff.ts}.
     *
     * <p>{@code POST /tickets/bulk-reassign} is Stream C's (C-063) and does not
     * exist yet. This constant names it anyway, because the refusal it decorates
     * is meaningless without saying what resolves it.
     *
     * <p><b>Widened from package-private by B-017</b>, which emits the same
     * {@code OpenTicketsProblem} when a team member holding open work is removed
     * from a project. The alternative was a second copy of the URL in
     * {@code masters.projects}, and two copies of a string that must agree is the
     * drift {@code masters.ProjectRoles} exists one package up to prevent. The
     * two features are both Stream B's and both under {@code masters}.
     */
    public static final String REASSIGN_URL = "/api/v1/tickets/bulk-reassign";

    private final ResourceRepository resources;
    private final RoleRepository roles;

    ResourceService(ResourceRepository resources, RoleRepository roles) {
        this.resources = resources;
        this.roles = roles;
    }

    // ------------------------------------------------------------------
    // Role codes — the ?role= filter's vocabulary
    // ------------------------------------------------------------------

    /**
     * The authoritative list of role codes, sorted so a 400 reads the same
     * twice running.
     *
     * <p>B-015 replaced a hardcoded set of six with a read of the table: role
     * codes are master data an Admin extends through S-09, so a compiled copy
     * starts drifting the moment that screen exists. The Role Master is in this
     * feature; the <em>repository</em> is Stream A's identity domain, which is
     * where every other consumer of {@code roles} reads from too.
     *
     * <p>A-037 moved the two calls here from {@code ResourceController}, which
     * held {@link RoleRepository} directly. Nothing about the query changed —
     * the point is only that a handler cannot query, because a handler that can
     * is a handler that can answer without whatever the service enforces next.
     */
    public List<String> knownRoleCodes() {
        return roles.findAll().stream().map(Role::getCode).sorted().toList();
    }

    /** Whether {@code code} — already upper-cased — names a real role. */
    public boolean roleExists(String code) {
        return roles.existsByCode(code);
    }

    // ------------------------------------------------------------------
    // The grid
    // ------------------------------------------------------------------

    /**
     * One page of the S-07 grid.
     *
     * <p>Asks the database for {@code limit + 1} rows and returns {@code limit}.
     * That extra row is how {@code hasMore} is known without a second query, and
     * without the off-by-one of "a full page probably means more" — which is
     * wrong exactly when the total is a multiple of the page size, and shows the
     * user a Next button leading to nothing.
     */
    @Transactional(readOnly = true)
    public ResourceDtos.ResourceListResponse list(ResourceFilter filter, String cursor, Integer limit) {
        int size = clampLimit(limit);
        List<ResourceRepository.ResourceRow> rows =
                resources.page(filter, ResourceCursor.decode(cursor), size + 1);

        boolean hasMore = rows.size() > size;
        List<ResourceRepository.ResourceRow> page = hasMore ? rows.subList(0, size) : rows;

        String nextCursor = null;
        if (hasMore) {
            ResourceRepository.ResourceRow last = page.get(page.size() - 1);
            nextCursor = new ResourceCursor(last.fullName(), last.id()).encode();
        }

        return new ResourceDtos.ResourceListResponse(
                hydrate(page),
                new ResourceDtos.Meta(nextCursor, hasMore, resources.count(filter)));
    }

    /**
     * Every matching resource, handed to {@code sink} in batches.
     *
     * <p>The export streams rather than materialising: a 5,000-person directory
     * held in a list, converted to DTOs, then written, is three copies of the
     * same data alive at once. Reusing the page query means the export and the
     * screen cannot disagree about what a filter means — the one thing a
     * "download what I'm looking at" button must get right.
     *
     * <p>Deliberately ignores the cursor and the limit. An export that stopped
     * at the current page would produce a file that looks complete and is not,
     * which is the failure mode nobody checks for.
     */
    @Transactional(readOnly = true)
    public void streamAll(ResourceFilter filter, Consumer<List<ResourceDtos.Resource>> sink) {
        ResourceCursor cursor = null;
        while (true) {
            List<ResourceRepository.ResourceRow> batch = resources.page(filter, cursor, EXPORT_BATCH);
            if (batch.isEmpty()) {
                return;
            }
            sink.accept(hydrate(batch));

            if (batch.size() < EXPORT_BATCH) {
                return;
            }
            ResourceRepository.ResourceRow last = batch.get(batch.size() - 1);
            cursor = new ResourceCursor(last.fullName(), last.id());
        }
    }

    // ------------------------------------------------------------------
    // Activate / deactivate — one resource, and a selection
    // ------------------------------------------------------------------

    /**
     * B-014 · sets {@code is_active} on one resource, for
     * {@code PATCH /users/{userId}/status}.
     *
     * <p><b>The same decision the bulk route makes</b>, translated into status
     * codes instead of an outcome code. That reuse is the point: the contract
     * has always said the two routes refuse the same thing for the same reason,
     * and the only way to keep that true a year from now is for there to be one
     * {@link #apply} rather than two implementations that agree today.
     *
     * <p><b>{@code UNCHANGED} answers 204, not 409.</b> Setting a flag to the
     * value it already holds succeeded — there is nothing for the caller to do
     * differently, and a client retrying after a dropped response must converge
     * rather than start reporting an error. It also matters for the specific row
     * this task exists to unblock: somebody already inactive who still holds
     * tickets, which is what a half-finished reassignment leaves behind, would
     * otherwise be refused forever by the guard and never settle.
     *
     * @throws ResourceWriteService.ResourceNotFoundException 404 — no such resource
     * @throws ResourceWriteService.OpenTicketsException      409 — reassign first
     */
    @Transactional
    public void setStatus(long userId, boolean isActive) {
        int openTickets = isActive
                ? 0
                : resources.openTicketCounts(List.of(userId)).getOrDefault(userId, 0);

        ResourceDtos.BulkStatusOutcome outcome = apply(userId, isActive, openTickets);
        switch (outcome.outcome()) {
            case NOT_FOUND -> throw new ResourceWriteService.ResourceNotFoundException(userId);
            case BLOCKED_OPEN_TICKETS ->
                    throw new ResourceWriteService.OpenTicketsException(userId, outcome.openTicketCount());
            case CHANGED, UNCHANGED -> {
                // 204 either way. See above.
            }
        }
    }

    /**
     * Sets {@code is_active} across a selection, reporting each resource's fate.
     *
     * <p><b>Deactivation is refused for anyone holding open tickets</b>, per
     * S-07 and the singular route's 409. It is refused per resource, not per
     * request: a selection of forty containing two such people should
     * deactivate thirty-eight and say clearly which two it did not.
     *
     * <p>Activation has no such guard — bringing somebody back cannot orphan
     * anything.
     *
     * <p>Duplicate ids in the request collapse before anything runs, so a grid
     * that sent the same row twice gets one outcome for it rather than a
     * {@code CHANGED} followed by a contradicting {@code UNCHANGED}.
     */
    @Transactional
    public ResourceDtos.BulkStatusData setStatus(ResourceDtos.BulkStatusRequest request) {
        boolean target = request.isActive();
        List<Long> ids = List.copyOf(new LinkedHashSet<>(request.userIds()));

        Map<Long, Integer> openTickets = target ? Map.of() : resources.openTicketCounts(ids);
        List<ResourceDtos.BulkStatusOutcome> results = new ArrayList<>(ids.size());

        for (Long id : ids) {
            results.add(apply(id, target, openTickets.getOrDefault(id, 0)));
        }
        return summarise(results);
    }

    private ResourceDtos.BulkStatusOutcome apply(long id, boolean target, int openTickets) {
        Optional<ResourceRepository.ResourceStatus> found = resources.findStatus(id);
        if (found.isEmpty()) {
            return new ResourceDtos.BulkStatusOutcome(
                    id, null, ResourceDtos.BulkStatusOutcomeCode.NOT_FOUND, null);
        }
        ResourceRepository.ResourceStatus resource = found.get();

        if (resource.isActive() == target) {
            return new ResourceDtos.BulkStatusOutcome(
                    id, resource.fullName(), ResourceDtos.BulkStatusOutcomeCode.UNCHANGED, null);
        }
        // Only deactivation can orphan work, and only a resource already active
        // can be deactivated — both of which the equality check above has
        // established by the time we get here.
        if (!target && openTickets > 0) {
            return new ResourceDtos.BulkStatusOutcome(
                    id, resource.fullName(),
                    ResourceDtos.BulkStatusOutcomeCode.BLOCKED_OPEN_TICKETS, openTickets);
        }

        resources.setActive(id, target);
        return new ResourceDtos.BulkStatusOutcome(
                id, resource.fullName(), ResourceDtos.BulkStatusOutcomeCode.CHANGED, null);
    }

    private static ResourceDtos.BulkStatusData summarise(List<ResourceDtos.BulkStatusOutcome> results) {
        int changed = 0;
        int unchanged = 0;
        int blocked = 0;
        int notFound = 0;
        for (ResourceDtos.BulkStatusOutcome result : results) {
            switch (result.outcome()) {
                case CHANGED -> changed++;
                case UNCHANGED -> unchanged++;
                case BLOCKED_OPEN_TICKETS -> blocked++;
                case NOT_FOUND -> notFound++;
            }
        }
        return new ResourceDtos.BulkStatusData(
                results, changed, unchanged, blocked, notFound,
                blocked > 0 ? REASSIGN_URL : null);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Attaches projects and open-ticket counts to a page of rows.
     *
     * <p>Two extra queries for the whole page, not two per row. That is the
     * entire reason the page query does not try to fetch them itself.
     */
    private List<ResourceDtos.Resource> hydrate(List<ResourceRepository.ResourceRow> rows) {
        List<Long> ids = rows.stream().map(ResourceRepository.ResourceRow::id).toList();
        Map<Long, List<ResourceDtos.ProjectRef>> projects = resources.projectsFor(ids);
        Map<Long, Integer> openTickets = resources.openTicketCounts(ids);

        return rows.stream().map(row -> {
            List<ResourceDtos.ProjectRef> memberships = projects.getOrDefault(row.id(), List.of());
            return new ResourceDtos.Resource(
                    row.id(),
                    row.fullName(),
                    row.roleCode(),
                    row.username(),
                    row.email(),
                    row.empCode(),
                    row.department(),
                    row.designation(),
                    row.reportingManager(),
                    memberships.stream().map(ResourceDtos.ProjectRef::id).toList(),
                    memberships,
                    row.isActive(),
                    openTickets.getOrDefault(row.id(), 0),
                    row.lastLoginAt(),
                    row.createdAt());
        }).toList();
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }
}
