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
 * {@code ob_projects} — one engagement: this client, this product, these module
 * services.
 *
 * <h2>What moved here from {@link ObClient}, and what did not</h2>
 *
 * <p>A client row used to be two things at once — a company, and the work being
 * done for it. The second half is this table. {@code start_date},
 * {@code sales_person_id} and {@code implementor_user_id} describe the
 * engagement; {@code name}, {@code address}, {@code city} and
 * {@code client_code} stay on the client and describe the company.
 *
 * <p>The client's own columns for the first three are <b>not</b> removed and
 * are not the same fact any more: {@code ob_clients.onboarding_date} is when
 * that company was first boarded, and {@code ob_clients.sales_person_id} is who
 * boarded them. A client boarded in March by one salesperson can start a second
 * project in November sold by another, and before this table there was nowhere
 * to record that.
 *
 * <h2>The pair is the identity; the name is a label</h2>
 *
 * <p>{@code uq_ob_projects_client_product} carries across from
 * {@code uq_ob_client_applications}, which has held (client, product) unique
 * since the module's first migration. So {@link #getName()} is free to be
 * whatever somebody types, is not unique, and nothing resolves a project
 * through it.
 *
 * <h2>{@link #getStatus()} and the one value nobody may type</h2>
 *
 * <p>{@link ObProjectStatus#COMPLETED} is stamped when the project's last
 * journey completes, never accepted from a request — see the enum. The
 * constructor therefore takes no status at all: every project is born
 * {@link ObProjectStatus#RUNNING}, including one created for a product whose
 * services are all held behind another project's.
 */
@Entity
@Table(name = "ob_projects")
public class ObProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * The engagement's start, and the date tentative completion is measured
     * from.
     *
     * <p>Not derived from the first journey step's activation, which is a
     * different and later event: a project starting on the 15th whose
     * prerequisites clear on the 30th has been running for a fortnight, and
     * reporting it as starting on the 30th would hide exactly the delay this
     * column exists to make visible.
     */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "sales_person_id")
    private Long salesPersonId;

    /** Who is running the implementation. Null until somebody is assigned — an ordinary state. */
    @Column(name = "implementor_user_id")
    private Long implementorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ObProjectStatus status = ObProjectStatus.RUNNING;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "created_by")
    private Long createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected ObProject() {
        // JPA
    }

    /**
     * A new project, with the five facts the table refuses to be without plus
     * the two it will not let a caller forget.
     *
     * <p>{@code salesPersonId} and {@code implementorUserId} are nullable
     * columns and are still constructor parameters rather than setters, on
     * {@link ObClient}'s own reasoning for {@code createdBy}: they are asked for
     * on the create form, so a project built without them is a form somebody
     * dropped on the floor rather than a project that genuinely has none. A
     * caller with nothing to pass passes null and has said so.
     */
    public ObProject(Long obClientId, Long productId, String name, LocalDate startDate,
                     Long salesPersonId, Long implementorUserId, Long createdBy) {
        this.obClientId = obClientId;
        this.productId = productId;
        this.name = name;
        this.startDate = startDate;
        this.salesPersonId = salesPersonId;
        this.implementorUserId = implementorUserId;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getObClientId() {
        return obClientId;
    }

    /**
     * <b>No setter, and none for {@link #getObClientId()} either.</b> The pair
     * is the identity of the row, not two fields on it —
     * {@code uq_ob_projects_client_product} is over it, the journeys were
     * instantiated from that product's module services, and every one of them
     * pins a template that belongs to it. Moving a project to another product
     * would leave all of that describing the old one.
     * {@code ObApplicationService} refuses the same edit on the purchase row
     * for the same reason.
     */
    public Long getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Long getSalesPersonId() {
        return salesPersonId;
    }

    public void setSalesPersonId(Long salesPersonId) {
        this.salesPersonId = salesPersonId;
    }

    public Long getImplementorUserId() {
        return implementorUserId;
    }

    public void setImplementorUserId(Long implementorUserId) {
        this.implementorUserId = implementorUserId;
    }

    public ObProjectStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    /**
     * The two are set together or not at all.
     *
     * <p>{@link ObProjectStatus#requiresReason()} names the pair that cannot be
     * recorded silently, and a separate {@code setStatusReason} would let a
     * caller satisfy that check on one line and undo it on the next. The
     * service validates; this signature is what stops the validation being
     * bypassed by accident.
     *
     * <p>It accepts {@link ObProjectStatus#COMPLETED} — {@link #complete()} is
     * the only caller that should pass it, and the refusal of a hand-set
     * completion lives in the service where the request is, not here where a
     * legitimate internal caller would have to work around it.
     */
    public void recordStatus(ObProjectStatus status, String statusReason) {
        this.status = status;
        this.statusReason = statusReason;
    }

    /** The earned transition: every journey of this project has completed. */
    public void complete() {
        this.status = ObProjectStatus.COMPLETED;
        this.statusReason = null;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
