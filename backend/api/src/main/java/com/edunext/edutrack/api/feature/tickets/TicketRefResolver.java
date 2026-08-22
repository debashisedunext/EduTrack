package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The {@link TicketWire.Refs} implementation the detail endpoint uses — one
 * ticket's reference ids turned into the objects the contract's {@code Ticket}
 * schema declares ({@code project}, {@code client}, {@code assignee},
 * {@code reportedBy}).
 *
 * <h2>Why this exists beside {@code list/TicketListRefs}</h2>
 *
 * <p>That class does the same job for a <em>page</em> of tickets and does it
 * well: three queries for the whole page, ids batched through
 * {@code findAllById}. It is not reused here for two reasons. It is
 * package-private in {@code feature/tickets/list}, and widening it would export
 * a page-shaped API to a single-row caller; and its refs are deliberately
 * emitted under the {@code TicketListUserRef} schema name, while
 * {@code Ticket.assignee} points at the canonical {@code UserRef}. Two callers,
 * two schemas — the same honest duplication {@code TicketListDtos} itself
 * records making against {@code CommentUserRefs}.
 *
 * <p><b>Four primary-key reads for one ticket, and that is the right cost
 * here.</b> The detail page is one ticket opened deliberately, not a page of
 * fifty, so there is no N+1 to amortise and batching would add a collection API
 * to save nothing. A second single-ticket caller should use this class rather
 * than write its own.
 *
 * <p><b>A missing row resolves to null, never to a placeholder.</b> An id
 * pointing at a deleted project is a fact about the data; inventing "Unknown
 * project" would render a name nobody can search for. All four fields are
 * optional in the contract, so null is both legal and truthful —
 * {@code TicketListRefs} makes the same choice for the same reason.
 */
@Component
public class TicketRefResolver {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ClientRepository clients;

    TicketRefResolver(UserRepository users, ProjectRepository projects, ClientRepository clients) {
        this.users = users;
        this.projects = projects;
        this.clients = clients;
    }

    /** Every reference {@code t} carries, resolved in four primary-key reads. */
    public TicketWire.Refs resolve(com.edunext.edutrack.domain.tickets.Ticket t) {
        return new TicketWire.Refs(
                user(t.getReportedBy()),
                user(t.getAssignedTo()),
                project(t.getProjectId()),
                client(t.getClientId()));
    }

    private TicketWire.UserRef user(Long id) {
        if (id == null) {
            return null;
        }
        return users.findById(id)
                .map(u -> new TicketWire.UserRef(u.getId(), displayNameOf(u), roleCodeOf(u)))
                .orElse(null);
    }

    private TicketWire.Project project(Long id) {
        if (id == null) {
            return null;
        }
        return projects.findById(id)
                .map(p -> new TicketWire.Project(p.getId(), p.getProjectCode(), p.getName()))
                .orElse(null);
    }

    private TicketWire.ClientRef client(Long id) {
        if (id == null) {
            return null;
        }
        return clients.findById(id)
                .map(c -> new TicketWire.ClientRef(c.getId(), c.getClientCode(), c.getName()))
                .orElse(null);
    }

    /**
     * Full name, falling back to the username — {@code TicketListRefs}' own
     * rule, restated rather than shared for the reason in the class note.
     */
    private static String displayNameOf(User user) {
        String fullName = blankToNull(user.getFullName());
        if (fullName != null) {
            return fullName;
        }
        return Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown");
    }

    private static String roleCodeOf(User user) {
        return user.getRole() == null ? null : user.getRole().getCode();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
