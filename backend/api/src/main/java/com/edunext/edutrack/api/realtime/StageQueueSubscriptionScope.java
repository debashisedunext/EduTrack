package com.edunext.edutrack.api.realtime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * D-059 · who may watch a team's queue (blueprint §9.3, S-31).
 *
 * <h2>Project membership, not row scope, and the frame is why</h2>
 *
 * <p>§10.2 gives a Developer, QA or Deployment resource {@code assigned_to =
 * me}. A stage queue is the opposite shape — S-31 exists precisely because
 * <em>"QA and Deployment are queue-driven teams, not assignment-driven ones.
 * Without a shared 'waiting in QA' list, tickets stall between the handoff and
 * someone noticing"</em> (§17 item 12) — so a rule of "only what is assigned to
 * you" would make the room useless to the people it is for.
 *
 * <p>Resolving that tension is <strong>not</strong> this class's job, and it
 * does not attempt it. What travels on a stage topic is a nudge:
 * {@code {event, stageCode, projectId}}, carrying no ticket id, code, title or
 * assignee. A subscriber learns that their team's queue moved and then refetches
 * {@code GET /stages/queue}, which applies whatever scope C-062 gives it. So the
 * question here is only "may this person be told their team's queue changed",
 * and the answer can be project membership without widening what anybody can
 * read by a single row.
 *
 * <p>That is D-044's argument in a different room: the event carries no state,
 * the database is the only answer, and a client that missed a frame is stale
 * rather than wrong.
 *
 * <p><strong>The day a ticket identity goes into a stage frame, this rule has to
 * change first.</strong> That is the change that turns a nudge into a feed of
 * other people's work, and in a diff it looks like adding a field.
 *
 * <h2>Read fresh, not from the token</h2>
 *
 * <p>{@code CallerIdentity.projectIds()} would answer this without a query, and
 * is what A-034 scopes REST reads with. It is not used here because a
 * subscription outlives the request that opened it: a token minted before
 * somebody joined a project would keep their team's room shut for the life of
 * the session, and the failure — a queue that silently never updates — is
 * exactly what D-014 and D-015 exist to prevent. One indexed existence check per
 * SUBSCRIBE is the same cost {@code ChatSubscriptionScope} already accepts, and
 * it is once per topic per session rather than per message.
 */
@Component
class StageQueueSubscriptionScope implements SubscriptionScope {

    /**
     * Membership, and Admin.
     *
     * <p>{@code is_active = 1} on both sides: a departed employee's session, or
     * a membership that has been revoked, must stop granting the room rather
     * than keep it open until the socket happens to drop.
     *
     * <p>The role inside the project is deliberately not consulted. The question
     * is whether this person is on the project at all — a Developer watching the
     * QA queue on their own project is how they see their handoff land, which is
     * the walkthrough §16 describes.
     */
    private static final String ON_PROJECT_OR_ADMIN = """
            SELECT EXISTS (
              SELECT 1
                FROM project_members pm
                JOIN users u ON u.id = pm.user_id
               WHERE pm.project_id = :projectId
                 AND pm.user_id    = :userId
                 AND pm.is_active  = 1
                 AND u.is_active   = 1
              UNION ALL
              SELECT 1
                FROM users u
                JOIN roles r ON r.id = u.role_id
               WHERE u.id = :userId
                 AND u.is_active = 1
                 AND r.code = 'ADMIN')
            """;

    private final JdbcClient jdbc;

    StageQueueSubscriptionScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean mayObserveStage(long userId, String stageCode, long projectId) {
        // stageCode is not consulted, and that is not an oversight: a queue is
        // per project, and somebody on the project may watch any of its teams.
        // D-014 has already validated the code — it is rejected rather than
        // sanitised there, because a dot would re-shape the destination and
        // could land a subscriber in another project's room.
        return Boolean.TRUE.equals(jdbc.sql(ON_PROJECT_OR_ADMIN)
                .param("projectId", projectId)
                .param("userId", userId)
                .query(Boolean.class)
                .single());
    }
}
