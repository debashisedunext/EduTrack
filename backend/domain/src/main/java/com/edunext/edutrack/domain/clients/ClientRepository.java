package com.edunext.edutrack.domain.clients;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
