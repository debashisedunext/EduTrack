package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A-112 · {@code ob_clients}, mapped as far as the row-scope guard needs it
 * and no further.
 *
 * <h2>Why this is two columns rather than fifteen</h2>
 *
 * <p>{@code OnboardingScopeResolver} has to express "journeys whose client I
 * created" (onboarding plan §3, the Sales row) as a {@code Specification}, and
 * {@link ObJourney} holds {@code ob_client_id} as a bare {@code Long} rather
 * than an association — so there is nothing to join through. A criteria
 * subquery needs a mapped type on the other side, and this is the smallest one
 * that answers the question.
 *
 * <p>It is deliberately not the client model. <b>B-102 owns Client CRUD</b> and
 * is blocked on this task, so the full entity — PAN, contacts, license, status
 * — cannot be waited for and must not be pre-empted here either. When B-102
 * lands, <b>widen this class</b> rather than adding a second {@code @Entity} on
 * {@code ob_clients}: two entities over one table is legal in JPA and is how a
 * table ends up with two disagreeing notions of what a client is.
 *
 * <h2>{@code created_by}, not {@code sales_person_id}</h2>
 *
 * <p>Both columns exist and they are not the same fact. §3 says Sales sees
 * "Clients they created", and A-112's backlog entry says {@code created_by = me}
 * — so the scope follows authorship, not the sales owner named on the record.
 * The difference bites when a client is boarded by one person and assigned to
 * another: the assignee is <em>not</em> given visibility by this rule, and if
 * that turns out to be wrong it is a plan change, not a fix here.
 */
@Entity
@Table(name = "ob_clients")
public class ObClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The user who boarded this client.
     *
     * <p><b>Nullable in the schema</b>, and the scope rule depends on knowing
     * that. {@code = ?} never matches NULL, so a client with no recorded
     * author is visible to nobody in the OB_SALES role — not to the person who
     * actually boarded it, and not to every Sales user at once. Deny is the
     * right direction for a row whose ownership is unknown, and it is the
     * direction SQL gives for free here; it is written down because the
     * opposite reading ("nobody created it, so it is everyone's") is the one
     * someone would implement if they tried to be helpful about the NULL.
     *
     * <p>Such a row is still reachable by OB_ADMIN, OB_MANAGER and OB_VIEWER,
     * so it is not lost — it is unowned, which is a data problem for the
     * onboarding admin rather than a hole in the guard.
     */
    @Column(name = "created_by")
    private Long createdBy;

    protected ObClient() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
