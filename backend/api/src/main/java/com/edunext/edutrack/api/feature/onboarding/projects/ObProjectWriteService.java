package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyInstantiationService;
import com.edunext.edutrack.api.feature.onboarding.prereqs.ObClientPrereqService;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectCreateRequest;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectDetail;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectUpdateRequest;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import com.edunext.edutrack.domain.onboarding.ObProject;
import com.edunext.edutrack.domain.onboarding.ObProjectRepository;
import com.edunext.edutrack.domain.onboarding.ObProjectStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The New Project form's create, and the project header's edit.
 *
 * <h2>Create is five writes in one transaction, in this order</h2>
 *
 * <ol>
 *   <li><b>The purchase row</b> ({@link ObProjectPurchaseRepository}), because
 *       {@code PurchasedProductAccess} refuses to instantiate a journey without
 *       one.</li>
 *   <li><b>The project</b>, which the journeys need an id from.</li>
 *   <li><b>The prerequisite checklist</b>, if this client has none yet. It is
 *       client-level and stays that way — one checklist per client, clearing it
 *       opens every project's journeys — so a client's second project finds one
 *       already there and adds nothing.</li>
 *   <li><b>One journey per checked Module Service</b>, through
 *       {@link ObJourneyInstantiationService#instantiateSelected}.</li>
 * </ol>
 *
 * <p>One transaction, so the states nobody can act on — a project with no
 * journeys, journeys with no purchase, a locked gate with no checklist behind it
 * — are unreachable rather than merely unlikely.
 *
 * <h2>Why a project cannot be created without a published prerequisite master</h2>
 *
 * <p>Journeys instantiate {@code LOCKED} and the <em>only</em> thing that opens
 * the gate is the prerequisite checklist clearing (plan §5.3: "there is no 'open
 * gate anyway' override"). A project created while no master is published would
 * therefore have journeys nothing can ever start — visible, owned, and
 * permanently dead. The wizard refused the same thing for the same reason.
 *
 * <p>The refusal applies <b>only when this client has no checklist yet</b>. A
 * client boarded last month already has one, very possibly already cleared, and
 * their second project has no need of a currently-published master to be
 * startable.
 */
@Service
@UnscopedAccess("""
        Every read a person sees goes through ObProjectReadRepository with the \
        client scope applied. The three repositories used here are a \
        load-for-write after that scoped read has already answered 404, and two \
        existence guards that MUST see rows the caller cannot — "one project per \
        client per product" is a fact about the organisation, and scoping it \
        would let one salesperson create a duplicate of a project another \
        salesperson owns because the first is invisible to them.""")
class ObProjectWriteService {

    private final ObProjectRepository projects;
    private final ObClientRepository clients;
    private final ObProductRepository products;
    private final ObProjectPurchaseRepository purchases;
    private final ObClientPrereqService prereqs;
    private final ObJourneyInstantiationService journeys;
    private final ObProjectService details;
    private final ObProjectDeletionGuard deletionGuard;
    private final ObProjectCascadeRepository cascade;

    ObProjectWriteService(ObProjectRepository projects, ObClientRepository clients,
                          ObProductRepository products, ObProjectPurchaseRepository purchases,
                          ObClientPrereqService prereqs, ObJourneyInstantiationService journeys,
                          ObProjectService details, ObProjectDeletionGuard deletionGuard,
                          ObProjectCascadeRepository cascade) {
        this.projects = projects;
        this.clients = clients;
        this.products = products;
        this.purchases = purchases;
        this.prereqs = prereqs;
        this.journeys = journeys;
        this.details = details;
        this.deletionGuard = deletionGuard;
        this.cascade = cascade;
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Transactional
    ObProjectDetail create(ObClientScope scope, ObProjectCreateRequest request) {
        if (scope.deniesEverything()) {
            throw new NotAnOnboardingProjectWriterException();
        }
        if (!scope.mayWrite()) {
            throw new NotAnOnboardingProjectWriterException();
        }

        Map<String, String> errors = new LinkedHashMap<>();
        ObClient client = clients.findById(request.clientId()).orElse(null);
        if (client == null) {
            errors.put("clientId", "No such client.");
        }
        ObProduct product = products.findById(request.productId()).orElse(null);
        if (product == null) {
            errors.put("productId", "No such product.");
        } else if (!product.isActive()) {
            // Retired, not deleted — ob_products has no delete. A retired
            // product keeps its existing projects running and is simply no
            // longer sold, so this refusal is about starting something new.
            errors.put("productId", "%s is retired and can no longer be sold.".formatted(product.getName()));
        }
        if (request.name() != null && request.name().isBlank()) {
            errors.put("name", "Give the project a name.");
        }
        if (!errors.isEmpty()) {
            throw new ObProjectValidationException(errors);
        }

        projects.findByObClientIdAndProductId(request.clientId(), request.productId())
                .ifPresent(existing -> {
                    throw new DuplicateProjectException(existing.getId(), existing.getName());
                });

        boolean clientHasChecklist = prereqs.headerOf(request.clientId()).isPresent();
        if (!clientHasChecklist && !prereqs.hasActivePrereqMaster()) {
            throw new NoPublishedPrerequisitesException();
        }

        purchases.recordPurchase(request.clientId(), request.productId());

        ObProject project = projects.save(new ObProject(
                request.clientId(),
                request.productId(),
                request.name().trim(),
                request.startDate(),
                request.salesPersonId(),
                request.implementorUserId(),
                scope.userId()));

        if (!clientHasChecklist) {
            prereqs.instantiate(request.clientId());
        }

        journeys.instantiateSelected(project, request.moduleServiceIds());

        return details.findDetail(scope, project.getId())
                .orElseThrow(() -> new ObProjectNotFoundException(project.getId()));
    }

    // ------------------------------------------------------------------
    // Edit
    // ------------------------------------------------------------------

    /**
     * The header edit: name, start date, the two people, and the status.
     *
     * <p>{@code clientId} and {@code productId} are not editable and are not on
     * the request — see {@code ObProjectUpdateRequest}. The scoped read has
     * already answered 404 for a project this caller cannot see, so by the time
     * the load-for-write below runs, the id is known to be theirs.
     */
    @Transactional
    ObProjectDetail update(ObClientScope scope, long projectId, ObProjectUpdateRequest request) {
        if (!scope.mayWrite()) {
            throw new ObProjectReadOnlyException(projectId);
        }
        ObProject project = projects.findById(projectId)
                .orElseThrow(() -> new ObProjectNotFoundException(projectId));

        Map<String, String> errors = new LinkedHashMap<>();
        if (request.name() != null && request.name().isBlank()) {
            errors.put("name", "Give the project a name.");
        }

        ObProjectStatus status = null;
        if (request.status() != null && !request.status().isBlank()) {
            try {
                status = ObProjectStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                errors.put("status", "Not a project status.");
            }
            if (status == ObProjectStatus.COMPLETED) {
                throw new ProjectStatusNotEarnedException();
            }
            if (status != null && status.requiresReason()
                    && (request.statusReason() == null || request.statusReason().isBlank())) {
                // The reason is worth nothing if it was optional at the moment
                // it was known — ObProjectStatus says so, and this is where it
                // is enforced.
                errors.put("statusReason", "Say why the project is %s."
                        .formatted(status.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            }
        }
        if (!errors.isEmpty()) {
            throw new ObProjectValidationException(errors);
        }

        project.setName(request.name().trim());
        project.setStartDate(request.startDate());
        project.setSalesPersonId(request.salesPersonId());
        project.setImplementorUserId(request.implementorUserId());
        if (status != null && status != project.getStatus()) {
            project.recordStatus(status, status.requiresReason() ? request.statusReason().trim() : null);
        }
        projects.save(project);

        return details.findDetail(scope, projectId)
                .orElseThrow(() -> new ObProjectNotFoundException(projectId));
    }

    // ------------------------------------------------------------------
    // Delete — and the reason it is often refused
    // ------------------------------------------------------------------

    /**
     * Remove a project that nothing records having run.
     *
     * <p><b>This exists for a project created against the wrong client, or on
     * the wrong product.</b> That is noise on a grid people read every morning,
     * and {@code DROPPED} would keep it there forever wearing a reason that
     * says "this was a typo".
     *
     * <p>Everything else is refused. A project owns journeys, journeys own
     * steps, and nine tables hold a foreign key to those steps with no cascade
     * — two of them hash-chained and append-only.
     * {@link ObProjectDeletionGuard} asks all nine before anything is removed,
     * and the answer is a sentence naming what is in the way rather than a
     * constraint violation naming an index. {@code fk_ob_journey_steps_journey}
     * is {@code RESTRICT} underneath as a second layer, for the hand at a SQL
     * prompt that never passes through here.
     *
     * <p>404 before 403, and both before the guard: a caller who cannot see
     * this project must not learn from the refusal that it exists and has
     * sign-offs.
     *
     * <p>The client's purchase row is left alone — see
     * {@link ObProjectCascadeRepository#deleteProject}.
     */
    @Transactional
    void delete(ObClientScope scope, long projectId) {
        details.findDetail(scope, projectId)
                .orElseThrow(() -> new ObProjectNotFoundException(projectId));
        if (!scope.mayWrite()) {
            throw new ObProjectReadOnlyException(projectId);
        }
        List<String> blockers = deletionGuard.blockersFor(projectId);
        if (!blockers.isEmpty()) {
            throw new ObProjectInUseException(blockers);
        }
        cascade.deleteProject(projectId);
    }
}
