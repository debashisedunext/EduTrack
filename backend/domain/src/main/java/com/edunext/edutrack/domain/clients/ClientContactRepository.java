package com.edunext.edutrack.domain.clients;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientContactRepository extends JpaRepository<ClientContact, Long> {

    /** The B-027 child grid, and the C-021 reporter dropdown. */
    List<ClientContact> findByClientIdAndIsActiveTrue(Long clientId);

    /**
     * Returns a list, not one row: "at most one primary" is a service-layer
     * rule the schema cannot assert, so a repository that assumed uniqueness
     * would throw on a state the database permits.
     */
    List<ClientContact> findByClientIdAndIsPrimaryTrue(Long clientId);

    /**
     * The B-028 gate — a client is selectable on a ticket only once it has a
     * live primary contact. One indexed read on
     * {@code ix_client_contacts_primary}, cheap enough to run on every ticket
     * form load.
     */
    boolean existsByClientIdAndIsPrimaryTrueAndIsActiveTrue(Long clientId);

    /**
     * Inbound mail resolving a sender address to the person who reported
     * (D-039). {@code ix_client_contacts_email} is not unique — the same
     * address can legitimately appear under two clients — so this returns the
     * set and the mail engine disambiguates by {@code website_domain}.
     */
    List<ClientContact> findByEmail(String email);
}
