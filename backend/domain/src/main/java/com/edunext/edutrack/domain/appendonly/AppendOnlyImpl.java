package com.edunext.edutrack.domain.appendonly;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The single implementation behind every {@link AppendOnly} repository.
 *
 * <p>Spring Data resolves a fragment implementation by name — interface
 * {@code AppendOnly} to class {@code AppendOnlyImpl} — so one generic class
 * serves all three append-only repositories rather than three copies of the
 * same two lines. Three copies is how one of them eventually grows an
 * {@code update}.
 *
 * <p>{@code persist} rather than {@code merge} is the whole content of this
 * class. {@code merge} on a detached instance carrying an id issues an
 * {@code UPDATE}, which is the exact operation the append-only rule exists to
 * prevent — it would be caught by the A-008 trigger, but as a runtime failure
 * in production rather than a method that was never callable.
 */
public class AppendOnlyImpl<T> implements AppendOnly<T> {

    @PersistenceContext
    private EntityManager em;

    @Override
    public T insert(T row) {
        em.persist(row);
        return row;
    }
}
