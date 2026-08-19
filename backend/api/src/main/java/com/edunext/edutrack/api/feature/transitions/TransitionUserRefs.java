package com.edunext.edutrack.api.feature.transitions;

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
 * C-044 · turns a ribbon segment's owner id into the contract's {@code UserRef}.
 *
 * <p>A near-twin of {@code EffortLogUserRefs}, whose own javadoc states the
 * precedent this follows: a feature declares its own view of a contract shape
 * rather than importing a shared one.
 */
@Component
class TransitionUserRefs {

    private final UserRepository users;

    TransitionUserRefs(UserRepository users) {
        this.users = users;
    }

    /**
     * @param ids may contain nulls and duplicates — a stage nobody has ever
     *            entered has no owner to resolve
     * @return ids that named a real user, mapped to their reference. A missing
     *         entry renders as a null {@code owner} rather than an invented
     *         placeholder, {@code EffortLogUserRefs}' own rule.
     */
    Map<Long, RibbonWire.UserRef> resolve(Collection<Long> ids) {
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
        Map<Long, RibbonWire.UserRef> refs = new HashMap<>(found.size());
        for (User user : found) {
            if (user.getId() == null) {
                continue;
            }
            refs.put(user.getId(), new RibbonWire.UserRef(user.getId(), displayNameOf(user)));
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
