package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code ob_implementation_stages} — the Implementation Stage master (OB-15).
 *
 * <p>The vocabulary an implementation is described in: Configuration, Data
 * Migration, Reports, Training, Communication, Third Party Integration —
 * seeded by {@code V20260911_1030__ob_implementation_stages.sql} and extended
 * from the screen rather than by a release. That is the reason this is a table
 * and not an enum, and the migration carries the argument.
 *
 * <h2>Two mutable fields and no delete</h2>
 *
 * <p>{@code name} and {@code sequence} are what the screen edits, plus
 * {@code isActive}, which is the whole lifecycle — {@link ObProduct}'s rule and
 * its reasoning: a value that has been used somewhere must stay resolvable, and
 * a row deleted out from under a historical reference is worse than a row
 * marked retired. There is no delete route, so this is not a convention
 * anybody has to remember.
 *
 * <h2>{@code sequence} is a position, and only the service may set it</h2>
 *
 * <p>1-based and contiguous across every row, retired ones included.
 * {@code setSequence} exists because renumbering has to write it, but nothing
 * outside {@code ObImplementationStageService} should call it: the invariant is
 * over the whole table, not over one row, and a caller who sets one row's
 * position has not reordered anything — they have created a duplicate.
 */
@Entity
@Table(name = "ob_implementation_stages")
public class ObImplementationStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique case-insensitively by the column's collation
     * ({@code utf8mb4_0900_ai_ci}). The service checks first anyway so the
     * refusal names the field rather than a MySQL constraint.
     */
    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    protected ObImplementationStage() {
    }

    public ObImplementationStage(String name, int sequence, boolean isActive, Long createdBy) {
        this.name = name;
        this.sequence = sequence;
        this.isActive = isActive;
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

    public int getSequence() {
        return sequence;
    }

    /** See the class note: renumbering is a whole-table operation. */
    public void setSequence(int sequence) {
        this.sequence = sequence;
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
