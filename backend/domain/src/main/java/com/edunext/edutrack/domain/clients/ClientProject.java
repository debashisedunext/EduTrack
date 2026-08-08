package com.edunext.edutrack.domain.clients;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Which clients are reachable from which project.
 *
 * <p>The ticket form filters its client dropdown to the clients mapped to the
 * ticket's project (C-021), so this table is read on every ticket create. The
 * pair is the identity — hence {@link ClientProjectId} rather than a surrogate
 * key.
 *
 * <p>This is the one place a delete is harmless: the row carries no fact of its
 * own, only the association, so removing it never destroys history the way
 * deleting a client would (B-029).
 */
@Entity
@Table(name = "client_projects")
public class ClientProject {

    @EmbeddedId
    private ClientProjectId id;

    /** Pre-selects this client when a ticket is raised on the project. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public ClientProjectId getId() {
        return id;
    }

    public void setId(ClientProjectId id) {
        this.id = id;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
