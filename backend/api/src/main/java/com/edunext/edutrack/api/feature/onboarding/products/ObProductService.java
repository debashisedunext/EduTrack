package com.edunext.edutrack.api.feature.onboarding.products;

import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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

    ObProductService(ObProductRepository products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<ObProductDtos.Product> list(Boolean isActive) {
        List<ObProduct> rows = isActive == null
                ? products.findAllByOrderByNameAsc()
                : products.findAllByIsActiveOrderByNameAsc(isActive);
        return withTemplateFlag(rows);
    }

    @Transactional(readOnly = true)
    public Optional<ObProductDtos.Product> find(long id) {
        return products.findById(id).map(row -> withTemplateFlag(List.of(row)).get(0));
    }

    @Transactional
    public ObProductDtos.Product create(ObProductDtos.WriteRequest request, Long createdBy) {
        String code = normalise(request.code());
        products.findByCode(code).ifPresent(existing -> {
            throw new DuplicateProductCodeException(code);
        });

        ObProduct saved = products.save(
                new ObProduct(code, request.name().trim(), request.activeOrDefault(), createdBy));
        return withTemplateFlag(List.of(saved)).get(0);
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
            return withTemplateFlag(List.of(products.save(row))).get(0);
        });
    }

    /**
     * One query for the whole page rather than one per row — the difference
     * between a catalogue screen and one that gets slower as the catalogue
     * grows.
     */
    private List<ObProductDtos.Product> withTemplateFlag(List<ObProduct> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(ObProduct::getId).toList();
        Set<Long> withTemplate = Set.copyOf(products.findProductIdsWithAnActiveTemplate(ids));
        return rows.stream()
                .map(row -> new ObProductDtos.Product(
                        row.getId(),
                        row.getCode(),
                        row.getName(),
                        row.isActive(),
                        withTemplate.contains(row.getId())))
                .toList();
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
