package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The write side of {@code ob_projects}, and the two questions that are about
 * the organisation rather than about the caller.
 *
 * <h2>This is not the door the Projects grid reads through</h2>
 *
 * <p>{@code ObProjectReadRepository} carries {@code ObClientScope}'s predicate
 * and is what every read a person sees goes through, on
 * {@link ObClientRepository}'s own division. {@link #findById} here is the
 * load-for-mutation that <em>follows</em> a scoped read which has already
 * answered 404 — never a way to fetch a project by id.
 *
 * <p>{@link #existsByObClientIdAndProductId} is unscoped on purpose, and it is
 * {@link ObClientRepository#existsByPanBlindIndex}'s argument applied to a
 * different key: "one project per client per product" is a fact about the
 * organisation. Routing it through the scope guard would let one salesperson
 * create a second project for a pair another salesperson already has, because
 * the first is invisible to them — turning the row-scope guard into a
 * duplicate-row bug, and defeating {@code uq_ob_projects_client_product}'s
 * whole purpose. The answer discloses nothing the caller did not already supply.
 */
public interface ObProjectRepository extends JpaRepository<ObProject, Long> {

    /**
     * The duplicate guard's question, asked before the insert so the answer is
     * a field-keyed 409 rather than a constraint violation naming a MySQL
     * index. {@code uq_ob_projects_client_product} is still what makes it true
     * under a race — the check is the good message, the index is the guarantee.
     */
    boolean existsByObClientIdAndProductId(Long obClientId, Long productId);

    /**
     * Whether this client has any project at all — the first clause of the
     * delete guard on {@code DELETE /onboarding/clients/{obClientId}}.
     *
     * <p>Unscoped for the same reason as above, and here it matters more: a
     * scoped count would let a caller delete a client whose only project
     * belongs to somebody else's scope, which is the precise case the guard
     * exists to refuse. {@code fk_ob_projects_client} would still refuse the
     * row, but as a constraint violation rather than as a sentence naming what
     * is in the way.
     */
    boolean existsByObClientId(Long obClientId);

    /**
     * Every project of one client, for the client detail page's project cards.
     *
     * <p>Bounded by construction — a client has as many projects as it has
     * bought products — so this returns a list rather than a page.
     */
    List<ObProject> findByObClientIdOrderByStartDateDescIdDesc(Long obClientId);

    /**
     * The project a journey's pair resolves to, for the one caller that has the
     * pair and not the id: {@code ObJourneyInstantiationService}, when a
     * product bought after gate-open instantiates into an existing project.
     */
    Optional<ObProject> findByObClientIdAndProductId(Long obClientId, Long productId);
}
