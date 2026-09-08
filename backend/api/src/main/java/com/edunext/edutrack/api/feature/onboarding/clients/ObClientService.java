package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.pan.PanService;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-102 · the reads behind OB-03's list and OB-05's page.
 *
 * <p>Separate from {@link ObClientWriteService} on the split
 * {@code ClientService}/{@code ClientWriteService} already uses in this stream:
 * the read side is a keyset page plus four grouped projections, the write side
 * is a validation set with side effects. One class holding both is the file
 * every change to this feature touches, which is what feature packaging exists
 * to avoid within a feature as much as across one.
 */
@Service
class ObClientService {

    private final ObClientReadRepository reads;
    private final PanService pan;
    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyStepRagService rag;

    ObClientService(ObClientReadRepository reads, PanService pan,
            ObJourneyStepRepository journeySteps, ObJourneyStepRagService rag) {
        this.reads = reads;
        this.pan = pan;
        this.journeySteps = journeySteps;
        this.rag = rag;
    }

    /**
     * OB-03's grid.
     *
     * <p>A caller with no onboarding standing gets an empty page rather than a
     * refusal — {@code ObEscalationService}'s own fast path, and
     * {@code ModuleAccessGuard}'s reasoning for why a module a caller has no
     * grant in should be indistinguishable from one that holds nothing.
     */
    @Transactional(readOnly = true)
    ObClientDtos.ObClientListResponse list(ObClientScope scope, String q, String status, String rag,
                                           String gateStatus, Long productId, Long salesPersonId,
                                           String cursor, Integer limit) {
        if (scope.deniesEverything()) {
            return new ObClientDtos.ObClientListResponse(List.of(), PageMeta.last());
        }

        int clamped = PageLimit.clamp(limit);
        List<ObClientReadRepository.ListRow> rows = reads.list(
                scope, q, status, rag, gateStatus, productId, salesPersonId,
                cursor, PageLimit.fetchSize(clamped));

        CursorPage<ObClientReadRepository.ListRow> page = CursorPage.of(rows, clamped,
                row -> new Cursor(row.onboardingDate().toString(), row.id()));

        List<Long> ids = page.data().stream().map(ObClientReadRepository.ListRow::id).toList();
        Map<Long, List<ObClientDtos.ObProductRef>> products = productsByClient(ids);
        Map<Long, ObClientDtos.ObContact> primaries = primaryContactsByClient(ids);

        return new ObClientDtos.ObClientListResponse(
                page.data().stream()
                        .map(row -> summary(row,
                                products.getOrDefault(row.id(), List.of()),
                                primaries.get(row.id())))
                        .toList(),
                page.meta());
    }

