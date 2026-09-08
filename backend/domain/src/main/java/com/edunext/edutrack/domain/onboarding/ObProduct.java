package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A-124 · {@code ob_products}, the catalogue journey templates bind to.
 *
 * <p>The table has existed since A-101's migration, which created it early
 * because {@code ob_client_applications.product_id} and
 * {@code ob_journey_templates.product_id} both point at it — a catalogue
 * declared after its dependants would have meant two migrations where one
 * would do. This is the mapping and the master API over it, not the schema.
 *
 * <h2>Retiring is not deleting, and there is no delete</h2>
 *
 * <p>{@code isActive} is the only lifecycle. A product a client was boarded
 * against still has journeys running from it, so removing the row would leave
 * those journeys naming a product nothing can resolve — and the contract's own
 * note says the name "has to render somewhere". The list therefore returns
 * inactive products marked rather than filtered, and the OB-04 wizard is the
 * caller that asks for {@code isActive=true}.
 */
@Entity
@Table(name = "ob_products")
public class ObProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique case-insensitively, which the column's collation
     * ({@code utf8mb4_0900_ai_ci}) already enforces — the service still checks
     * first so the refusal names the field rather than a MySQL constraint.
     */
    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    protected ObProduct() {
    }

    public ObProduct(String code, String name, boolean isActive, Long createdBy) {
        this.code = code;
        this.name = name;
        this.isActive = isActive;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
