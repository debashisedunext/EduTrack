package com.edunext.edutrack.domain.clients;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * The upsert key for the Excel import (B-035): a matching code updates the
     * existing client, it never inserts a second one. Backed by
     * {@code uq_clients_code}, so this is also the pre-commit dry-run's
     * create-vs-update decision in step 4.
     */
    Optional<Client> findByClientCode(String clientCode);

    /**
     * Inbound-mail auto-match (D-039) — resolved per message, not per page
     * load, which is why the column is indexed. Returns the set:
     * {@code ix_clients_domain} is not unique, and an ambiguous domain should
     * fall through to manual attribution rather than throw inside the mail
     * poller.
     */
    List<Client> findByWebsiteDomain(String websiteDomain);

    /** ACTIVE | INACTIVE. B-029 deactivates; nothing here ever deletes. */
    List<Client> findByStatusOrderByNameAsc(String status);

    /** Everything one import run touched — B-037 reverses a bad batch as a set. */
    List<Client> findByImportBatchId(Long importBatchId);

    /**
     * Which of these codes already exist — B-030's dry run deciding create
     * versus update for a whole file in <b>one</b> query.
     *
     * <p>The obvious alternative, calling {@link #findByClientCode} per row, is
     * 5,000 round trips for the step users repeat most because it is the safe
     * one.
     *
     * <p>No {@code upper()} on the column, deliberately: {@code client_code}
     * collates {@code utf8mb4_0900_ai_ci}, so MySQL already matches {@code acme}
     * against {@code ACME} — and does it through {@code uq_clients_code} rather
     * than scanning, which wrapping the column in a function would prevent.
     * Codes come back in their stored case; the caller re-normalises.
     */
    @Query("select c.clientCode from Client c where c.clientCode in :codes")
    List<String> findClientCodesIn(@Param("codes") Collection<String> codes);
}