    /**
     * OB-05's page in one read.
     *
     * <p>Six statements rather than one join: the client, its contacts, its
     * purchases, its requirements, its journeys and their steps. Joining them
     * would multiply the client row by the product of four child sets, and the
     * de-duplication afterwards costs more than the round trips it saved.
     *
     * @return empty for a client that is not there <b>and</b> for one outside
     *         the caller's scope — the controller turns both into 404
     */
    @Transactional(readOnly = true)
    Optional<ObClientDtos.ObClientDetail> findDetail(ObClientScope scope, long obClientId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        return reads.findDetail(scope, obClientId).map(this::detail);
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    private ObClientDtos.ObClientDetail detail(ObClientReadRepository.DetailRow row) {
        long id = row.summary().id();

        List<ObClientDtos.ObContact> contacts = reads.contactsOf(id).stream()
                .map(ObClientService::contact).toList();
        List<ObClientDtos.ObApplication> applications = reads.applicationsOf(id).stream()
                .map(ObClientService::application).toList();
        List<ObClientDtos.ObProductRef> products = applications.stream()
                .map(ObClientDtos.ObApplication::product).toList();

        return new ObClientDtos.ObClientDetail(
                id,
                row.summary().name(),
                row.summary().onboardingDate(),
                row.summary().status(),
                row.summary().rag(),
                row.summary().gateStatus(),
                row.summary().journeyCount(),
                row.summary().journeysComplete(),
                products,
                ObClientDtos.UserRef.of(row.summary().salesPersonId(), row.summary().salesPersonName()),
                contacts.stream().filter(ObClientDtos.ObContact::isPrimary).findFirst().orElse(null),
                row.summary().liveAt(),
                row.summary().hasPortalLogin(),
                row.description(),
                row.address(),
                row.licenseType(),
                // Masked for every role, and masked here rather than at the
                // controller: A-113's reveal is its own audited operation, and
                // the only value this document can carry is the masked one.
                pan.masked(row.panCiphertext()),
                row.statusReason(),
                contacts,
                applications,
                reads.requirementsOf(id).stream().map(ObClientService::requirement).toList(),
                journeys(id),
                ObClientDtos.UserRef.of(row.createdBy(), row.createdByName()),
                row.createdAt());
    }

    /**
     * B-106 · one requirement row, with both user references assembled.
     *
     * <p>{@code UserRef.of} answers null for a null id, which is what makes the
     * two left joins in {@code REQUIREMENT_COLUMNS} safe to read here without a
     * branch: an unmet requirement has no {@code metBy} and an imported one has
     * no {@code createdBy}, and both come back as an absent object rather than
     * as a {@code UserRef} wrapped around a zero.
     */
    private static ObClientDtos.ObRequirement requirement(
            ObClientReadRepository.RequirementRow row) {
        return new ObClientDtos.ObRequirement(
                row.id(), row.sequence(), row.title(), row.bodyHtml(), row.bodyText(),
                row.isMet(), row.metAt(),
                ObClientDtos.UserRef.of(row.metBy(), row.metByName()),
                ObClientDtos.UserRef.of(row.createdBy(), row.createdByName()),
                row.createdAt(), row.updatedAt());
    }

    /**
     * The accordion strips, with their dots attached in one pass.
     *
     * <p>Two statements — journeys and every step of every journey — rather
     * than one per journey. A client with six purchases would otherwise cost
     * seven round trips to render a page that is, in the end, one screen.
     */
    private List<ObClientDtos.ObJourneyStrip> journeys(long clientId) {
        List<ObClientReadRepository.JourneyStripRow> journeyRows = reads.journeysOf(clientId);
        if (journeyRows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<ObClientDtos.ObStepDot>> dots = new LinkedHashMap<>();
        for (ObClientReadRepository.StepDotRow dot : reads.stepDotsOf(clientId)) {
            dots.computeIfAbsent(dot.journeyId(), key -> new ArrayList<>())
                    .add(new ObClientDtos.ObStepDot(dot.id(), dot.sequence(), dot.name(),
                            dot.status(), dot.rag(), dot.dependsOnStepId()));
        }

        List<ObClientDtos.ObJourneyStrip> strips = new ArrayList<>(journeyRows.size());
        for (ObClientReadRepository.JourneyStripRow journey : journeyRows) {
            strips.add(new ObClientDtos.ObJourneyStrip(
                    journey.id(),
                    new ObClientDtos.ObProductRef(journey.productId(), journey.productCode(),
                            journey.productName()),
                    journey.gateStatus(),
                    journey.rag(),
                    percentComplete(journey.stepsSettled(), journey.stepCount()),
                    journey.heldByJourneyId(),
                    journey.totalTatDays(),
                    utilizedHours(journey.id()),
                    dots.getOrDefault(journey.id(), List.of())));
        }
        return strips;
    }

    /**
     * C-120 · one journey's utilized-so-far, in hours — the sum of {@link
     * ObJourneyStepRagService#hoursConsumed} across every one of its steps.
     *
     * <p>A separate read per journey, on {@code ObJourneyStepRagService}'s
     * own single-step shape: {@code hoursConsumed} needs each step's live
     * clock state (a {@code WAITING_ON_CLIENT} step reads its last pause),
     * which {@link ObClientReadRepository#stepDotsOf} was never asked to
     * carry and OB-05's client is a handful of journeys, never hundreds.
     *
     * <p>0.0 for a journey with no steps or none yet started — not null.
     * {@code ObJourneyStrip}'s own note is about the feature being unbuilt,
     * not about a journey that has genuinely consumed no time.
     */
    private Double utilizedHours(long journeyId) {
        BigDecimal total = journeySteps.findByJourneyIdOrderBySequenceAsc(journeyId).stream()
                .map(rag::hoursConsumed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Settled steps over total, floored.
     *
     * <p>A skipped step counts as settled. It is work that will not be done and
     * is not waiting for anybody, so leaving it out would strand every journey
     * carrying one below 100% forever — the progress bar would say a finished
     * onboarding is unfinished.
     *
     * <p>A journey with no steps is 0%, not 100%. Division would say the empty
     * set is entirely complete, which is true arithmetically and wrong on
     * screen: a journey whose template had no steps has not finished, it has
     * not started.
     */
    private static int percentComplete(int settled, int total) {
        return total == 0 ? 0 : (int) Math.floor(settled * 100.0 / total);
    }

    private Map<Long, List<ObClientDtos.ObProductRef>> productsByClient(List<Long> ids) {
        Map<Long, List<ObClientDtos.ObProductRef>> byClient = new LinkedHashMap<>();
        for (ObClientReadRepository.ProductRow row : reads.productsOf(ids)) {
            byClient.computeIfAbsent(row.obClientId(), key -> new ArrayList<>())
                    .add(new ObClientDtos.ObProductRef(row.id(), row.code(), row.name()));
        }
        return byClient;
    }

    private Map<Long, ObClientDtos.ObContact> primaryContactsByClient(List<Long> ids) {
        Map<Long, ObClientDtos.ObContact> byClient = new LinkedHashMap<>();
        for (ObClientReadRepository.ContactRow row : reads.primaryContactsOf(ids)) {
            byClient.put(row.obClientId(), contact(row));
        }
        return byClient;
    }

    private static ObClientDtos.ObClientSummary summary(ObClientReadRepository.ListRow row,
                                                        List<ObClientDtos.ObProductRef> products,
                                                        ObClientDtos.ObContact primary) {
        return new ObClientDtos.ObClientSummary(
                row.id(), row.name(), row.onboardingDate(), row.status(), row.rag(), row.gateStatus(),
                row.journeyCount(), row.journeysComplete(), products,
                ObClientDtos.UserRef.of(row.salesPersonId(), row.salesPersonName()),
                primary, row.liveAt(), row.hasPortalLogin());
    }

    /**
     * <p>{@code isPrimary} is {@code is_primary AND is_active}, not the stored
     * flag: {@code is_primary_key} — the generated column
     * {@code uq_ob_client_contacts_primary} is on — is 1 only while a contact is
     * also active, so this reports the fact the database enforces rather than a
     * second one that could drift from it.
     *
     * <p>B-103 · {@code whatsappOptInBy} is on {@link
     * ObClientReadRepository.ContactRow} and deliberately does not appear here.
     * The client portal renders this same contact shape, and who inside the
     * organisation attested a consent is not something to hand across that
     * boundary — {@code ObStepDot}'s "absent by construction, not hidden
     * client-side" argument, on a smaller field.
     */
    private static ObClientDtos.ObContact contact(ObClientReadRepository.ContactRow row) {
        return new ObClientDtos.ObContact(row.id(), row.name(), row.designation(), row.email(),
                row.phone(), row.whatsappOptIn(), row.whatsappOptInAt(), row.whatsappOptInSource(),
                row.isPrimary() && row.isActive(), row.isActive());
    }

    private static ObClientDtos.ObApplication application(ObClientReadRepository.ApplicationRow row) {
        return new ObClientDtos.ObApplication(row.id(),
                new ObClientDtos.ObProductRef(row.productId(), row.productCode(), row.productName()),
                row.licenseType(), row.units(), row.licenseStart(), row.licenseEnd());
    }
}
