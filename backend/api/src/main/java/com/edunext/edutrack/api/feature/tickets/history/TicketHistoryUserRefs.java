package com.edunext.edutrack.api.feature.tickets.history;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * C-059 · turns the actor/author ids on a page of history into the contract's
 * {@code UserRef}.
 *
 * <p>A near-twin of {@code EffortLogUserRefs} and {@code CommentUserRefs} —
 * both those classes' javadocs state the precedent this follows: a feature
 * declares its own view of a contract shape rather than importing a shared
 * one, because a common DTO across four streams is renegotiated every time
 * one of them wants another field. No role lookup here, unlike
 * {@code CommentUserRefs}: the history stream has no "author's role" chip to
 * fill.
 *
 * <p>One query for the whole page rather than one per row, for the same
 * reason both twins give: a page mixing a handful of actors on a busy ticket
 * costs one {@code IN} lookup rather than one point read per row.
 */
@Component
class TicketHistoryUserRefs {

    private final UserRepository users;

    TicketHistoryUserRefs(UserRepository users) {
        this.users = users;
    }

    /**
     * @param ids may contain nulls and duplicates — {@code actor_id} is null for
     *            a {@code SYSTEM} row by design (an escalation or a scanner, not
     *            a person), and a null must not reach {@code findAllById}
     * @return ids that named a real user, mapped to their reference. An id that
     *         did not — a deleted account, a {@code SYSTEM} row's null — is
     *         simply absent, so a missing entry renders as a null {@code actor}
     *         rather than an invented "Unknown user"
     */
    Map<Long, TicketHistoryDtos.UserRef> resolve(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }

        List<User> found = users.findAllById(wanted);
        Map<Long, TicketHistoryDtos.UserRef> refs = new HashMap<>(found.size());
        for (User user : found) {
            if (user.getId() == null) {
                continue;
            }
            refs.put(user.getId(), new TicketHistoryDtos.UserRef(user.getId(), displayNameOf(user)));
        }
        return Map.copyOf(refs);
    }

    /** {@code full_name}, falling back to the username — {@code CommentUserRefs}' rule, verbatim. */
    private static String displayNameOf(User user) {
        String fullName = blankToNull(user.getFullName());
        if (fullName != null) {
            return fullName;
        }
        return Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
