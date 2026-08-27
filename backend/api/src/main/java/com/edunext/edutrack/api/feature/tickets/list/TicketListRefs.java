package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.Project;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D-061 · turns the ids on a page of tickets into the {@code UserRef},
 * {@code Project} and {@code ClientRef} the contract's {@code TicketSummary}
 * declares.
 *
 * <h2>Why this exists rather than flat ids</h2>
 *
 * <p>{@code TicketListDtos} used to argue for flat ids, and the argument was
 * good: "one row per ticket becomes one project lookup per ticket, and S-17
 * renders 50 rows". That objection is to the <b>n+1</b>, not to the nesting —
 * and this class answers it directly. Every id a page needs is resolved in one
 * {@code IN} query per type, so a 50-row page costs <b>three</b> queries
 * regardless of its size, the same as one row.
 *
 * <p>The nesting itself is not ours to re-decide: the contract's {@code Ticket}
 * embeds the whole {@code Project} schema, described there as "Stream C's call,
 * and not one to relitigate from here", and {@code TicketSummary} is a strict
 * subset of {@code Ticket}'s names. The frontend generates its client from that
 * document, which is why the flat form rendered S-17's assignee, project,
 * client and ID columns blank — the fields it read were never sent.
 *
 * <p>Deliberately a near-twin of {@code CommentUserRefs} rather than a shared
 * class the two packages import, following the precedent that class states:
 * features declare their own view of a contract shape, because a common one
 * across four streams has to be renegotiated whenever any of them wants another
 * field.
 *
 * <p>An id with no matching row resolves to <b>nothing</b> rather than to a
 * placeholder, exactly as {@code CommentUserRefs} does. A deleted account
 * should render the row without an assignee rather than inventing "Unknown
 * user", which is a name-shaped string where the product means it does not
 * know.
 */
@Component
public class TicketListRefs {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ClientRepository clients;

    TicketListRefs(UserRepository users, ProjectRepository projects, ClientRepository clients) {
        this.users = users;
        this.projects = projects;
        this.clients = clients;
    }

    /**
     * Every reference a page needs, in three queries.
     *
     * <p>Returned as one object rather than three methods for the reason
     * {@code CommentUserRefs.resolve} gives: they are always wanted together and
     * are always the same page, and three methods are three round trips that
     * nobody notices growing.
     *
     * @param page may be empty, and any id on it may be null — {@code client_id}
     *             and {@code assigned_to} are both nullable on {@code tickets},
     *             an unassigned ticket being the ordinary case rather than the
     *             exceptional one
     */
    public Resolved resolve(Collection<Ticket> page) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> projectIds = new HashSet<>();
        Set<Long> clientIds = new HashSet<>();

        for (Ticket t : page) {
            addIfPresent(userIds, t.getAssignedTo());
            addIfPresent(userIds, t.getReportedBy());
            addIfPresent(projectIds, t.getProjectId());
            addIfPresent(clientIds, t.getClientId());
        }

        return new Resolved(
                userRefs(userIds), projectRefs(projectIds), clientRefs(clientIds));
    }

    private Map<Long, TicketListDtos.UserRef> userRefs(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<User> found = users.findAllById(ids);
        Map<Long, TicketListDtos.UserRef> refs = new HashMap<>(found.size());
        for (User user : found) {
            if (user.getId() == null) {
                continue;
            }
            refs.put(user.getId(), new TicketListDtos.UserRef(
                    user.getId(), displayNameOf(user), roleCodeOf(user)));
        }
        return Map.copyOf(refs);
    }

    private Map<Long, TicketListDtos.Project> projectRefs(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Project> found = projects.findAllById(ids);
        Map<Long, TicketListDtos.Project> refs = new HashMap<>(found.size());
        for (Project project : found) {
            if (project.getId() == null) {
                continue;
            }
            refs.put(project.getId(), new TicketListDtos.Project(
                    project.getId(), project.getProjectCode(), project.getName()));
        }
        return Map.copyOf(refs);
    }

    private Map<Long, TicketListDtos.ClientRef> clientRefs(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Client> found = clients.findAllById(ids);
        Map<Long, TicketListDtos.ClientRef> refs = new HashMap<>(found.size());
        for (Client client : found) {
            if (client.getId() == null) {
                continue;
            }
            refs.put(client.getId(), new TicketListDtos.ClientRef(
                    client.getId(), client.getClientCode(), client.getName()));
        }
        return Map.copyOf(refs);
    }

    private static void addIfPresent(Set<Long> into, Long id) {
        if (id != null) {
            into.add(id);
        }
    }

    /**
     * @param people   ids that named a real user, mapped to their reference. Ids
     *                 that did not are simply absent, so {@code get} returns null
     *                 and the field serialises as null
     * @param projects the same, for {@code project_id}
     * @param clients  the same, for {@code client_id}
     */
    public record Resolved(Map<Long, TicketListDtos.UserRef> people,
                    Map<Long, TicketListDtos.Project> projects,
                    Map<Long, TicketListDtos.ClientRef> clients) {

        static Resolved empty() {
            return new Resolved(Map.of(), Map.of(), Map.of());
        }

        TicketListDtos.UserRef person(Long id) {
            return id == null ? null : people.get(id);
        }

        TicketListDtos.Project project(Long id) {
            return id == null ? null : projects.get(id);
        }

        TicketListDtos.ClientRef client(Long id) {
            return id == null ? null : clients.get(id);
        }
    }

    /**
     * {@code full_name}, falling back to the username.
     *
     * <p>The same fallback {@code CommentUserRefs} makes, for the same reason:
     * {@code full_name} is nullable and an SSO-provisioned account can arrive
     * without one. An assignee rendered as "null" is worse than one rendered as
     * "rkumar", which is at least the handle colleagues know.
     */
    private static String displayNameOf(User user) {
        String fullName = blankToNull(user.getFullName());
        if (fullName != null) {
            return fullName;
        }
        return Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown");
    }

    private static String roleCodeOf(User user) {
        if (user.getRole() == null) {
            return null;
        }
        return user.getRole().getCode();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
