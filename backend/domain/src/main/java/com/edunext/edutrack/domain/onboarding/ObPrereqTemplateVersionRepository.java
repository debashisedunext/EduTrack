package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ObPrereqTemplateVersionRepository extends JpaRepository<ObPrereqTemplateVersion, Long> {

    /** The version new clients are snapshotted from. At most one, by unique index. */
    Optional<ObPrereqTemplateVersion> findByIsActiveTrue();

    /**
     * The editable version. At most one, and enforced by
     * {@code uq_ob_prereq_template_versions_draft} rather than only here —
     * see {@link ObPrereqTemplateVersion}'s javadoc for why two would be
     * ambiguous rather than merely untidy.
     */
    Optional<ObPrereqTemplateVersion> findByPublishedAtIsNull();

    Optional<ObPrereqTemplateVersion> findByVersion(int version);

    /** Next version number is this row's {@code version + 1}. */
    Optional<ObPrereqTemplateVersion> findTopByOrderByVersionDesc();
}
