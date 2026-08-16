package com.edunext.edutrack.api.feature.clients;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * B-026 · the reference checks and the mapping write behind S-33's form.
 *
 * <p><b>The client row itself is not written here.</b> It goes through
 * {@code ClientRepository} and the JPA entity, which is the only thing that maps
 * {@code tags} — a MySQL {@code JSON} column Hibernate serialises through
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. Writing the row with {@link JdbcClient}
 * as well would mean a second, hand-rolled encoding of that column living beside
 * the mapped one, which is the drift {@code ProjectRoles} and
 * {@code PasswordComplexity} exist to prevent. What is here is what JPA is the
 * wrong tool for: three existence checks that must not load entities, and a
 * set-replacement over a composite-key join table.
 *
 * <p><b>The checks are batched, never per row.</b> {@link #missingProjectIds}
 * answers for the whole selection in one statement: S-33's Projects tab is a
 * multi-select and a client on eight projects would otherwise cost eight round
 * trips to say "all fine" — and, worse, would report the first bad id and stop,
 * so an admin fixing a stale selection learns about one mistake per save.
 * B-025's bulk status setter took the same position on unknown client ids for
 * the same reason.
 */
@Repository
class ClientWriteRepository {

    private final JdbcClient jdbc;

    ClientWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reference checks
    // ------------------------------------------------------------------

    /**
     * The named account manager, or empty.
     *
     * <p>Reads {@code is_active} rather than filtering on it, so the service can
     * tell "no such resource" from "that resource has left" — two different
     * mistakes with two different fixes, and one 400 that says which.
     */
    Optional<ManagerCandidate> findManager(long userId) {
        return jdbc.sql("""
                        SELECT u.id, u.full_name, u.is_active
                        FROM users u
                        WHERE u.id = ?
                        """)
                .param(userId)
                .query((rs, n) -> new ManagerCandidate(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getBoolean("is_active")))
                .optional();
    }

    /**
     * Which of these project ids do not exist — <b>every</b> one, in one
     * statement.
     *
     * <p>Existence only, not {@code is_active}. B-016 gave projects three states
     * and a client's association with a project that has been put on hold or
     * closed is a fact about the relationship, not a mistake: unmapping it would
     * rewrite history to make a form tidy, and the Projects tab shows the
     * project's own status beside each row instead.
     */
    List<Long> missingProjectIds(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        Set<Long> present = new LinkedHashSet<>(jdbc.sql("""
                        SELECT p.id FROM projects p WHERE p.id IN (:ids)
                        """)
                .param("ids", projectIds)
                .query(Long.class)
                .list());

        return projectIds.stream().filter(id -> !present.contains(id)).distinct().toList();
    }

    /** Whether an {@code sla_policies} row exists — {@code clients.sla_policy_id} is an FK. */
    boolean slaPolicyExists(long slaPolicyId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM sla_policies s WHERE s.id = ?)
                        """)
                .param(slaPolicyId)
                .query(Boolean.class)
                .single());
    }

    /**
     * A client already holding this code, ignoring {@code exceptId}.
     *
     * <p><b>No {@code UPPER()} on the column, deliberately</b> — the same call
     * {@code ClientRepository.findClientCodesIn} documents. {@code client_code}
     * collates {@code utf8mb4_0900_ai_ci}, so MySQL already matches {@code acme}
     * against {@code ACME}, and it does it <i>through</i> {@code uq_clients_code}
     * rather than scanning, which wrapping the column in a function would
     * prevent.
     *
     * <p><b>{@code exceptId} is what stops every ordinary edit reporting the
     * client's own code as taken.</b> S-33 submits the whole form on every save,
     * so without it a rename of any other field would 409 on the code the client
     * already has — the {@code u.id <> ?} B-013 had to document on the resource
     * form, in the same shape.
     */
    Optional<String> findConflictingCode(String clientCode, Long exceptId) {
        return jdbc.sql("""
                        SELECT c.client_code
                        FROM clients c
                        WHERE c.client_code = ?
                          AND (? IS NULL OR c.id <> ?)
                        LIMIT 1
                        """)
                .param(clientCode)
                .param(exceptId)
                .param(exceptId)
                .query(String.class)
                .optional();
    }

    // ------------------------------------------------------------------
    // The mapping
    // ------------------------------------------------------------------

    /**
     * Replaces this client's whole {@code client_projects} set.
     *
     * <p><b>A delete-then-insert, and here that is right</b>, where B-017 had to
     * argue itself out of it for {@code project_members} and B-018 for SLA
     * overrides. Both of those rows carry facts of their own — an allocation
     * percentage, an escalation flag, an {@code added_at} somebody reads as a
     * start date — so removing and re-adding a row fabricates a departure and a
     * return. {@code client_projects} holds no fact but the association and its
     * default flag, which is exactly what this statement is replacing;
     * A-006's own comment says the {@code ON DELETE CASCADE} on this table "is
     * safe here and only here" for the same reason.
     *
     * <p>{@code created_at} does move, and that is the one thing lost. Nothing
     * reads it — no screen, no report, no scanner — and preserving it would mean
     * an upsert whose only purpose is to keep a column honest that nobody asks.
     * Recorded rather than hidden.
     *
     * <p>The default flag is set in the same pass, so a client cannot be left
     * with a default pointing at a project it is no longer mapped to. The
     * service refuses that combination first; this makes it unrepresentable
     * afterwards.
     */
    void replaceProjects(long clientId, List<Long> projectIds, Long defaultProjectId) {
        jdbc.sql("DELETE FROM client_projects WHERE client_id = ?")
                .param(clientId)
                .update();

        for (Long projectId : new LinkedHashSet<>(projectIds)) {
            jdbc.sql("""
                            INSERT INTO client_projects (client_id, project_id, is_default)
                            VALUES (?, ?, ?)
                            """)
                    .param(clientId)
                    .param(projectId)
                    .param(projectId.equals(defaultProjectId))
                    .update();
        }
    }

    record ManagerCandidate(long id, String displayName, boolean isActive) {
    }
}
