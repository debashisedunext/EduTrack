package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code ob_clients} — the onboarding client master (OB-03/OB-04/OB-05).
 *
 * <h2>Widened by B-102, exactly as A-112 asked</h2>
 *
 * <p>A-112 mapped two columns of this table and no more, because
 * {@code OnboardingScopeResolver} needed a mapped type on the other side of a
 * criteria subquery and nothing else existed to build one from. Its note said
 * what to do next in as many words: <em>"when B-102 lands, <b>widen this
 * class</b> rather than adding a second {@code @Entity} on {@code ob_clients} —
 * two entities over one table is legal in JPA and is how a table ends up with
 * two disagreeing notions of what a client is."</em> This is that widening, and
 * nothing about the scope rule below it changed.
 *
 * <h2>The two PAN columns are written together or not at all</h2>
 *
 * <p>{@link #getPanCiphertext()} is AES-GCM output and
 * {@link #getPanBlindIndex()} is a deterministic HMAC. The migration's header
 * has the full argument for the split; what matters at this layer is that
 * ciphertext without an index is a row the duplicate guard cannot see, and an
 * index without ciphertext is a PAN that can be matched but never read back.
 * {@link #sealPan} takes both at once so that no caller can write one, and
 * there is no setter for either alone.
 *
 * <p>There is also no <em>re</em>-seal. {@code pan} is immutable once set — the
 * contract omits it from {@code ObClientUpdateRequest} deliberately, because it
 * is the duplicate guard's key and an identity fact rather than a correctable
 * typo. {@link #sealPan} refuses a second call rather than trusting every
 * future caller to remember that.
 *
 * <h2>{@code created_by}, not {@code sales_person_id}</h2>
 *
 * <p>Both columns exist and they are not the same fact. Plan §3 says Sales sees
 * "Clients they created", and A-112's backlog entry says {@code created_by = me}
 * — so the scope follows authorship, not the sales owner named on the record.
 * The difference bites when a client is boarded by one person and assigned to
 * another: the assignee is <em>not</em> given visibility by this rule, and if
 * that turns out to be wrong it is a plan change, not a fix here.
 */
@Entity
@Table(name = "ob_clients")
public class ObClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "onboarding_date", nullable = false)
    private LocalDate onboardingDate;

    /** AES-GCM. Written only through {@link #sealPan}; read only by {@code PanService}. */
    @Column(name = "pan_ciphertext")
    private byte[] panCiphertext;

    /**
     * HMAC-SHA256, and the column {@code uq_ob_clients_pan_blind} is on.
     *
     * <p>{@code BINARY(32)} <em>pads</em> rather than truncating, so a value of
     * the wrong length comes back silently altered instead of failing — which
     * is why {@code PanBlindIndex} is the only producer.
     *
     * <p><b>{@code columnDefinition}, not {@code length}.</b> A {@code byte[]}
     * maps to {@code VARBINARY} by default, and {@code length = 32} would make
     * that {@code varbinary(32)} — a different type from the fixed-width
     * {@code BINARY(32)} A-101 created. Hibernate's schema validation refuses
     * to start on the mismatch, which is the good outcome: the padding
     * behaviour above is precisely the difference between the two types, and an
     * entity that quietly agreed with the wrong one would be a duplicate guard
     * matching against values the column had altered.
     */
    @Column(name = "pan_blind_index", columnDefinition = "binary(32)")
    private byte[] panBlindIndex;

    @Column(name = "address", columnDefinition = "text")
    private String address;

    @Column(name = "sales_person_id")
    private Long salesPersonId;

    @Column(name = "license_type", length = 64)
    private String licenseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status", nullable = false, length = 20)
    private ObClientStatus overallStatus = ObClientStatus.ONBOARDING;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    /** Stamped by the go-live flip (plan §5.9), never by a request. */
    @Column(name = "live_at")
    private Instant liveAt;

    /**
     * The user who boarded this client.
     *
     * <p><b>Nullable in the schema</b>, and the scope rule depends on knowing
     * that. Equality never matches NULL, so a client with no recorded author is
     * visible to nobody in the OB_SALES role — not to the person who actually
     * boarded it, and not to every Sales user at once. Deny is the right
     * direction for a row whose ownership is unknown, and it is the direction
     * SQL gives for free here; it is written down because the opposite reading
     * ("nobody created it, so it is everyone's") is the one someone would
     * implement if they tried to be helpful about the NULL.
     *
     * <p>Such a row is still reachable by OB_ADMIN, OB_MANAGER and OB_VIEWER,
     * so it is not lost — it is unowned, which is a data problem for the
     * onboarding admin rather than a hole in the guard.
     */
    @Column(name = "created_by")
    private Long createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected ObClient() {
        // JPA
    }

    /**
     * A new client, with the three facts the table refuses to be without.
     *
     * <p>{@code name} and {@code onboarding_date} are {@code NOT NULL}, and
     * {@code created_by} is the column A-112's Sales scope reads — a client
     * boarded without it is visible to no Sales user at all, including the one
     * who boarded it. Taking all three at construction makes that a compile
     * error rather than a row that looks fine until somebody in Sales cannot
     * find it.
     */
    public ObClient(String name, LocalDate onboardingDate, Long createdBy) {
        this.name = name;
        this.onboardingDate = onboardingDate;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getOnboardingDate() {
        return onboardingDate;
    }

    public void setOnboardingDate(LocalDate onboardingDate) {
        this.onboardingDate = onboardingDate;
    }

    public byte[] getPanCiphertext() {
        return panCiphertext;
    }

    public byte[] getPanBlindIndex() {
        return panBlindIndex;
    }

    /** True once a PAN has been recorded — the state {@link #sealPan} refuses to overwrite. */
    public boolean hasPan() {
        return panBlindIndex != null;
    }

    /**
     * Record a PAN, both columns at once and once only.
     *
     * @throws IllegalStateException on a second call. The contract makes
     *         {@code pan} immutable once set, and enforcing it here rather than
     *         in one service means a later caller — a bulk import, a merge tool
     *         — cannot reach the column without meeting the same rule. A wrong
     *         PAN is a wrong client row, and the honest repair is
     *         {@code DROPPED} plus a new one.
     */
    public void sealPan(byte[] ciphertext, byte[] blindIndex) {
        if (hasPan()) {
            throw new IllegalStateException(
                    "PAN is immutable once set. Client " + id + " already carries one; "
                            + "correct a wrong PAN by dropping the client and boarding it again.");
        }
        if (ciphertext == null || blindIndex == null) {
            throw new IllegalArgumentException(
                    "A PAN is its ciphertext and its blind index together — one without the "
                            + "other is either a row the duplicate guard cannot see or a value "
                            + "that can never be read back.");
        }
        this.panCiphertext = ciphertext;
        this.panBlindIndex = blindIndex;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Long getSalesPersonId() {
        return salesPersonId;
    }

    public void setSalesPersonId(Long salesPersonId) {
        this.salesPersonId = salesPersonId;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public ObClientStatus getOverallStatus() {
        return overallStatus;
    }

    /**
     * The three statuses a person may record.
     *
     * @throws IllegalArgumentException for {@link ObClientStatus#LIVE}, which is
     *         {@link #goLive}'s to stamp. The API answers 422 before reaching
     *         here; this is the same rule stated a second time, for the callers
     *         that are not the API.
     */
    public void recordStatus(ObClientStatus status, String reason) {
        if (!status.settableByHand()) {
            throw new IllegalArgumentException(
                    "LIVE is earned when every journey completes with its sign-offs, never set. "
                            + "See ObClientStatus.");
        }
        this.overallStatus = status;
        // Cleared on the way back to ONBOARDING: a reason left behind by a hold
        // that has since been lifted reads on OB-05 as though the client were
        // still held.
        this.statusReason = status.requiresReason() ? reason : null;
    }

    /** Plan §5.9's flip. Not a setter — the timestamp and the status move together or not at all. */
    public void goLive(Instant at) {
        this.overallStatus = ObClientStatus.LIVE;
        this.statusReason = null;
        this.liveAt = at;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getLiveAt() {
        return liveAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
