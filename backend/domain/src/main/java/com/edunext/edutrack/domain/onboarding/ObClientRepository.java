package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * B-102 · the write side of {@code ob_clients}, and nothing else.
 *
 * <h2>Two methods, and both of them are deliberately blind to the caller</h2>
 *
 * <p>Every <em>read</em> of a client that a person will see is scoped by
 * A-112's rule and runs through {@code ObClientReadRepository}, whose SQL
 * carries {@code ObClientScope}'s predicate. This interface is not that door
 * and must never become it: {@link #findById} here is the load-for-mutation
 * that follows a scoped read which has already answered 404, not a way to fetch
 * a client by id.
 *
 * <p>{@link #existsByPanBlindIndex} is unscoped <b>on purpose</b>, and it is
 * the same argument {@code ObJourneyInstantiationService} makes for its own
 * uniqueness guards: "one client per PAN" is a fact about the organisation, not
 * about the caller. Routing it through the scope guard would let a Sales user
 * board a second row for a legal entity another Sales user already boarded,
 * because the first one is invisible to them — turning a row-scope guard into a
 * data-integrity bug, and defeating the one guard plan §1.1 item 6 exists for.
 *
 * <p>The blind index is a one-way HMAC, so answering it discloses nothing about
 * the client that holds it: the caller learns that <em>a</em> PAN they already
 * possess is on file, which is exactly what they need to be told and is the
 * whole content of the {@code 409}.
 */
public interface ObClientRepository extends JpaRepository<ObClient, Long> {

    /**
     * The duplicate guard's question, asked before the insert so the answer is
     * a field-keyed 409 rather than a constraint violation naming a MySQL
     * index. {@code uq_ob_clients_pan_blind} is still what makes it true under
     * a race — the check is the good message, the index is the guarantee.
     */
    boolean existsByPanBlindIndex(byte[] panBlindIndex);

    /**
     * The existing holder, for a message that can say <em>which</em> client
     * already carries this PAN.
     *
     * <p>Named rather than merely counted because "this PAN is already on file"
     * sends the boarder looking through a client list; "this PAN belongs to
     * Horizon Academy" ends the question. Callers must still decide whether the
     * name is theirs to disclose — {@code ObClientWriteService} names it only
     * when the caller's own scope can already see that client.
     */
    Optional<ObClient> findByPanBlindIndex(byte[] panBlindIndex);
}
