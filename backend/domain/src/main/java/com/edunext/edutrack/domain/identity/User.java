package com.edunext.edutrack.domain.identity;

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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A resource — blueprint §8.2, maintained by the Resource Master (S-07).
 *
 * <p><b>{@code role} is authoritative for authentication.</b> §2's matrix is
 * per-role, A-022's JWT carries a singular {@code role} claim and A-034's
 * {@code ScopeResolver} switches on one role. {@link UserRole} is the dormant
 * multi-role seam for blueprint §16 and is expected to be empty — never read it
 * to decide what a user may do.
 *
 * <p><b>{@code reportingManagerId} cycles must be checked at any depth.</b> The
 * A-003 triggers only reject the depth-1 self-reference {@code id =
 * reporting_manager_id}; a trigger cannot walk the chain without a recursive
 * query per write. A→B→A, and anything longer, is the Resource Master
 * service's job before it saves.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emp_code", nullable = false, length = 20)
    private String empCode;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    /** Argon2id (A-020). Never leaves the service layer — no DTO carries it. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "mobile", length = 20)
    private String mobile;

    /**
     * Ownership, not an actor reference: a user cannot exist without a role
     * (A-003 made the column NOT NULL precisely so scope is never undefined),
     * and the resource list renders the role name on every row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /**
     * Scalar by design, and the first of several such columns in this package.
     * It points at {@code users} but it is a graph edge, not ownership —
     * mapping it as a {@code @ManyToOne} would hang a lazy proxy off every row
     * the resource list and the org chart touch, and the cycle check needs the
     * raw ids anyway to walk upward without loading a chain of entities.
     * Package-info states the general rule; {@code manager_id},
     * {@code assigned_by}, {@code added_by} and {@code user_id} follow it.
     */
    @Column(name = "reporting_manager_id")
    private Long reportingManagerId;

    @Column(name = "department", length = 80)
    private String department;

    @Column(name = "designation", length = 80)
    private String designation;

    /**
     * Denominator for the capacity heatmap (S-24) and for every utilisation
     * figure — {@code BigDecimal} because those are divisions, not counters.
     */
    @Column(name = "daily_capacity_hrs", nullable = false, precision = 4, scale = 2)
    private BigDecimal dailyCapacityHrs = new BigDecimal("8.00");

    /** Presentation only. Storage is UTC everywhere (PLAN.md §3.1). */
    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Kolkata";

    /** Resources are deactivated, never deleted — tickets reference them. */
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    /** A-026: true on create and after an admin reset. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = true;

    /** A-021 lockout counter; the threshold is 5. */
    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /**
     * Reserved for the directory/SSO question still open in
     * GETTING-STARTED.md §1b. Unique with {@code externalSource}, so a user
     * imported twice from the same source collides rather than duplicating.
     */
    @Column(name = "external_id", length = 255)
    private String externalId;

    /** AZURE_AD | GOOGLE | … , or null when EduTrack is the system of record. */
    @Column(name = "external_source", length = 50)
    private String externalSource;

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

    public String getEmpCode() {
        return empCode;
    }

    public void setEmpCode(String empCode) {
        this.empCode = empCode;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Long getReportingManagerId() {
        return reportingManagerId;
    }

    public void setReportingManagerId(Long reportingManagerId) {
        this.reportingManagerId = reportingManagerId;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public BigDecimal getDailyCapacityHrs() {
        return dailyCapacityHrs;
    }

    public void setDailyCapacityHrs(BigDecimal dailyCapacityHrs) {
        this.dailyCapacityHrs = dailyCapacityHrs;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public short getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(short failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getExternalSource() {
        return externalSource;
    }

    public void setExternalSource(String externalSource) {
        this.externalSource = externalSource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
