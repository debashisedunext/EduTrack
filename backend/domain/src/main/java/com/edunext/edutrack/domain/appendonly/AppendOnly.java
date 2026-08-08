package com.edunext.edutrack.domain.appendonly;

/**
 * B-005 · the write half of an append-only table.
 *
 * <p>{@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions} are insert-only and hash-chained. CLAUDE.md
 * states the rule as a property of the <em>API surface</em>, not of the
 * implementation: "No service method may expose {@code update()} or
 * {@code delete()} on them. Only {@code insert()}."
 *
 * <p>That is why the three repositories extend
 * {@link org.springframework.data.repository.Repository} — the empty marker —
 * plus this fragment, rather than {@code CrudRepository} or
 * {@code JpaRepository}. Those publish {@code delete}, {@code deleteById},
 * {@code deleteAll} and a {@code save} that <em>merges</em> a detached instance
 * into an {@code UPDATE}. Extending them and hoping nobody calls the wrong
 * method is not a guarantee; not inheriting them is.
 *
 * <p>{@link #insert} is {@code persist}, never {@code merge}, so an entity that
 * already carries an id fails loudly instead of quietly rewriting a row that
 * the hash chain has already committed to.
 *
 * <p>This is layer one of four. Layer two is
 * {@link org.hibernate.annotations.Immutable} on the entity, which stops
 * Hibernate dirty-checking a loaded row into an {@code UPDATE} nobody wrote.
 * Layer three is the {@code edutrack_app} grant, INSERT and SELECT only
 * (A-010). Layer four is the {@code BEFORE UPDATE} / {@code BEFORE DELETE}
 * triggers that {@code SIGNAL SQLSTATE '45000'} (A-008). Any one of them would
 * usually hold. The guarantee is that all four have to fail together.
 *
 * @param <T> the append-only entity type
 */
public interface AppendOnly<T> {

    /**
     * Append one row.
     *
     * <p>The returned instance is the managed one — its generated id is
     * populated after the flush, which is what a caller building a hash chain
     * needs in order to link the next row.
     *
     * @throws jakarta.persistence.EntityExistsException if the entity already
     *         has an identifier, i.e. someone tried to re-save a row that is
     *         already in the chain
     */
    T insert(T row);
}
