package com.edunext.edutrack.api.feature.onboarding.implementationstages;

import com.edunext.edutrack.domain.onboarding.ObImplementationStage;
import com.edunext.edutrack.domain.onboarding.ObImplementationStageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Implementation Stage master's rules — OB-15.
 *
 * <h2>One invariant, and it is over the table rather than the row</h2>
 *
 * <p><b>{@code sequence} is 1..N with no gaps and no duplicates, always.</b>
 * The column carries no unique index to enforce it, because renumbering is a
 * set of UPDATEs and every ordering of them passes through a moment where two
 * rows share a position — MySQL has no DEFERRABLE to escape with, so a unique
 * index would refuse legitimate reorders halfway through. The migration records
 * that; this class is where the invariant actually lives, applied over the
 * whole table inside one transaction, which is the only place that ever sees
 * every row at once.
 *
 * <p>The consequence for callers is worth stating plainly, because it is
 * unusual for a PATCH: <b>the {@code sequence} that comes back is not
 * necessarily the one that went in.</b> The request names a position the caller
 * wants; the response says where everything ended up.
 *
 * <h2>Why a position rather than a reorder endpoint</h2>
 *
 * <p>{@code PUT /onboarding/journey-templates/order} takes the whole ordered id
 * list, which is the right shape for the up/down control it serves. This
 * master's screen edits a number in a form instead, so the whole list is not
 * what the caller has — they have one row and a number. Sending the list would
 * mean the screen reconstructing it from a page that may be stale, and getting
 * that wrong silently reorders rows nobody touched. A single position is the
 * smaller claim, and {@link #reposition} turns it into the same renumbering.
 *
 * <h2>Out-of-range positions are clamped, not refused</h2>
 *
 * <p>{@code 400} means last. Somebody typing a number into a position box is
 * expressing an intent the master can satisfy exactly, and a 400 telling them
 * the list is only six long is a validation error about arithmetic rather than
 * about their meaning. What is refused is a duplicate name, which is a genuine
 * collision.
 */
@Service
public class ObImplementationStageService {

    private final ObImplementationStageRepository stages;

    ObImplementationStageService(ObImplementationStageRepository stages) {
        this.stages = stages;
    }

    @Transactional(readOnly = true)
    public List<ObImplementationStageDtos.Stage> list(Boolean isActive) {
        List<ObImplementationStage> rows = isActive == null
                ? stages.findAllByOrderBySequenceAscIdAsc()
                : stages.findAllByIsActiveOrderBySequenceAscIdAsc(isActive);
        return rows.stream().map(ObImplementationStageService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ObImplementationStageDtos.Stage> find(long id) {
        return stages.findById(id).map(ObImplementationStageService::toDto);
    }

    /**
     * Appends by default, or inserts at the requested position.
     *
     * <p>Saved first and repositioned second rather than computed in one step:
     * the row needs an id before it can take part in the ordering, and giving
     * it {@code order.size() + 1} up front means the append case — which is
     * every create from the screen's "New stage" button — needs no renumbering
     * at all.
     */
    @Transactional
    public ObImplementationStageDtos.Stage create(
            ObImplementationStageDtos.WriteRequest request, Long createdBy) {

        String name = normalise(request.name());
        stages.findByName(name).ifPresent(existing -> {
            throw new DuplicateImplementationStageNameException(name);
        });

        List<ObImplementationStage> order = stages.findAllByOrderBySequenceAscIdAsc();
        ObImplementationStage saved = stages.save(new ObImplementationStage(
                name, order.size() + 1, request.activeOrDefault(), createdBy));

        if (request.sequence() != null) {
            // Copied rather than appended to in place. `order` is whatever the
            // repository chose to return, and Spring Data does not promise it
            // is mutable — the test fake's `.toList()` is immutable and this
            // threw against it, which is the honest reproduction of a
            // repository that returns an unmodifiable view.
            List<ObImplementationStage> withNew = new ArrayList<>(order);
            withNew.add(saved);
            reposition(withNew, saved, request.sequence());
        }
        return toDto(saved);
    }

    /**
     * Renames, retires or moves — the three things the screen can do to a row.
     *
     * <p>A rename to the row's own name is fine and common: the duplicate check
     * ignores the row being edited, so the obvious round trip (open the form,
     * change the position, save) does not fail on a name nobody touched.
     */
    @Transactional
    public Optional<ObImplementationStageDtos.Stage> update(
            long id, ObImplementationStageDtos.WriteRequest request) {

        return stages.findById(id).map(row -> {
            String name = normalise(request.name());
            stages.findByName(name)
                    .filter(other -> !other.getId().equals(row.getId()))
                    .ifPresent(other -> {
                        throw new DuplicateImplementationStageNameException(name);
                    });

            row.setName(name);
            row.setActive(request.activeOrDefault());
            ObImplementationStage saved = stages.save(row);

            if (request.sequence() != null) {
                reposition(stages.findAllByOrderBySequenceAscIdAsc(), saved, request.sequence());
            }
            return toDto(saved);
        });
    }

    /**
     * Moves {@code moved} to 1-based {@code target} within {@code order} and
     * renumbers the result 1..N.
     *
     * <p>Compared by id rather than by reference: {@code order} is re-read from
     * the repository inside {@link #update}, and whether Hibernate hands back
     * the same instance the caller is holding depends on the persistence
     * context — which is not a thing a reorder should quietly depend on.
     *
     * @param target clamped into {@code [1, size]} — see the class note on why
     *               an out-of-range position is somebody's intent rather than
     *               their mistake.
     */
    private void reposition(List<ObImplementationStage> order, ObImplementationStage moved, int target) {
        List<ObImplementationStage> working = new ArrayList<>(order);
        working.removeIf(row -> row.getId().equals(moved.getId()));

        int index = Math.clamp(target - 1L, 0, working.size());
        working.add(index, moved);

        renumber(working);
    }

    /**
     * Writes 1..N over the list as given, touching only the rows whose position
     * actually changed.
     *
     * <p>The filter is not a micro-optimisation. Every row here has
     * {@code updated_at ON UPDATE CURRENT_TIMESTAMP(6)}, so writing all of them
     * would restamp the whole master on every save and make "when did this
     * stage last change" answer "just now" for six rows nobody edited.
     */
    private void renumber(List<ObImplementationStage> ordered) {
        List<ObImplementationStage> dirty = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            ObImplementationStage row = ordered.get(i);
            if (row.getSequence() != i + 1) {
                row.setSequence(i + 1);
                dirty.add(row);
            }
        }
        if (!dirty.isEmpty()) {
            stages.saveAll(dirty);
        }
    }

    private static ObImplementationStageDtos.Stage toDto(ObImplementationStage row) {
        return new ObImplementationStageDtos.Stage(
                row.getId(), row.getName(), row.getSequence(), row.isActive());
    }

    /**
     * Trimmed, and otherwise left exactly as typed.
     *
     * <p>Not upper-cased the way {@code ObProductService} normalises a product
     * <em>code</em>: this is a label a person reads on a screen, and "Third
     * Party Integration" is the seeded spelling. Case-insensitive uniqueness
     * still holds — it comes from the column's collation, and the check above
     * goes through {@code findByName}, which inherits it.
     */
    private static String normalise(String name) {
        return name == null ? null : name.trim();
    }
}
