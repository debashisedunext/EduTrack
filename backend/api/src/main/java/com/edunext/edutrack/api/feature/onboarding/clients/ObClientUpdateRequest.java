package com.edunext.edutrack.api.feature.onboarding.clients;

import jakarta.validation.constraints.Size;

/**
 * B-102 · {@code PATCH /onboarding/clients/{obClientId}}'s body — the OB-05
 * Client info card.
 *
 * <h2>A class with setters, where every other DTO in this package is a record</h2>
 *
 * <p>The contract says "<b>Partial by field</b>", and four of the six editable
 * fields are nullable: {@code description}, {@code address},
 * {@code salesPersonId} and {@code licenseType}. On a record, an omitted field
 * and an explicit {@code null} both arrive as {@code null} and cannot be told
 * apart — so a caller sending {@code {"name": "…"}} would silently clear the
 * client's address, and a caller trying to clear the address would have no way
 * to say so. Both are wrong and they are the same bug.
 *
 * <p>Phase 1's {@code ClientWriteRequest} sidesteps this by being the whole
 * representation: S-33 submits every field on every save. That is a fair answer
 * for a four-tab form owned by one screen, and it is the wrong answer here,
 * because {@code status} is on this body too — a full-representation PATCH would
 * make every ordinary field edit also re-assert a status, and re-asserting
 * {@code ON_HOLD} while somebody else lifted the hold would put it back without
 * anybody having asked for it.
 *
 * <p>So presence is tracked per field, in the only way Jackson offers without a
 * module: a setter that records it was called. Verbose, and the verbosity is
 * confined to this one class.
 *
 * <p><b>The setters are {@code public} inside a package-private class</b>, and
 * that is not a slip. Jackson's default setter visibility is {@code
 * PUBLIC_ONLY}: package-private setters are not discovered at all, so every
 * field would arrive null with no error anywhere — a PATCH that silently did
 * nothing. The records elsewhere in this package do not have the problem
 * because Jackson binds those through their canonical constructor.
 * {@code ObClientUpdateRequestBindingTest} deserialises real JSON and pins it,
 * because nothing else in the build would notice.
 *
 * <h2>{@code pan} is absent, and its absence is load-bearing</h2>
 *
 * <p>Not an oversight and not a field to add later: PAN is immutable once set
 * (the contract's own words — "a wrong PAN is a wrong client row, and the
 * honest repair is {@code status: DROPPED} and a new one"). {@code ObClient}
 * enforces the same rule a second time in {@code sealPan}, so a field added
 * here would be refused one layer down rather than quietly working.
 */
class ObClientUpdateRequest {

    private String name;
    private boolean nameSet;

    private String description;
    private boolean descriptionSet;

    private String address;
    private boolean addressSet;

    private Long salesPersonId;
    private boolean salesPersonIdSet;

    private String licenseType;
    private boolean licenseTypeSet;

    private String status;
    private boolean statusSet;

    private String statusReason;

    @Size(max = 200)
    String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSet = true;
    }

    boolean hasName() {
        return nameSet;
    }

    String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionSet = true;
    }

    boolean hasDescription() {
        return descriptionSet;
    }

    String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
        this.addressSet = true;
    }

    boolean hasAddress() {
        return addressSet;
    }

    Long getSalesPersonId() {
        return salesPersonId;
    }

    public void setSalesPersonId(Long salesPersonId) {
        this.salesPersonId = salesPersonId;
        this.salesPersonIdSet = true;
    }

    boolean hasSalesPersonId() {
        return salesPersonIdSet;
    }

    @Size(max = 64)
    String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
        this.licenseTypeSet = true;
    }

    boolean hasLicenseType() {
        return licenseTypeSet;
    }

    String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.statusSet = true;
    }

    /**
     * An explicit {@code "status": null} counts as absent rather than as a
     * request to clear it. {@code overall_status} is {@code NOT NULL} and has
     * no empty value — the four of {@code ck_ob_clients_status} are all there
     * is — so the only thing a null could mean is "leave it alone", which is
     * what an omitted field already means.
     */
    boolean hasStatus() {
        return statusSet && status != null;
    }

    @Size(max = 500)
    String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }
}
