package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateRepository extends JpaRepository<ObJourneyTemplate, Long> {

    /** Any version at all — a product's very first template has none. */
    boolean existsByProductId(Long productId);

    Optional<ObJourneyTemplate> findByProductIdAndIsActiveTrue(Long productId);

    List<ObJourneyTemplate> findByProductIdOrderByVersionDesc(Long productId);

    /** Next version number for a product is this row's {@code version + 1}. */
    Optional<ObJourneyTemplate> findTopByProductIdOrderByVersionDesc(Long productId);
}
