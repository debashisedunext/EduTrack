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
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * C-103 · Instantiation — plan §5.2. One LOCKED journey per purchased
 * product, {@code template_id + version} pinned at creation, owners
 * resolved where they can be, clocks dead until the gate opens.
 *
 * <h2>What this service does not do</h2>
 *
 * <p>Deliberately, so the next three tasks have a table to build on rather
 * than a half-finished implementation to untangle:
 *
 * <ul>
 *   <li><b>Service-level dependency (C-123) is resolved here, released
 *       elsewhere.</b> {@link ObJourney#getHeldByJourneyId()} is set from the
 *       template's {@code depends_on_template_id} at birth; clearing it when
 *       the holder completes is {@link ObJourneyDependencyRelease}'s.</li>
 *   <li><b>No role→user resolution.</b> A template step's {@code ownerRole}
 *       is never consulted — there is no per-client role→user resolver
 *       anywhere yet (OB-08's "Responsibility" admin, not built). Only a
 *       pinned {@code ownerUserId} carries forward; everything else lands on
 *       {@link #unassignedSteps()}.</li>
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
    private final ObJourneyTemplateStepRepository templateSteps;
    private final ObJourneyTemplateStepItemRepository templateStepItems;
    private final PurchasedProductAccess purchasedProducts;
    private final ObJourneyStepLifecycleService stepLifecycle;

    public ObJourneyInstantiationService(ObJourneyRepository journeys,
                                          ObJourneyStepRepository journeySteps,
                                          ObJourneyStepItemRepository journeyStepItems,
                                          ObJourneyTemplateRepository templates,
                                          ObJourneyTemplateStepRepository templateSteps,
                                          ObJourneyTemplateStepItemRepository templateStepItems,
                                          PurchasedProductAccess purchasedProducts,
                                          ObJourneyStepLifecycleService stepLifecycle) {
        this.journeys = journeys;
        this.journeySteps = journeySteps;
        this.journeyStepItems = journeyStepItems;
        this.templates = templates;
        this.templateSteps = templateSteps;
        this.templateStepItems = templateStepItems;
        this.purchasedProducts = purchasedProducts;
        this.stepLifecycle = stepLifecycle;
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
        if (!purchasedProducts.isPurchased(obClientId, productId)) {
            throw new ProductNotPurchasedException(obClientId, productId);
        }
        List<ObJourneyTemplate> services =
                templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(productId);
        if (services.isEmpty()) {
            throw new NoActiveTemplateForProductException(productId);
        }

        List<ObJourney> created = new ArrayList<>();
        for (ObJourneyTemplate service : services) {
            if (journeys.existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
                    obClientId, productId, service.getName())) {
                continue;
            }
            created.add(instantiateService(obClientId, productId, service));
        }
        if (created.isEmpty()) {
            throw new JourneyAlreadyExistsException(obClientId, productId);
        }
        return created;
    }

    /** One service of one product, the caller having already settled that it is missing. */
    private ObJourney instantiateService(long obClientId, long productId, ObJourneyTemplate template) {
        ObJourney journey = new ObJourney();
        journey.setObClientId(obClientId);
        journey.setProductId(productId);
        journey.setServiceName(template.getName());
        journey.setTemplateId(template.getId());

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

        // C-123 · plan §5.5: a service that depends on another module service
        // instantiates normally but stays held — no step activates, no clock
        // runs — until this client's journey from the dependency completes.
        // Vacuous when the client never bought the dependency's product, or
        // already finished it: the journey starts as if it had no dependency.
        journey.setHeldByJourneyId(holdingJourneyFor(obClientId, template));

        ObJourney saved = journeys.save(journey);
        cloneSteps(template.getId(), saved.getId());
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
     * The journey this one waits behind, or {@code null}.
     *
     * <p>The dependency is declared between <em>template versions</em>
     * ({@code depends_on_template_id}) but held between <em>journeys</em>, and
     * it resolves through the dependency's <b>service</b> rather than its row:
     * the client's live journey for that (product, service name) pair — if
     * there is one — is the holder.
     *
     * <p>Resolving by row id would hold only until the dependency published a
     * new version, at which point the client's journey would be pinned to v2
     * while the declaration still named v1, and every dependent journey would
     * start unheld.
     */
    private Long holdingJourneyFor(long obClientId, ObJourneyTemplate template) {
        Long dependsOn = template.getDependsOnTemplateId();
        if (dependsOn == null) {
            return null;
        }
        return templates.findById(dependsOn)
                .flatMap(dependency -> journeys
                        .findFirstByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNullOrderByIdDesc(
                                obClientId, dependency.getProductId(), dependency.getName()))
                .filter(holder -> holder.getCompletedAt() == null)
                .map(ObJourney::getId)
                .orElse(null);
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

    private void cloneSteps(long templateId, long journeyId) {
        List<ObJourneyTemplateStep> sourceSteps = templateSteps.findByTemplateIdOrderBySequenceAsc(templateId);

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
            // Pinned user only — see the class javadoc on why ownerRole is
            // never consulted here.
            clone.setOwnerUserId(source.getOwnerUserId());
            clone.setBackupOwnerUserId(source.getBackupOwnerUserId());
            clone.setRequiresSignoff(source.isRequiresSignoff());
            ObJourneyStep savedClone = journeySteps.save(clone);
            sourceToClonedStepId.put(source.getId(), savedClone.getId());

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
}
