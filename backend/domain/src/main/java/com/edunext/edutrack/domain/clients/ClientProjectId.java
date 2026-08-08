package com.edunext.edutrack.domain.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * The {@code (client_id, project_id)} pair — the identity of a
 * {@link ClientProject}.
 *
 * <p>Both halves stay plain {@code Long} ids rather than associations: the row
 * holds no fact beyond the association itself, and mapping either side would
 * turn a dropdown filter read into two proxy loads per row.
 */
@Embeddable
public class ClientProjectId implements Serializable {

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientProjectId other)) {
            return false;
        }
        return Objects.equals(clientId, other.clientId)
                && Objects.equals(projectId, other.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId, projectId);
    }
}
