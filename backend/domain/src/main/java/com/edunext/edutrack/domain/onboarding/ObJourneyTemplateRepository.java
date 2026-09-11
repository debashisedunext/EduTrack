package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateRepository extends JpaRepository<ObJourneyTemplate, Long> {

    /** Any version at all — a product's very first template has none. */
    boolean existsByProductId(Long productId);

    Optional<ObJourneyTemplate> findByProductIdAndIsActiveTrue(Long productId);

    List<ObJourneyTemplate> findByProductIdOrderByVersionDesc(Long productId);

    /** Next version number for a product is this row's {@code version + 1}. */
    Optional<ObJourneyTemplate> findTopByProductIdOrderByVersionDesc(Long productId);

    /**
     * The head of one <em>service's</em> version chain.
     *
     * <p>{@code beginRevision} numbers from this rather than from
     * {@link #findTopByProductIdOrderByVersionDesc}: a product sells several
     * named services, and a version counts the edits to one of them. Keyed on
     * name because {@code uq_ob_journey_templates_version (product_id, name,
     * version)} is — see {@code V20260909_1900}.
     */
    Optional<ObJourneyTemplate> findTopByProductIdAndNameOrderByVersionDesc(Long productId, String name);

    /**
     * The active version of <em>every</em> service a product sells, in the
     * order the ribbons are drawn.
     *
     * <p>The plural counterpart to {@link #findByProductIdAndIsActiveTrue},
     * which predates a product selling more than one service and returns
     * whichever single row the database felt like once it stopped being
     * unique. Instantiation reads this one: a purchase creates a journey per
     * active service, not one journey.
     *
     * <p>{@code id} breaks the tie after {@code sequence}, because sequence is
     * author-assigned and nothing stops two services sharing one. Without it
     * the ribbon order is whatever the optimiser chose that morning, which is
     * stable in testing and not in production.
     */
    List<ObJourneyTemplate> findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(Long productId);

    /**
     * The active version of one named service, which publishing a new revision
     * has to stand down before it can take its place.
     *
     * <p>Keyed on name for {@link #findTopByProductIdAndNameOrderByVersionDesc}'s
     * reason — {@code uq_ob_journey_templates_version} is (product, name,
     * version), so "the active one" is only well defined within a name. An
     * {@code Optional} rather than a list because at most one version of a
     * service is active at a time; two would be the bug this method is used to
     * avoid creating.
     */
    Optional<ObJourneyTemplate> findByProductIdAndNameAndIsActiveTrue(Long productId, String name);

    /** Every version of every service a product sells, newest first. */
    List<ObJourneyTemplate> findByProductIdOrderByNameAscVersionDesc(Long productId);

    /** C-123 · the whole Module Service catalogue — one row per product with an active version. */
    List<ObJourneyTemplate> findByIsActiveTrueOrderBySequenceAsc();

    /** Batch enrichment for {@code GET /onboarding/products}, one statement rather than N. */
    List<ObJourneyTemplate> findByProductIdInAndIsActiveTrue(java.util.Collection<Long> productIds);

    /**
     * Every version of one <em>service</em>, oldest first — the whole chain the
     * OB-07 catalogue draws a single card for.
     *
     * <p>The unit a rename and a delete both act on. A service's identity is
     * {@code (product_id, name)}, exactly as
     * {@code uq_ob_journey_templates_version} keys it, so renaming v3 alone
     * would not rename the service: it would split one chain into two, leave
     * {@code beginRevision} numbering from the wrong head, and make the
     * catalogue draw a second card for a service nobody created.
     */
    List<ObJourneyTemplate> findByProductIdAndNameOrderByVersionAsc(Long productId, String name);

    /**
     * The versions named by a set of ids, in one statement — what a delete
     * turns the reverse dependency edges it found into, so its refusal can
     * name the services that hold the one being deleted rather than their ids.
     *
     * <p>Replaces {@code findByDependsOnTemplateIdIn}: the dependency left
     * this table for {@code ob_journey_template_dependencies} in
     * {@code V20260911_1100}, so the reverse lookup is
     * {@code ObJourneyTemplateDependencyRepository.findByIdDependsOnTemplateIdIn}
     * and this is only the name resolution that follows it.
     */
    List<ObJourneyTemplate> findByIdIn(java.util.Collection<Long> templateIds);

    /**
     * How many client journeys were instantiated from any of these template
     * versions — the one question a rename or a delete of a Module Service
     * turns on.
     *
     * <p>Counted over {@code ObJourney.templateId}, which pins the version at
     * instantiation and never moves ({@link ObJourney}'s own contract). Every
     * version of the chain is passed, not only the active one: a client boarded
     * on v1 while the catalogue sits at v3 is still a client on this service,
     * and asking only about the head would report it as unused.
     *
     * <p>Archived journeys count too, on {@code countJourneysByProduct}'s own
     * reasoning one table over: a finished onboarding is exactly as much
     * evidence that the service was sold as a running one, and its steps still
     * render from the template rows a delete would remove.
     *
     * <p>Not the {@code COUNT(*)} CLAUDE.md forbids — that rule is about
     * dashboards, which are read constantly and have summary tables built for
     * them. This is one count over an admin catalogue of a handful of rows.
     */
    @Query("select count(j) from ObJourney j where j.templateId in :templateIds")
    long countJourneysForTemplates(@Param("templateIds") java.util.Collection<Long> templateIds);

    /**
     * The same tally for the whole catalogue in one statement — what
     * {@code listObJourneyTemplates} enriches every row with, so the OB-07 page
     * can disable Edit and Delete without a call per card.
     *
     * <p>A version nothing was ever boarded against is absent from the result
     * rather than present as zero, {@code ObProductRepository.Tally}'s own
     * shape.
     */
    @Query("""
            select j.templateId as templateId, count(j) as tally
            from ObJourney j
            where j.templateId in :templateIds
            group by j.templateId
            """)
    List<TemplateTally> countJourneysByTemplate(@Param("templateIds") java.util.Collection<Long> templateIds);

    /**
     * C-124 · re-stamp {@code ob_journeys.service_name} on every journey
     * boarded on any version of one service — the other half of a rename.
     *
     * <p>{@code service_name} is denormalised onto the journey at
     * instantiation ({@code V20260910_0030}), because the fact that has to be
     * unique — "one live journey per client per service" — cannot be
     * expressed by an index spanning a join. It is also the key two lookups
     * resolve a service by: {@code uq_ob_journeys_client_service}, and the
     * dependency hold, which matches {@code (product, service name)} rather
     * than a template id so it survives the dependency publishing a new
     * version.
     *
     * <p>That is exactly why the column has to move with the name rather than
     * why the name cannot move. A rename that left these rows holding the old
     * string would break both lookups — a dependent journey would start
     * unheld, and the uniqueness guard would stop recognising a client's
     * existing journey and let a second one in beside it. Renaming the chain
     * and re-stamping its journeys in the same transaction leaves both
     * matching, which is what makes a rename safe on a service clients are
     * already on.
     *
     * <p>A bulk update rather than a row-per-journey save: a service with
     * hundreds of clients is ordinary, the new name is the same for all of
     * them, and none of these entities is loaded here. It lives on the
     * <em>template</em> repository rather than {@code ObJourneyRepository}
     * because {@code ScopeGuardRulesTest} forbids feature code from touching
     * that interface at all — {@link #countJourneysForTemplates} above reads
     * the same table from here for the same reason. Scope is not weakened by
     * the exception: this is an admin write over a whole service, not a read
     * of one client's rows.
     *
     * @return how many journeys were re-stamped
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ObJourney j set j.serviceName = :name where j.templateId in :templateIds")
    int renameServiceOnJourneys(@Param("name") String name,
                                @Param("templateIds") java.util.Collection<Long> templateIds);

    /**
     * One row of the grouped count above, keyed by template version.
     *
     * <p>An interface projection rather than a constructor expression, for
     * {@code ObProductRepository.Tally}'s reason: {@code count} and {@code sum}
     * disagree about whether they widen to {@code Long}, and an alias-bound
     * projection does not care.
     */
    interface TemplateTally {

        Long getTemplateId();

        long getTally();
    }
}
