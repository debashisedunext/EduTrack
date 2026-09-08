package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyInstantiationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-104 · recording what a client bought, and keeping the licence window
 * current afterwards.
 *
 * <h2>What this task actually closes</h2>
 *
 * <p>OB-04 captures purchases and their licence dates at boarding, and until now
 * that was the only moment they could ever be captured. Two ordinary things were
 * therefore unrepresentable once the wizard closed:
 *
 * <ul>
 *   <li><b>A client buying a second product.</b> The purchase is what a journey
 *       is instantiated from, so with no way to add one, a client who bought a
 *       module six months in had nothing to be onboarded through — and no way to
 *       get one short of somebody writing the row by hand and remembering to
 *       call C-103's service afterwards.</li>
 *   <li><b>A renewal.</b> {@code license_end} is why the backlog calls this pair
 *       "the renewal anchor", and why {@code ix_ob_client_applications_license_end}
 *       exists at all — the migration's own words, "renewals will read this
 *       without a client in hand". A column that can be written once at boarding
 *       and never moved forward is not an anchor: by the time a renewals module
 *       reads it, every row past its first year would say the licence lapsed.</li>
 * </ul>
 *
 * <h2>Adding a purchase instantiates its journey, in the same transaction</h2>
 *
 * <p>Not a follow-up call the caller has to remember. {@code ObClientWriteService}
 * does exactly this for the wizard's products and says why: a client with a
 * purchase and no journey is one of the two states
 * {@code ObClientChildWriteRepository} names as "states somebody would have to
 * notice and repair by hand". A purchase added here is the same fact arriving
 * later, and it earns the same journey.
 *
 * <p>C-103 decides what that journey looks like, and its rule is worth knowing
 * from this side: a product bought <b>after</b> this client's gate has already
 * opened instantiates directly {@code OPEN} rather than {@code LOCKED} (plan
 * §5.3 item 3). So the client is not re-gated on prerequisites they have already
 * satisfied — precisely the case this route creates and the wizard never could.
 *
 * <h2>There is no {@code DELETE}, and that is a decision rather than an omission</h2>
 *
 * <p>{@code fk_ob_journeys_application} points at {@code (ob_client_id,
 * product_id)} with no {@code ON DELETE} clause, so it is {@code RESTRICT} — and
 * every purchase acquires a journey the moment it is made, by the paragraph
 * above. A delete would therefore have exactly two possible implementations:
 *
 * <ol>
 *   <li>Issue the {@code DELETE} and let MySQL refuse it. That is a 500 dressed
 *       as a feature: the route would fail for every purchase that has ever
 *       existed, which is all of them.</li>
 *   <li>Delete the journey first. That means reaching into {@code ob_journeys},
 *       {@code ob_journey_steps} and everything hanging off them — clock events,
 *       sign-off requests, escalations — from a package that owns none of it, to
 *       destroy the record of work that was done. Archiving instead does not
 *       help: {@code archived_at} leaves the row in place and {@code RESTRICT}
 *       still refuses.</li>
 * </ol>
 *
 * <p>So the operation is not offered rather than offered broken. A purchase made
 * by mistake is a client-level problem — the client is deactivated, or the
 * journey is archived by the people who own journeys — and the honest home for
 * "unpick an instantiated journey" is beside C-103's instantiation, not here.
 * Named in {@code README.md}'s deferral table so the next person finds a reason
 * rather than a gap.
 *
 * <h2>Order of refusals, which is the same security property B-103 states</h2>
 *
 * <p>Scoped client read (404) → write role (403) → purchase resolution (404) →
 * product immutability (409) → validation (400) → duplicate product (409) →
 * template (409). The scoped read is first so an out-of-scope client id is
 * indistinguishable from a missing one; the write-role check precedes the
 * purchase lookup so a Viewer cannot use the 404/403 difference to probe which
 * purchase ids exist under a client they can see.
 */
@Service
class ObApplicationService {

    private final ObClientService details;
    private final ObClientReadRepository reads;
    private final ObClientChildWriteRepository products;
    private final ObApplicationWriteRepository applications;
    private final ObJourneyInstantiationService journeys;

    ObApplicationService(ObClientService details, ObClientReadRepository reads,
                         ObClientChildWriteRepository products,
                         ObApplicationWriteRepository applications,
                         ObJourneyInstantiationService journeys) {
        this.details = details;
        this.reads = reads;
        this.products = products;
        this.applications = applications;
        this.journeys = journeys;
    }

    // ------------------------------------------------------------------
    // Add
    // ------------------------------------------------------------------

