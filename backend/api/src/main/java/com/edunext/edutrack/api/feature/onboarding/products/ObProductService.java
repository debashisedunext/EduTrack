package com.edunext.edutrack.api.feature.onboarding.products;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependency;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A-124 · the product catalogue's rules.
 *
 * <h2>Two rules, and both are about what a code means</h2>
 *
 * <p><b>A duplicate code is refused here, not by the index.</b> The column's
 * collation is {@code utf8mb4_0900_ai_ci}, so MySQL would refuse
 * {@code lms} against an existing {@code LMS} on its own — with a message
 * naming a constraint, which is what {@code createClient} already had to be
 * taught not to do one master over. Checking first lets the refusal name the
 * field.
 *
 * <p><b>A code is immutable once the product exists.</b> Not because the string
 * is sacred, but because {@code ob_client_applications} and
 * {@code ob_journey_templates} both point at the row by id while every human
 * artefact — a mail subject, a report column, a conversation — refers to it by
 * code. Letting it change renames the product in half the places it appears and
 * not the other half. A PATCH that resends the same code is fine and common;
 * one that sends a different code is refused.
 */
@Service
public class ObProductService {

    private final ObProductRepository products;
    private final ObJourneyTemplateRepository templates;
    private final ObJourneyTemplateDependencyRepository dependencies;

    ObProductService(ObProductRepository products, ObJourneyTemplateRepository templates,
            ObJourneyTemplateDependencyRepository dependencies) {
        this.products = products;
        this.templates = templates;
        this.dependencies = dependencies;
    }

    @Transactional(readOnly = true)
    public List<ObProductDtos.Product> list(Boolean isActive) {
        List<ObProduct> rows = isActive == null
                ? products.findAllByOrderByNameAsc()
                : products.findAllByIsActiveOrderByNameAsc(isActive);
        return project(rows);
    }

    @Transactional(readOnly = true)
    public Optional<ObProductDtos.Product> find(long id) {
        return products.findById(id).map(row -> project(List.of(row)).get(0));
    }

    @Transactional
    public ObProductDtos.Product create(ObProductDtos.WriteRequest request, Long createdBy) {
        String code = normalise(request.code());
        products.findByCode(code).ifPresent(existing -> {
            throw new DuplicateProductCodeException(code);
        });

        ObProduct saved = products.save(
                new ObProduct(code, request.name().trim(), request.activeOrDefault(), createdBy));
        return project(List.of(saved)).get(0);
    }

    /**
     * @throws ProductCodeImmutableException if the request names a different
     *         code — see the class note. Refused rather than silently ignored:
     *         a caller who believes they renamed something and did not is worse
     *         off than one who is told they cannot.
     */
    @Transactional
    public Optional<ObProductDtos.Product> update(long id, ObProductDtos.WriteRequest request) {
        return products.findById(id).map(row -> {
            String code = normalise(request.code());
            if (!code.equalsIgnoreCase(row.getCode())) {
                throw new ProductCodeImmutableException(row.getCode(), code);
            }
            row.setName(request.name().trim());
            row.setActive(request.activeOrDefault());
            return project(List.of(products.save(row))).get(0);
        });
    }

    /**
     * The three derived fields, in three queries for the whole page rather than
     * three per row — the difference between a catalogue screen and one that
     * gets slower as the catalogue grows.
     *
     * <p><b>{@code totalTatDays} is null only when there is no active
     * template.</b> An active template with no steps yet sums nothing and comes
     * back from {@link ObProductRepository#sumActiveTemplateTatDays} as no row
     * at all, which is indistinguishable there from having no template — so the
     * distinction is made here, against the set that already answers
     * {@code hasActiveTemplate}. The two facts read the same on the OB-07 card
     * if they are collapsed, and they are not the same: one product cannot be
     * bought, the other can and costs nothing yet.
     */
    private List<ObProductDtos.Product> project(List<ObProduct> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(ObProduct::getId).toList();
        Set<Long> withTemplate = Set.copyOf(products.findProductIdsWithAnActiveTemplate(ids));
        Map<Long, Long> tatDays = tally(products.sumActiveTemplateTatDays(ids));
        Map<Long, Long> journeys = tally(products.countJourneysByProduct(ids));
        // C-123 · one more batched read for the catalogue's own three fields —
        // ObJourneyTemplateRepository, not a fourth ObProductRepository query,
        // since `sequence` lives on the template row and the dependency set
        // hangs off it.
        //
        // A product publishes as many active templates as it sells Module
        // Services (V20260910_0030), so the merge function is not decoration:
        // `Collectors.toMap` throws IllegalStateException("Duplicate key") the
        // moment a second one is published, and it throws inside
        // GET /onboarding/products — the OB-04 wizard's product picker, which
        // is how a client is created at all. Keeping the lowest `sequence`
        // makes the three singular fields below describe the product's *first*
        // service, which is the only honest thing they can describe; the
        // catalogue reads listObJourneyTemplates and sees all of them.
        Map<Long, ObJourneyTemplate> activeTemplates = templates.findByProductIdInAndIsActiveTrue(ids).stream()
                .collect(Collectors.toMap(ObJourneyTemplate::getProductId, t -> t,
                        (first, second) -> first.getSequence() <= second.getSequence() ? first : second));

        /*
          And one batched read of their dependency sets. Keyed by *template*
          id, not product id — the map above has already picked one template
          per product, so the two are looked up in sequence rather than
          merged.
        */
        Map<Long, List<Long>> dependsOn = dependencies.findByIdTemplateIdIn(
                        activeTemplates.values().stream().map(ObJourneyTemplate::getId).toList()).stream()
                .collect(Collectors.groupingBy(ObJourneyTemplateDependency::getTemplateId,
                        Collectors.mapping(ObJourneyTemplateDependency::getDependsOnTemplateId,
                                Collectors.toList())));

        return rows.stream()
                .map(row -> {
                    boolean hasTemplate = withTemplate.contains(row.getId());
                    ObJourneyTemplate active = activeTemplates.get(row.getId());
                    return new ObProductDtos.Product(
                            row.getId(),
                            row.getCode(),
                            row.getName(),
                            row.isActive(),
                            hasTemplate,
                            hasTemplate ? Math.toIntExact(tatDays.getOrDefault(row.getId(), 0L)) : null,
                            Math.toIntExact(journeys.getOrDefault(row.getId(), 0L)),
                            active == null ? null : active.getId(),
                            active == null ? null : active.getSequence(),
                            active == null ? List.<Long>of()
                                    : dependsOn.getOrDefault(active.getId(), List.of()));
                })
                .toList();
    }

    private static Map<Long, Long> tally(List<ObProductRepository.Tally> rows) {
        return rows.stream().collect(Collectors.toMap(
                ObProductRepository.Tally::getProductId,
                ObProductRepository.Tally::getTally,
                (a, b) -> a));
    }

    /**
     * Upper-cased and trimmed, so the stored form matches the pattern the
     * contract declares and two callers cannot create {@code LMS} and
     * {@code lms} as different products by disagreeing about case.
     */
    private static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
