package com.edunext.edutrack.domain.masters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * The product area a concern was raised against — blueprint §7.3, the Module
 * field of §7.5's "Where it happened". Eight rows are seeded by C-065's
 * {@code V20260819_1336} migration: Student, Admission, Fees, Examination,
 * Attendance, Library, Inventory, Parent App.
 *
 * <p><b>Named {@code ProductModule}, not {@code Module}, and that is not a
 * style choice.</b> {@code java.lang.Module} is imported implicitly into every
 * compilation unit in the language. An entity called {@code Module} would
 * shadow it in this package and be shadowed by it in any file that imports
 * neither — the resulting error names a JPMS type nobody in this codebase has
 * ever referenced, which is the least useful diagnostic available. The table is
 * {@code product_modules} anyway, so the class matches the schema rather than
 * the screen label.
 *
 * <p><b>This is a master, not an enum, and PLAN.md §3.9 says so outright:
 * "nothing in Java may hard-code the list".</b> The ninth module is a row
 * somebody inserts, not a migration and a deployment. That is also why there is
 * no {@code CHECK} constraint on {@code tickets.module_id} and a foreign key
 * instead.
 *
 * <p><b>Retired, never deleted.</b> {@code is_active = 0} takes a module out of
 * the pickers; the row survives so that a ticket raised against it last year
 * still renders a name. C-065's migration leaves the FK at its default
 * {@code RESTRICT} precisely so a delete cannot succeed while any ticket still
 * points here.
 *
 * <p>No {@code ticketCount} field, unlike {@link TaskType}'s view — this master
 * has no admin screen to make a deactivate decision on, so there is nothing for
 * a count to inform. B-064's own backlog entry says a Module Master screen is a
 * new task on the S-11/S-12 pattern rather than a change to this one.
 */
@Entity
@Table(name = "product_modules")
public class ProductModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** {@code STUDENT}, {@code PARENT_APP}, … — unique, and the stable identifier. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    /** Display text. An Admin may change it; never key behaviour off it. */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Display order in the picker. Not unique — {@code id} breaks the tie. */
    @Column(name = "seq", nullable = false)
    private short seq;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public short getSeq() {
        return seq;
    }

    public void setSeq(short seq) {
        this.seq = seq;
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
