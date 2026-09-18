package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.prereqs.ObClientPrereqService;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import com.edunext.edutrack.domain.onboarding.ObProject;
import com.edunext.edutrack.domain.onboarding.ObProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * "This pair needs a project and may not have one" — for the callers that
 * create journeys without having been through the New Project form.
 *
 * <h2>Who needs this, and why they are not simply made to use the form</h2>
 *
 * <p>Since {@code V20260911_1800} every journey belongs to a project, and
 * {@code ObJourneyInstantiationService} resolves one rather than inventing one —
 * see {@code ProjectNotFoundForPairException} for why inventing is the wrong
 * default there. Two callers still create journeys by another route:
 *
 * <ul>
 *   <li><b>The purchases panel</b> on the client page
 *       ({@code ObApplicationService}), which records a product bought after
 *       boarding. It predates projects and is still the right place to record a
 *       renewal's licence terms.</li>
 *   <li><b>The development fixtures</b>, which board a corpus of clients with no
 *       form involved at all.</li>
 * </ul>
 *
 * <p>Both want a project to exist, neither has a name or a start date to give
 * it, and both would otherwise each grow their own copy of this. One place,
 * named for what it does.
 *
 * <h2>The derived name is the backfill's, deliberately</h2>
 *
 * <p>{@code "<Client> — <Product>"}, truncated to the column exactly as
 * {@code V20260911_1800} truncates it. A project created this way is
 * indistinguishable from one the migration backfilled, which is the honest
 * outcome: in both cases nobody chose a name, and the two should not look like
 * different kinds of row on the grid. Somebody renames it on the project page
 * when they care.
 *
 * <p><b>Idempotent by returning the existing row</b>, unlike
 * {@code ObClientPrereqService.instantiate}, which refuses a second call.
 * The difference is what a second call means: a client with two checklists is a
 * caller bug worth surfacing, while "make sure this pair has a project" asked
 * twice is the ordinary shape of an ensure.
 */
@Service
@UnscopedAccess("""
        Reads a client and a product by id to derive a name, and looks up or \
        writes the project for a pair the caller has already been authorised to \
        act on — the scoped read that answered 404 happened before any of its \
        callers reached here. "Does this pair have a project" must also see rows \
        the caller cannot, or two salespeople would each create one for the same \
        pair and collide on uq_ob_projects_client_product.""")
public class ObProjectProvisioning {

    private final ObProjectRepository projects;
    private final ObClientRepository clients;
    private final ObProductRepository products;
    private final ObClientPrereqService prereqs;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObEscalationService}'s own
     * note: two constructors and no annotation is not an ambiguity Spring
     * resolves, it is a context that fails to start.
     */
    @Autowired
    ObProjectProvisioning(ObProjectRepository projects, ObClientRepository clients,
                          ObProductRepository products, ObClientPrereqService prereqs) {
        this(projects, clients, products, prereqs, Clock.systemUTC());
    }

    /** Test seam — the derived start date is "today", which a fixed clock lets a test pin. */
    ObProjectProvisioning(ObProjectRepository projects, ObClientRepository clients,
                          ObProductRepository products, ObClientPrereqService prereqs, Clock clock) {
        this.projects = projects;
        this.clients = clients;
        this.products = products;
        this.prereqs = prereqs;
        this.clock = clock;
    }

    /**
     * The project for this pair, created if it is missing.
     *
     * @param createdBy the acting user, or null where there is none — the
     *                  fixtures have no caller, and a fabricated one would put a
     *                  real person's id on rows they never touched
     */
    @Transactional
    public ObProject ensureFor(long obClientId, long productId, Long createdBy) {
        ensureChecklist(obClientId);
        return projects.findByObClientIdAndProductId(obClientId, productId)
                .orElseGet(() -> projects.save(new ObProject(
                        obClientId,
                        productId,
                        derivedName(obClientId, productId),
                        LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                        clients.findById(obClientId).map(c -> c.getSalesPersonId()).orElse(null),
                        // Neither the implementor nor their manager is knowable
                        // here. This path has no form and no caller choosing
                        // people — it backfills the project a purchase implies —
                        // and inventing either would put a name on an
                        // engagement nobody assigned them to.
                        null,
                        null,
                        createdBy)));
    }

    /**
     * The client's prerequisite checklist, if they have none.
     *
     * <p><b>This is the gate's only opener, so a project without it is a project
     * whose journeys can never start.</b> Journeys instantiate {@code LOCKED}
     * and plan §5.3 is explicit that there is no "open gate anyway" override —
     * so a purchase recorded through the OB-05 panel for a client who has never
     * had a checklist would produce exactly the permanently dead journeys
     * {@code ObProjectWriteService} refuses to create from the form.
     *
     * <p>The form <em>refuses</em> in that situation and this <em>proceeds</em>
     * without one, and the difference is deliberate: the form has somebody in
     * front of it who can be told to publish a master first, while this runs
     * inside a purchase that has already been accepted. Failing here would roll
     * back a commercial fact over a configuration gap. The journeys stay locked
     * and open the moment a master is published and the client's checklist is
     * instantiated, which is recoverable; a rolled-back purchase is a support
     * call.
     */
    private void ensureChecklist(long obClientId) {
        if (prereqs.headerOf(obClientId).isPresent() || !prereqs.hasActivePrereqMaster()) {
            return;
        }
        prereqs.instantiate(obClientId);
    }

    /**
     * {@code "<Client> — <Product>"}, cut to {@code ob_projects.name}'s 200
     * characters.
     *
     * <p>The truncation is not tidiness: a 200-character client name beside a
     * 160-character product name overruns by half, and MySQL in strict mode
     * refuses the row rather than trimming it — so an untruncated name here
     * would be a fixture load that fails on the one client with a long legal
     * title.
     */
    private String derivedName(long obClientId, long productId) {
        String clientName = clients.findById(obClientId).map(c -> c.getName()).orElse("Client " + obClientId);
        String productName = products.findById(productId).map(p -> p.getName()).orElse("Product " + productId);
        String name = clientName + " — " + productName;
        return name.length() <= 200 ? name : name.substring(0, 200);
    }
}
