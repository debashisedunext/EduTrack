package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.domain.tickets.Ticket;

import java.util.Objects;

/**
 * C-043 · the golden rule, in one place — blueprint §2: "only the current
 * stage owner (plus PM and Admin) can move a ticket to the next stage. A
 * Developer cannot push a ticket into Deployment while it is sitting with
 * QA."
 *
 * <h2>Why this is not a permission code</h2>
 *
 * <p>{@code ticket.handoff} and {@code ticket.rework} are granted to all six
 * roles ({@code V20260806_0900}, {@link RolePermissions}) — the capability
 * says everybody may advance <em>some</em> ticket, and this decides
 * <em>which</em> one. That makes it a row-scope rule, the same category
 * CONVENTIONS.md places comment and attachment deletion in
 * ({@code CommentService.requireMayDelete},
 * {@code AttachmentService.requireMayDelete}), not a capability check —
 * which is why it lives here as a plain predicate rather than behind an
 * extra {@code @PreAuthorize}.
 *
 * <h2>Why this class exists rather than a private method on {@link TransitionService}</h2>
 *
 * <p>{@code TicketDetailService} needs the identical answer when it decides
 * whether {@code handoff}/{@code rework} belong in {@code availableActions}
 * — the contract's own note on that field is "the client renders buttons
 * from this rather than re-deriving permissions, because two
 * implementations of the same rule always diverge." A second, private copy
 * of this predicate in {@code detail/} would be exactly the divergence that
 * warning is about.
 */
public final class StageOwnership {

    private StageOwnership() {
    }

    /**
     * @return {@code true} for the ticket's current assignee, or for a
     *         caller holding ADMIN or PM regardless of assignment — the only
     *         three the golden rule admits.
     */
    public static boolean mayAdvance(CallerIdentity identity, Ticket ticket) {
        return Objects.equals(ticket.getAssignedTo(), identity.userId())
                || RolePermissions.ADMIN.equals(identity.roleCode())
                || RolePermissions.PM.equals(identity.roleCode());
    }
}
