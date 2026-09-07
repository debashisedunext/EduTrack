package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.pan.PanService;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    ObClientService(ObClientReadRepository reads, PanService pan) {
        this.reads = reads;
        this.pan = pan;
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
                reads.requirementsOf(id),
                journeys(id),
                ObClientDtos.UserRef.of(row.createdBy(), row.createdByName()),
                row.createdAt());
    }

    /**
     * The accordion strips, with their dots attached in one pass.
     *
     * <p>Two statements — journeys and every step of every journey — rather
     * than one per journey. A client with six purchases would otherwise cost
     * seven round trips to render a page that is, in the end, one screen.
     */
    private List<ObClientDtos.ObJourneyStrip> journeys(long clientId) {
        List<ObClientReadRepository.JourneyRow> journeyRows = reads.journeysOf(clientId);
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
        for (ObClientReadRepository.JourneyRow journey : journeyRows) {
            strips.add(new ObClientDtos.ObJourneyStrip(
                    journey.id(),
                    new ObClientDtos.ObProductRef(journey.productId(), journey.productCode(),
                            journey.productName()),
                    journey.gateStatus(),
                    journey.rag(),
                    percentComplete(journey.stepsSettled(), journey.stepCount()),
                    journey.blockedByJourneyId(),
                    journey.totalTatDays(),
                    // C-120's roll-up over ob_step_clock_events. Null rather
                    // than 0.0 — see ObJourneyStrip's own note.
                    null,
                    dots.getOrDefault(journey.id(), List.of())));
        }
        return strips;
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

    private static ObClientDtos.ObContact contact(ObClientReadRepository.ContactRow row) {
        return new ObClientDtos.ObContact(row.id(), row.name(), row.designation(), row.email(),
                row.phone(), row.whatsappOptIn(), row.isPrimary() && row.isActive());
    }

    private static ObClientDtos.ObApplication application(ObClientReadRepository.ApplicationRow row) {
        return new ObClientDtos.ObApplication(row.id(),
                new ObClientDtos.ObProductRef(row.productId(), row.productCode(), row.productName()),
                row.licenseType(), row.units(), row.licenseStart(), row.licenseEnd());
    }
}
