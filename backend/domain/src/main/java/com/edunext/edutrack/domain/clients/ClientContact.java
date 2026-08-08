package com.edunext.edutrack.domain.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A person at the client — B-027, blueprint §4B.2.
 *
 * <p>The ticket form's second dropdown reads this table: pick a client, then
 * pick the individual who reported the issue (C-021).
 *
 * <p><b>{@code isPrimary} is deliberately not unique.</b> B-028 requires at
 * least one primary contact before a client is selectable on a ticket, and "at
 * least one" is not a UNIQUE key; "at most one" would need a partial unique
 * index, which MySQL does not have. Both halves live in the service layer — the
 * flag is stored and indexed so the gate is one indexed read, not a scan.
 */
@Entity
@Table(name = "client_contacts")
public class ClientContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Genuine ownership — a contact has no meaning apart from its client, and
     * the B-027 child grid walks exactly this edge. {@code LAZY} because the
     * ticket form resolves contacts by client id without needing the row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "designation", length = 80)
    private String designation;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    /** The B-028 gate; uniqueness is not enforceable here — see the class note. */
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    /** Feeds the D-036 recipient list. */
    @Column(name = "receives_mail", nullable = false)
    private boolean receivesMail = true;

    /** Forward hook for client portal login; unused in phase 1. */
    @Column(name = "portal_access", nullable = false)
    private boolean portalAccess;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public void setPrimary(boolean primary) {
        this.isPrimary = primary;
    }

    public boolean isReceivesMail() {
        return receivesMail;
    }

    public void setReceivesMail(boolean receivesMail) {
        this.receivesMail = receivesMail;
    }

    public boolean isPortalAccess() {
        return portalAccess;
    }

    public void setPortalAccess(boolean portalAccess) {
        this.portalAccess = portalAccess;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
