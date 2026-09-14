package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependency;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import com.edunext.edutrack.domain.onboarding.ObProject;
import com.edunext.edutrack.domain.onboarding.ObProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-103 · Instantiation — plan §5.2. One LOCKED journey per purchased
 * product, {@code template_id + version} pinned at creation, owners
 * resolved where they can be, and no clock started here.
 *
 * <p>A journey born {@code LOCKED} still activates nothing — the kick at the
 * end of {@link #instantiate} is guarded on {@code OPEN} and stays that way.
 * What changed with the advisory checklist is only that its owners may now
 * press Start themselves rather than waiting on the gate; see
 * {@link ObJourneyStepLifecycleService#start}.
 *
 * <h2>What this service does not do</h2>
 *
 * <p>Deliberately, so the next three tasks have a table to build on rather
 * than a half-finished implementation to untangle:
 *
 * <ul>
 *   <li><b>Service-level dependency (C-123) is resolved here, released
 *       elsewhere.</b> {@link ObJourney#getHeldByJourneyId()} is set at birth
 *       from the template's dependency set; clearing it when the holder
 *       completes — and re-pointing it at the next outstanding one, since a
 *       service may wait behind several — is
 *       {@link ObJourneyDependencyRelease}'s.</li>
 *   <li><b>No role→user resolution, because there is no role left to
 *       resolve.</b> A template step used to carry an {@code ownerRole} this
 *       service never consulted — no per-client role→user resolver exists
 *       (OB-08's "Responsibility" admin, not built), so a task naming only a
 *       role instantiated onto nobody. The column is gone
 *       ({@code V20260914_1830}) and its job is done by a person instead: a
 *       task with no pinned {@code ownerUserId} falls back to the project's
 *       implementor. See {@link #defaultImplementorOf}.
 *       {@link #unassignedSteps()} still exists and is now the narrow case it
 *       was meant to be — a project with no implementor and no creator —
 *       rather than where most of a journey lands.</li>
 * </ul>
 *
 * <p><b>C-119 · the one exception.</b> Every step is still born
 * {@code PENDING} in {@link #cloneSteps} — activating the first wave of
 * dependency-free steps is not duplicated in the clone itself, on
 * {@link ObJourneyStep}'s own class javadoc. But a journey born
 * {@link ObGateStatus#OPEN} (a product bought after this client's gate
 * already cleared) needs that first wave activated by <em>something</em>,
 * since nothing else will ever call {@code complete}/{@code skip} on a step
 * that is still {@code PENDING} — so {@link #instantiate} calls
 * {@link ObJourneyStepLifecycleService#activateEligibleSteps} once the
 * journey is saved, the same re-evaluation a step completion triggers
 * mid-journey.
 */
@Service
@UnscopedAccess("""
        A-112 · this class performs no scoped read. Its three uses of \n        ObJourneyRepository are two uniqueness guards and a save, and the \n        guards MUST see rows the caller cannot: "one live journey per client \n        per product" and "one open gate per client" are facts about the \n        client, not about the caller. Routing them through ScopedJourneys \n        would let a Sales user create a second journey for a client another \n        Sales user boarded, because the first one is invisible to them — \n        turning a scope guard into a data-integrity bug. The 404 rule still \n        applies to every read of the journey this creates; it is just not \n        this class that performs one.""")
public class ObJourneyInstantiationService {

    private final ObJourneyRepository journeys;
    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyStepItemRepository journeyStepItems;
    private final ObJourneyTemplateRepository templates;
    private final ObJourneyTemplateDependencyRepository templateDependencies;
    private final ObJourneyTemplateStepRepository templateSteps;
    private final ObJourneyTemplateStepItemRepository templateStepItems;
    private final PurchasedProductAccess purchasedProducts;
    private final ObJourneyStepLifecycleService stepLifecycle;
    private final ObDemoStepDocumentSeeder demoDocuments;
    private final ObProjectRepository projects;

    public ObJourneyInstantiationService(ObJourneyRepository journeys,
                                          ObJourneyStepRepository journeySteps,
                                          ObJourneyStepItemRepository journeyStepItems,
                                          ObJourneyTemplateRepository templates,
                                          ObJourneyTemplateDependencyRepository templateDependencies,
                                          ObJourneyTemplateStepRepository templateSteps,
                                          ObJourneyTemplateStepItemRepository templateStepItems,
                                          PurchasedProductAccess purchasedProducts,
                                          ObJourneyStepLifecycleService stepLifecycle,
                                          ObDemoStepDocumentSeeder demoDocuments,
                                          ObProjectRepository projects) {
        this.journeys = journeys;
        this.journeySteps = journeySteps;
        this.journeyStepItems = journeyStepItems;
        this.templates = templates;
        this.templateDependencies = templateDependencies;
        this.templateSteps = templateSteps;
        this.templateStepItems = templateStepItems;
        this.purchasedProducts = purchasedProducts;
        this.stepLifecycle = stepLifecycle;
        this.demoDocuments = demoDocuments;
        this.projects = projects;
    }

    /**
     * <b>One product, one journey <em>per Module Service</em> it publishes.</b>
     * The wizard's multi-select (OB-04, B-109) calls this once per product it
     * creates a purchase row for; C-118's gate-open flow and a same-client
     * repeat purchase both land here too.
     *
     * <p>Until {@code V20260910_0030} a product could publish only one
     * service and this returned one journey. EduTrack ERP now runs Standard
     * SaaS Onboarding and Enterprise (with data migration audit) at once, and
     * a client buying it is boarded through both — one ribbon each, in
     * catalogue sequence. The whole set is created in one transaction: a
     * client holding some of a product's services and not others is a
     * half-boarded client nothing downstream knows how to read.
     *
     * <p><b>Services already running are skipped, not refused.</b> A product
     * whose catalogue grew a service after this client was boarded
     * instantiates the new one on the next call and leaves the rest alone;
     * only a product with nothing left to instantiate raises
     * {@link JourneyAlreadyExistsException}.
     *
     * @throws ProductNotPurchasedException      no {@code ob_client_applications} row for this pair
     * @throws JourneyAlreadyExistsException      every service of the product already runs for this client
     * @throws NoActiveTemplateForProductException the product publishes no service to pin
     */
    @Transactional
    public List<ObJourney> instantiate(long obClientId, long productId) {
        ObProject project = projects.findByObClientIdAndProductId(obClientId, productId)
                .orElseThrow(() -> new ProjectNotFoundForPairException(obClientId, productId));
        return instantiateInto(project,
                templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(productId));
    }

    /**
     * The Projects screen's create — <b>only the Module Services the form left
     * checked</b>.
     *
     * <p>{@link #instantiate} boards a client through every service its product
     * publishes, which was the only behaviour that existed while a purchase was
     * a checkbox on a wizard. A project is chosen service by service, so the
     * two differ in exactly one respect: which templates reach
     * {@link #instantiateInto}. Everything after that — pinned sequence,
     * gate inheritance, the held-behind cursor, the step clone — is the same
     * code, because a journey created from a checked service is not a different
     * kind of journey.
     *
     * <p>The project is passed in rather than looked up, because this is the
     * one caller that has just written it and is inside the same transaction.
     *
     * @param templateIds the checked services, in any order — catalogue
     *                    sequence is re-imposed here, so a form that submits
     *                    them shuffled still instantiates a dependency before
     *                    the service held behind it
     * @throws UnknownModuleServiceException an id that is not an active service
     *                                       of this project's product, which is
     *                                       a form built against a catalogue
     *                                       that has since changed
     */
    @Transactional
    public List<ObJourney> instantiateSelected(ObProject project, List<Long> templateIds) {
        List<ObJourneyTemplate> active =
                templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(project.getProductId());
        Set<Long> wanted = new LinkedHashSet<>(templateIds);
        List<Long> offered = active.stream().map(ObJourneyTemplate::getId).toList();
        for (Long id : wanted) {
            if (!offered.contains(id)) {
                throw new UnknownModuleServiceException(project.getProductId(), id);
            }
        }
        // Filtered out of `active` rather than fetched by id, so the result
        // keeps the catalogue's own ordering without a second sort.
        return instantiateInto(project, active.stream().filter(t -> wanted.contains(t.getId())).toList());
    }

    /**
     * The shared half of the two entry points above: a project, and the
     * services it is to be boarded through.
     *
     * <p><b>Services already running are skipped, not refused.</b> A product
     * whose catalogue grew a service after this client was boarded
     * instantiates the new one on the next call and leaves the rest alone;
     * only a call with nothing left to instantiate raises
     * {@link JourneyAlreadyExistsException}.
     */
    private List<ObJourney> instantiateInto(ObProject project, List<ObJourneyTemplate> services) {
        long obClientId = project.getObClientId();
        long productId = project.getProductId();
        if (!purchasedProducts.isPurchased(obClientId, productId)) {
            throw new ProductNotPurchasedException(obClientId, productId);
        }
        if (services.isEmpty()) {
            throw new NoActiveTemplateForProductException(productId);
        }

        List<ObJourney> created = new ArrayList<>();
        for (ObJourneyTemplate service : services) {
            if (journeys.existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
                    obClientId, productId, service.getName())) {
                continue;
            }
            created.add(instantiateService(project, service));
        }
        if (created.isEmpty()) {
            throw new JourneyAlreadyExistsException(obClientId, productId);
        }
        return created;
    }

    /** One service of one project, the caller having already settled that it is missing. */
    private ObJourney instantiateService(ObProject project, ObJourneyTemplate template) {
        long obClientId = project.getObClientId();
        long productId = project.getProductId();
        ObJourney journey = new ObJourney();
        // The three are written together here and nowhere else — the
        // denormalisation ObJourney#getProjectId() documents holds because
        // this is its only writer.
        journey.setProjectId(project.getId());
        journey.setObClientId(obClientId);
        journey.setProductId(productId);
        journey.setServiceName(template.getName());
        journey.setTemplateId(template.getId());
        // The catalogue position, pinned at birth on ObJourney#getSequence()'s
        // own reasoning: OB-07's up/down control renumbers every active
        // template, and read live that would reorder the ribbons of every
        // school already boarded — mid-journey, under owners working the
        // strips in the order they were handed. Copied here, the catalogue
        // still drives the order journeys instantiate in (this loop's own
        // ordering) and the order the next school is boarded in; it stops
        // driving the order of a school boarded before the swap.
        journey.setSequence(template.getSequence());

        // Plan §5.3 item 3: "products bought after gate-open instantiate
        // directly OPEN." A client's gate opens for every journey at once
        // and never re-locks (C-118), so one prior OPEN journey — live or
        // archived — settles it. `gateOpenedBy` stays null: nobody actually
        // performed a gate-open action for *this* journey, it inherited one
        // already in effect, and the CHECK constraint only requires the
        // timestamp, not the actor.
        if (journeys.existsByObClientIdAndGateStatus(obClientId, ObGateStatus.OPEN)) {
            journey.setGateStatus(ObGateStatus.OPEN);
            journey.setGateOpenedAt(Instant.now());
        } else {
            journey.setGateStatus(ObGateStatus.LOCKED);
        }

        /*
          C-123 · plan §5.5: a service that depends on other module services
          instantiates normally but stays held — no step activates, no clock
          runs — until this client's journeys from those dependencies
          complete. Vacuous when the client never bought a dependency's
          product, or already finished it: the journey starts as if it had
          none.

          Only the *first* outstanding holder is written, because
          `held_by_journey_id` is one column. That is not a shortcut around
          the set: ObJourneyDependencyRelease re-points it at the next one
          still running when this holder completes, and only clears it when
          none is left. The column is a cursor over the set, not the set.
        */
        journey.setHeldByJourneyId(holdingJourneysFor(obClientId, template).stream()
                .findFirst().orElse(null));

        ObJourney saved = journeys.save(journey);
        cloneSteps(template.getId(), saved.getId(), project);
        if (saved.getGateStatus() == ObGateStatus.OPEN) {
            // C-119 · the "instantiate directly OPEN" edge case ObJourneyStep's
            // own class javadoc assigns here: nothing else will ever call
            // complete/skip on a step that is still PENDING, so the first
            // wave of dependency-free steps needs this explicit kick.
            stepLifecycle.activateEligibleSteps(saved.getId());
        }
        return saved;
    }

    /**
     * The wizard's own multi-select, one purchase already recorded per
     * product: every id instantiates in the one transaction, so a partial
     * failure never leaves the client with some journeys and not others.
     */
    @Transactional
    public List<ObJourney> instantiateAll(long obClientId, List<Long> productIds) {
        // C-123 · plan §5.5: the Module Service sequence "drives the order
        // journeys instantiate and display". Sorted here rather than trusted
        // from the wizard's multi-select, so a dependency journey always
        // exists before the journey that has to be held behind it.
        List<Long> ordered = productIds.stream()
                .sorted(Comparator.comparingInt(productId -> templates
                        .findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(productId).stream()
                        .mapToInt(ObJourneyTemplate::getSequence)
                        .min()
                        .orElse(Integer.MAX_VALUE)))
                .toList();
        // Flattened: one product contributes as many journeys as it publishes
        // services, and `instantiate` has already ordered each product's own
        // by the same catalogue sequence.
        return ordered.stream().flatMap(productId -> instantiate(obClientId, productId).stream()).toList();
    }

    /**
     * Every journey of this client that {@code template} is still waiting
     * behind — empty when nothing holds it.
     *
     * <p>The dependency is declared between <em>template versions</em> (rows
     * of {@code ob_journey_template_dependencies}) but held between
     * <em>journeys</em>, and each edge resolves through the dependency's
     * <b>service</b> rather than its row: the client's live journey for that
     * (product, service name) pair — if there is one — is a holder.
     *
     * <p>Resolving by row id would hold only until the dependency published a
     * new version, at which point the client's journey would be pinned to v2
     * while the declaration still named v1, and every dependent journey would
     * start unheld.
     *
     * <p>An edge holds nothing at all when the client never bought that
     * product (no journey to wait for), or already finished it (nothing left
     * to wait for). Both are skipped rather than treated as an unsatisfied
     * dependency, which is the behaviour the single-dependency version had
     * and the only one that lets a partially-purchased client board.
     *
     * <p>Ordered by journey id, so "the first outstanding holder" is a stable
     * choice rather than whatever the optimiser returned. It only decides
     * which holder's completion wakes this journey up first; the journey is
     * released when the <em>last</em> of them finishes either way.
     */
    List<Long> holdingJourneysFor(long obClientId, ObJourneyTemplate template) {
        List<Long> holders = new ArrayList<>();
        for (ObJourneyTemplateDependency edge
                : templateDependencies.findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(template.getId())) {
            templates.findById(edge.getDependsOnTemplateId())
                    .flatMap(dependency -> journeys
                            .findFirstByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNullOrderByIdDesc(
                                    obClientId, dependency.getProductId(), dependency.getName()))
                    .filter(holder -> holder.getCompletedAt() == null)
                    .map(ObJourney::getId)
                    .ifPresent(holders::add);
        }
        holders.sort(Comparator.naturalOrder());
        return holders;
    }

    /**
     * The Manager's unassigned list — every step instantiation could not
     * resolve an owner for. See {@link ObJourneyStep}'s own javadoc for why
     * that happens and where the answer to "which role was it meant for"
     * still lives.
     */
    @Transactional(readOnly = true)
    public List<ObJourneyStep> unassignedSteps() {
        return journeySteps.findByOwnerUserIdIsNullOrderByIdAsc();
    }

    private void cloneSteps(long templateId, long journeyId, ObProject project) {
        List<ObJourneyTemplateStep> sourceSteps = templateSteps.findByTemplateIdOrderBySequenceAsc(templateId);
        Long fallbackOwner = defaultImplementorOf(project);

        // First pass: clone every step without depends_on_step_id, since the
        // target ids a later step might point at do not exist yet — same
        // two-pass shape ObJourneyTemplateService#cloneSteps uses to revise
        // a template, applied here to instantiate one.
        Map<Long, Long> sourceToClonedStepId = new LinkedHashMap<>();
        for (ObJourneyTemplateStep source : sourceSteps) {
            ObJourneyStep clone = new ObJourneyStep();
            clone.setJourneyId(journeyId);
            clone.setTemplateStepId(source.getId());
            clone.setSequence(source.getSequence());
            clone.setName(source.getName());
            clone.setDescription(source.getDescription());
            clone.setTatDays(source.getTatDays());
            /*
              The template's pinned implementor, or the project's own when the
              task names nobody. See `defaultImplementorOf`: a task with no
              owner used to instantiate onto nobody and land on the Manager's
              unassigned list, which is a worse answer than the one the
              project has been carrying all along.

              No backup is seeded, because the template no longer has one to
              seed from (V20260914_1830). `ob_journey_steps.backup_owner_user_id`
              is untouched and still set per journey — leave coverage is a fact
              about this client's March, not about the plan.
            */
            clone.setOwnerUserId(source.getOwnerUserId() != null ? source.getOwnerUserId() : fallbackOwner);
            clone.setRequiresSignoff(source.isRequiresSignoff());
            ObJourneyStep savedClone = journeySteps.save(clone);
            sourceToClonedStepId.put(source.getId(), savedClone.getId());

            // No-op unless edutrack.onboarding.demo-data.pre-satisfy-step-documents
            // is set, which only a development profile may set. See the seeder:
            // no route anywhere creates a STEP-owned attachment, so without it a
            // step with a required document cannot be completed by anyone.
            demoDocuments.satisfyRequiredDocuments(
                    source.getId(), savedClone.getId(), savedClone.getOwnerUserId());

            for (ObJourneyTemplateStepItem item : templateStepItems.findByStepIdOrderBySequenceAsc(source.getId())) {
                ObJourneyStepItem itemClone = new ObJourneyStepItem();
                itemClone.setStepId(savedClone.getId());
                itemClone.setTemplateItemId(item.getId());
                itemClone.setSequence(item.getSequence());
                itemClone.setLabel(item.getLabel());
                journeyStepItems.save(itemClone);
            }
        }

        // Second pass: every clone now has an id, so depends_on_step_id can
        // be re-pointed at the clone of whatever the source step pointed at.
        for (ObJourneyTemplateStep source : sourceSteps) {
            if (source.getDependsOnStepId() == null) {
                continue;
            }
            Long clonedId = sourceToClonedStepId.get(source.getId());
            Long clonedDependsOn = sourceToClonedStepId.get(source.getDependsOnStepId());
            ObJourneyStep clone = journeySteps.findById(clonedId)
                    .orElseThrow(() -> new IllegalStateException(
                            "journey step " + clonedId + " was just cloned from template step "
                                    + source.getId() + " and has vanished mid-instantiation"));
            clone.setDependsOnStepId(clonedDependsOn);
        }
    }

    /**
     * Who a task with nobody named on it is put on — <b>the project's own
     * implementor</b>, and the person who created the project after that.
     *
     * <h2>Why a template task is usually nameless, and why that stopped
     * meaning unassigned</h2>
     *
     * <p>A Module Service is authored once and boarded for every client that
     * buys the product. Naming a person on a task there says "Priya does the
     * data migration for everyone, for ever", which is true of almost no task
     * — so almost every task was left blank, instantiated onto nobody, and
     * arrived on {@link #unassignedSteps()} for a manager to hand out one by
     * one. A designer field that is correctly left empty on nearly every row
     * is not a default; it is a form nobody can fill in.
     *
     * <p>The project knows the answer. {@code ob_projects.implementor_user_id}
     * is asked for on the create form and is exactly "who is running this
     * implementation", so a nameless task lands on them and a manager
     * reassigns the handful that belong to somebody else — the opposite of
     * assigning every step of every journey by hand.
     *
     * <p>{@code created_by} is the second fallback rather than a co-equal:
     * whoever set the project up is a real person who can see it and can pass
     * the work on, which beats nobody. Both columns are nullable, and a
     * project with neither leaves the task unowned exactly as before — the
     * unassigned list did not go away, it just stopped being where every task
     * goes.
     *
     * <p><b>The template still wins when it names somebody.</b> This is a
     * fallback for null, not an override: a task pinned to one person is
     * pinned deliberately, and a project implementor quietly replacing them
     * would make the designer's own field advisory.
     *
     * <p>Resolved once per journey rather than per task, and deliberately not
     * re-resolved later: the journey step is a <em>snapshot</em>, the same way
     * its name and TAT are. Changing a project's implementor next month
     * reassigns nothing already boarded, which is the behaviour a running
     * journey needs — the alternative silently moves live work off somebody's
     * plate.
     */
    private static Long defaultImplementorOf(ObProject project) {
        return project.getImplementorUserId() != null
                ? project.getImplementorUserId()
                : project.getCreatedBy();
    }
}
