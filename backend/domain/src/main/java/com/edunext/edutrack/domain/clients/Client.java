package com.edunext.edutrack.domain.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The client master — blueprint §4B.2, screens S-32/S-33.
 *
 * <p><b>Clients are deactivated, never deleted</b> (B-029). Historical tickets
 * must keep resolving their client, so nothing pointing here cascades.
 * Deactivating warns and blocks <em>new</em> tickets; it never hides existing
 * ones.
 *
 * <p>{@code clientCode} is the natural key <em>and</em> the upsert key for the
 * Excel import — B-035 updates the matching row, never inserts a duplicate.
 * That is why the column is UNIQUE rather than merely indexed.
 *
 * <p>B-028: a client is not selectable on a ticket until it has a primary
 * contact. Neither half of that rule ("at least one", "at most one") is
 * expressible as a MySQL key, so both live in the service layer — see
 * {@link ClientContact}.
 */
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Natural key, and the upsert key for the B-035 import. */
    @Column(name = "client_code", nullable = false, length = 20)
    private String clientCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "short_name", length = 60)
    private String shortName;

    @Column(name = "industry", length = 80)
    private String industry;

    /** ACTIVE | INACTIVE — B-029 flips this instead of deleting. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /**
     * Points at {@code users} but is not ownership — an actor id, so it stays a
     * scalar {@code Long} per package-info. Mapping it {@code @ManyToOne} would
     * hang a lazy proxy off every row the client report touches and buy nothing.
     */
    @Column(name = "account_manager_id")
    private Long accountManagerId;

    /** BASIC | STANDARD | PREMIUM | … */
    @Column(name = "support_plan", length = 20)
    private String supportPlan;

    /** Overrides the project's default SLA policy when set. */
    @Column(name = "sla_policy_id")
    private Long slaPolicyId;

    @Column(name = "primary_email", length = 150)
    private String primaryEmail;

    @Column(name = "support_email", length = 150)
    private String supportEmail;

    @Column(name = "phone", length = 30)
    private String phone;

    /** Auto-matches inbound mail to this client (D-039); indexed for that read. */
    @Column(name = "website_domain", length = 120)
    private String websiteDomain;

    @Column(name = "address_line1", length = 150)
    private String addressLine1;

    @Column(name = "address_line2", length = 150)
    private String addressLine2;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "state", length = 80)
    private String state;

    @Column(name = "country", length = 80)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /**
     * Presentation layer only. Every instant in this schema is UTC (PLAN.md
     * §3.1); this decides how the client's own hours and client-facing SLA
     * windows are rendered, never how anything is stored.
     */
    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Kolkata";

    @Column(name = "contract_start")
    private LocalDate contractStart;

    @Column(name = "contract_end")
    private LocalDate contractEnd;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    /** Traces a bulk-imported row back to its batch so B-037 can reverse the set. */
    @Column(name = "import_batch_id")
    private Long importBatchId;

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

    public String getClientCode() {
        return clientCode;
    }

    public void setClientCode(String clientCode) {
        this.clientCode = clientCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAccountManagerId() {
        return accountManagerId;
    }

    public void setAccountManagerId(Long accountManagerId) {
        this.accountManagerId = accountManagerId;
    }

    public String getSupportPlan() {
        return supportPlan;
    }

    public void setSupportPlan(String supportPlan) {
        this.supportPlan = supportPlan;
    }

    public Long getSlaPolicyId() {
        return slaPolicyId;
    }

    public void setSlaPolicyId(Long slaPolicyId) {
        this.slaPolicyId = slaPolicyId;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public void setPrimaryEmail(String primaryEmail) {
        this.primaryEmail = primaryEmail;
    }

    public String getSupportEmail() {
        return supportEmail;
    }

    public void setSupportEmail(String supportEmail) {
        this.supportEmail = supportEmail;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getWebsiteDomain() {
        return websiteDomain;
    }

    public void setWebsiteDomain(String websiteDomain) {
        this.websiteDomain = websiteDomain;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public LocalDate getContractStart() {
        return contractStart;
    }

    public void setContractStart(LocalDate contractStart) {
        this.contractStart = contractStart;
    }

    public LocalDate getContractEnd() {
        return contractEnd;
    }

    public void setContractEnd(LocalDate contractEnd) {
        this.contractEnd = contractEnd;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
