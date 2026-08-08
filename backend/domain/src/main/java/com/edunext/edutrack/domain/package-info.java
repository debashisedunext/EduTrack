/**
 * B-005 · the JPA entity model.
 *
 * <p>Schema is owned by Stream A and lives in {@code db/migration}; the entities
 * here are mapped <em>onto</em> it and never generate it. {@code ddl-auto} is
 * {@code validate} (application.yml), so a property that does not match a column
 * fails the application at startup rather than at first query — which is the
 * point, and is what {@code EntityMappingIT} exercises against a real MySQL.
 *
 * <h2>Packaging</h2>
 *
 * Feature-packaged, not layer-packaged (CLAUDE.md): {@code domain.clients} holds
 * the client entities <em>and</em> their repositories. There is no shared
 * {@code entities/} or {@code repositories/} folder — that is the arrangement
 * that makes four developers collide daily.
 *
 * <p>{@code domain.mail} and {@code domain.notifications} already hold Stream D
 * domain services. The entities added there are Stream B's per this module's
 * contract ("schema owned by Stream A; entities by Stream B") and are additive:
 * D's writers speak JDBC precisely so the two cannot collide.
 *
 * <h2>Conventions</h2>
 *
 * <ul>
 *   <li><b>Time.</b> {@code DATETIME(6)} maps to {@link java.time.Instant} —
 *       storage is UTC everywhere (PLAN.md §3.1), and {@code Instant} is the
 *       only java.time type that cannot silently acquire a local offset.
 *       {@code DATE} maps to {@link java.time.LocalDate}: a holiday is a date,
 *       not an instant, and giving it a timezone would move it.</li>
 *   <li><b>Money-shaped numbers.</b> {@code DECIMAL(p,s)} maps to
 *       {@link java.math.BigDecimal}, never {@code double} — effort hours are
 *       summed and compared against SLA thresholds.</li>
 *   <li><b>Timestamps are the database's.</b> {@code created_at} and
 *       {@code updated_at} carry SQL defaults, so they are mapped
 *       {@code insertable = false, updatable = false} with Hibernate's
 *       {@code @Generated} reading the value back. Letting Java supply them
 *       would put two clocks on one column.</li>
 *   <li><b>Associations are selective.</b> {@code @ManyToOne(LAZY)} is used only
 *       where the child genuinely belongs to the parent and screens navigate it
 *       (a stage to its template, a contact to its client). Actor and audit
 *       columns — {@code assigned_to}, {@code reported_by}, {@code created_by},
 *       {@code actor_id} — stay scalar {@code Long} ids. They point at
 *       {@code users} but they are not ownership, and mapping them as
 *       associations buys nothing while adding a lazy proxy to every row a
 *       report touches.</li>
 *   <li><b>JSON columns</b> ({@code can_return_to},
 *       {@code mentioned_user_ids}) map through
 *       {@code @JdbcTypeCode(SqlTypes.JSON)} to typed lists — PLAN.md §3.1
 *       translated these from PostgreSQL arrays, and a raw {@code String} would
 *       push the parsing into every caller.</li>
 * </ul>
 *
 * <h2>The append-only rule</h2>
 *
 * {@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions} are insert-only and hash-chained. Their
 * repositories do <b>not</b> extend {@code CrudRepository} or
 * {@code JpaRepository} — those publish {@code delete}, {@code deleteAll} and a
 * {@code save} that merges. They extend
 * {@link org.springframework.data.repository.Repository} plus
 * {@link com.edunext.edutrack.domain.appendonly.AppendOnly}, so the only write
 * any caller can reach is {@code insert}.
 *
 * <p>The entities are additionally
 * {@link org.hibernate.annotations.Immutable}, which stops Hibernate
 * dirty-checking a loaded row into an {@code UPDATE} nobody wrote (PLAN.md
 * §3.6). Database triggers (A-008) reject the rest. The layers are deliberate:
 * a missing method, an immutable mapping, a narrow grant and a trigger. Losing
 * one should not lose the guarantee.
 *
 * <p>Sealing a stage transition is the single permitted mutation, and it is an
 * explicit JPQL update on named columns — see
 * {@code TicketStageTransitionRepository#seal}.
 */
package com.edunext.edutrack.domain;
