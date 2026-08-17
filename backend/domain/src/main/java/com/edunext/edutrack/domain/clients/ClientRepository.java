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
     *
     * <p><b>B-034 widened this from a projection of the code to the whole
     * row</b>, because blueprint §4B.3's step-4 table names the fields an update
     * would change and that cannot be answered from a list of codes. It costs
     * the entities rather than one column for the matched subset — bounded by
     * the 5,000-row import cap and by how many of those codes exist, and the
     * same order of memory as the staged sheet the caller is already holding.
     * The caller reads them inside a read-only transaction and keeps only
     * strings.
     */
    @Query("select c from Client c where c.clientCode in :codes")
    List<Client> findByClientCodeIn(@Param("codes") Collection<String> codes);
}
