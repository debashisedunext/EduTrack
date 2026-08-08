package com.edunext.edutrack.domain.clients;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientProjectRepository extends JpaRepository<ClientProject, ClientProjectId> {

    /** The clients offered on a project's ticket form (C-021). */
    List<ClientProject> findByIdProjectId(Long projectId);

    /** The other direction: which projects a client is reachable from. */
    List<ClientProject> findByIdClientId(Long clientId);
}
