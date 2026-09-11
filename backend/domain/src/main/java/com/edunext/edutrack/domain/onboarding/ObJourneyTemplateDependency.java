package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * One edge of the service-level dependency graph (plan §5.5): this template
 * version waits behind that one.
 *
 * <h2>Why a row per edge, and not a column</h2>
 *
 * <p>It was a column — {@code ob_journey_templates.depends_on_template_id} —
 * until {@code V20260911_1100}, which is why so much of the surrounding
 * javadoc still speaks of "the dependency" in the singular. A service waits
 * behind a <em>set</em>: the plan's own example, a biometric rollout after the
 * ERP service, is a set of one only because the example is small.
 *
 * <h2>Cycle-freedom is the service layer's, still</h2>
 *
 * <p>{@code ck_ob_jt_dependencies_not_self} refuses the one-hop case, which
 * the old column could not express (MySQL 8.4 answers ERROR 3818 for a CHECK
 * naming an auto-increment column, and {@code id} was one). That is the base
 * case and not the check: the graph is cross-product by design and unbounded
 * in depth, a CHECK cannot see another row, and
 * {@code ObJourneyTemplateService#updateDependsOn} owns the transitive walk.
 * A reader who takes the constraint as evidence the database guards cycles
 * will not write the check, and the first cycle will be found by a journey
 * that never starts.
 *
 * <h2>Edges are between versions, held between journeys</h2>
 *
 * <p>Both ids name template <em>versions</em>, because that is what a row of
 * {@code ob_journey_templates} is. Instantiation does not resolve the holder
 * by that id — see {@code ObJourneyInstantiationService#holdingJourneysFor},
 * which resolves through the dependency's {@code (product, name)} service
 * instead, so a dependency publishing v2 does not leave every dependent
 * journey unheld.
 */
@Entity
@Table(name = "ob_journey_template_dependencies")
public class ObJourneyTemplateDependency {

    @EmbeddedId
    private ObJourneyTemplateDependencyId id;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public ObJourneyTemplateDependency() {
    }

    public ObJourneyTemplateDependency(long templateId, long dependsOnTemplateId) {
        this.id = new ObJourneyTemplateDependencyId(templateId, dependsOnTemplateId);
    }

    public ObJourneyTemplateDependencyId getId() {
        return id;
    }

    public void setId(ObJourneyTemplateDependencyId id) {
        this.id = id;
    }

    public Long getTemplateId() {
        return id == null ? null : id.getTemplateId();
    }

    public Long getDependsOnTemplateId() {
        return id == null ? null : id.getDependsOnTemplateId();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
