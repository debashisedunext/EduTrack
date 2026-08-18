package com.edunext.edutrack.api.feature.tickets.effort;

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
 * C-035 · turns the {@code user_id}s on a page of effort logs into the
 * contract's {@code UserRef}.
 *
 * <p>A near-twin of {@code CommentUserRefs}, minus the role lookup that
 * feature needs and this one does not — §4A.4's grid rolls up by resource, not
 * by role. {@code CommentUserRefs}' own javadoc states the precedent this
 * follows: a feature declares its own view of a contract shape rather than
 * importing a shared one, because a common DTO across four streams is
 * renegotiated every time one of them wants another field.
 *
 * <p>One query for the whole page rather than one per row, for the same reason:
 * a week's worth of a resource's own entries names one person forty times, and
 * that is one {@code IN} lookup rather than forty point reads.
 */
@Component
class EffortLogUserRefs {

    private final UserRepository users;

    EffortLogUserRefs(UserRepository users) {
        this.users = users;
    }

    /**
     * @param ids may contain nulls and duplicates; {@code user_id} is
     *            {@code NOT NULL} in the schema, but a caller unidentifiable
     *            under {@code dev-noauth} could still reach a write with a null
     *            actor before that write is refused elsewhere, and making every
     *            caller of this method guard against it is how one forgets
     * @return ids that named a real user, mapped to their reference. An id that
     *         did not is simply absent, so a missing entry renders as a null
     *         {@code user} rather than an invented "Unknown user" — a deleted
     *         account should render the hours without a name, not a name-shaped
     *         placeholder for one
     */
    Map<Long, EffortLogDtos.UserRef> resolve(Collection<Long> ids) {
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
        Map<Long, EffortLogDtos.UserRef> refs = new HashMap<>(found.size());
        for (User user : found) {
            if (user.getId() == null) {
                continue;
            }
            refs.put(user.getId(), new EffortLogDtos.UserRef(user.getId(), displayNameOf(user)));
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
