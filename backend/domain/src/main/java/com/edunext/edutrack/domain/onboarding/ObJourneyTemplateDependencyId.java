package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * The {@code (template_id, depends_on_template_id)} pair — the identity of an
 * {@link ObJourneyTemplateDependency}.
 *
 * <p>{@link com.edunext.edutrack.domain.clients.ClientProjectId}'s shape, for
 * its reasons: the row holds no fact beyond the association, so both halves
 * stay plain {@code Long} ids rather than associations, and the pair being the
 * primary key is what makes declaring the same dependency twice a
 * duplicate-key refusal instead of a journey held twice behind one service.
 */
@Embeddable
public class ObJourneyTemplateDependencyId implements Serializable {

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "depends_on_template_id", nullable = false)
    private Long dependsOnTemplateId;

    public ObJourneyTemplateDependencyId() {
    }

    public ObJourneyTemplateDependencyId(Long templateId, Long dependsOnTemplateId) {
        this.templateId = templateId;
        this.dependsOnTemplateId = dependsOnTemplateId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getDependsOnTemplateId() {
        return dependsOnTemplateId;
    }

    public void setDependsOnTemplateId(Long dependsOnTemplateId) {
        this.dependsOnTemplateId = dependsOnTemplateId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObJourneyTemplateDependencyId other)) {
            return false;
        }
        return Objects.equals(templateId, other.templateId)
                && Objects.equals(dependsOnTemplateId, other.dependsOnTemplateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, dependsOnTemplateId);
    }
}