    /**
     * Record a purchase and instantiate the journey it exists to produce.
     *
     * <p>Both in one transaction, so the pair of states nobody can act on — a
     * purchase with no journey, a journey with no purchase — is unreachable
     * rather than merely unlikely. The purchase is written first because C-103's
     * service reads {@code ob_client_applications} to check the product was
     * bought before it will instantiate anything; {@code ObClientWriteService}
     * orders the wizard's create the same way and for the same reason.
     */
    @Transactional
    ObClientDtos.ObClientDetail add(ObClientScope scope, long obClientId,
                                    ObClientDtos.ObApplicationWriteRequest request) {

        requireWritableClient(scope, obClientId);
        validate(request);
        rejectDuplicateProduct(obClientId, request.productId());
        requireSellableWithPublishedTemplate(request.productId());

        applications.insert(obClientId, request);
        journeys.instantiate(obClientId, request.productId());

        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Edit — the renewal, and the seat count
    // ------------------------------------------------------------------

    /**
     * Change the licence type, the seat count or the licence window.
     *
     * <p><b>The product is not re-validated</b>, and the omission is deliberate.
     * {@link #requireSellableWithPublishedTemplate} asks whether a product may be
     * <em>bought</em>; whether an existing purchase may be <em>corrected</em> is
     * a different question with the opposite answer. A product retired last
     * quarter is out of the picker and its clients are still onboarding through
     * it — refusing to renew their licence because the product is no longer sold
     * would make a retirement retroactively strand every client who already
     * bought it. Retiring rather than deleting is exactly what {@code ob_products}
     * does, on the migration's own note that "the journeys keep running after it
     * is retired".
     */
    @Transactional
    ObClientDtos.ObClientDetail update(ObClientScope scope, long obClientId, long applicationId,
                                       ObClientDtos.ObApplicationWriteRequest request) {

        requireWritableClient(scope, obClientId);
        ObClientReadRepository.ApplicationRow current = requireApplication(obClientId, applicationId);

        refuseRepointing(current, request.productId());
        validate(request);

        applications.update(applicationId, request);
        return detail(scope, obClientId);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * The scoped read, then the write role — 404 before 403 and never the
     * reverse.
     *
     * <p>{@code ObContactService.requireWritableClient}'s reasoning verbatim: an
     * out-of-scope client must be indistinguishable from a missing one, and a
     * caller who can see the client has already been shown it, so 403 concedes
     * nothing.
     */
    private void requireWritableClient(ObClientScope scope, long obClientId) {
        ObClientDtos.ObClientDetail client = detail(scope, obClientId);
        if (!scope.mayWrite()) {
            throw new ObClientReadOnlyException(client.name());
        }
    }

    /**
     * The scoped client read on its own, for the controller's {@code If-Match}.
     *
     * <p>Exposed rather than letting the controller hold {@code ObClientService}
     * as a second collaborator — {@code ObContactService.readable} says why: the
     * precondition has to be evaluated against exactly the document these
     * operations answer with, and two paths to it are two chances to diverge.
     */
    ObClientDtos.ObClientDetail readable(ObClientScope scope, long obClientId) {
        return detail(scope, obClientId);
    }

    private ObClientDtos.ObClientDetail detail(ObClientScope scope, long obClientId) {
        return details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
    }

    private ObClientReadRepository.ApplicationRow requireApplication(long obClientId,
                                                                     long applicationId) {
        return reads.applicationOf(obClientId, applicationId)
                .orElseThrow(() -> new ObApplicationNotFoundException(obClientId, applicationId));
    }

    /**
     * A {@code PATCH} naming a different product is refused, not ignored.
     *
     * <p>The body carries {@code productId} because it is the whole
     * representation rather than a sparse patch, so the panel echoes back what it
     * read. Echoing the same value is the normal case and does nothing; echoing a
     * <em>different</em> one is a form that has come apart, and quietly keeping
     * the old product would leave somebody believing the change landed.
     * {@link ApplicationProductImmutableException} sets out what landing it would
     * have done to the journey pinned behind it.
     */
    private static void refuseRepointing(ObClientReadRepository.ApplicationRow current,
                                         Long requestedProductId) {
        if (requestedProductId != null && requestedProductId != current.productId()) {
            throw new ApplicationProductImmutableException(current.productName());
        }
    }

    private void rejectDuplicateProduct(long obClientId, long productId) {
        Optional<ObClientReadRepository.ApplicationRow> existing =
                reads.applicationByProduct(obClientId, productId);
        existing.ifPresent(row -> {
            throw new DuplicateApplicationProductException(row.productName(), row.id());
        });
    }

    /**
     * The product must be on sale, and it must have a template to instantiate
     * from.
     *
     * <p>Both asked here rather than left to C-103's service, on
     * {@link ProductWithoutTemplateException}'s stated reason: that service's own
     * exceptions are package-private to its package and are not something to
     * widen from here. Checking first also produces a refusal that names the
     * product and tells an admin what to do about it on OB-07, rather than a
     * rollback naming a class nobody outside Stream C has read.
     */
    private void requireSellableWithPublishedTemplate(long productId) {
        Set<Long> one = Set.of(productId);
        if (products.sellableProductIds(one).isEmpty()) {
            throw new ObClientValidationException(Map.of("productId",
                    "No product on sale with id " + productId
                            + ". A retired product is out of the picker by definition — buying one "
                            + "today would instantiate a journey from a template nobody maintains."));
        }
        if (products.productIdsWithActiveTemplate(one).isEmpty()) {
            throw new ProductWithoutTemplateException(List.of(productId));
        }
    }

    /**
     * Everything Bean Validation cannot say, collected rather than thrown at the
     * first — {@link ObClientValidationException}'s own reason.
     *
     * <p>One rule lands here today, because the rest of this shape is expressible
     * in annotations: {@code productId} is {@code @NotNull} and {@code units} is
     * {@code @Min(1)}, mirroring {@code ck_ob_client_applications_units}. The
     * licence window is not, because it is a relationship between two fields.
     */
    private static void validate(ObClientDtos.ObApplicationWriteRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (request.hasInvertedWindow()) {
            // ck_ob_client_applications_licence_window says the same thing and
            // would say it as a constraint name. Keyed to licenseEnd rather than
            // to the pair, because the end date is the one being moved: it is the
            // field a renewal edits, and a message on the start date would point
            // at the half nobody touched.
            errors.put("licenseEnd", "A licence cannot end before it starts — "
                    + request.licenseEnd() + " is earlier than " + request.licenseStart() + ".");
        }

        if (!errors.isEmpty()) {
            throw new ObClientValidationException(errors);
        }
    }
}
