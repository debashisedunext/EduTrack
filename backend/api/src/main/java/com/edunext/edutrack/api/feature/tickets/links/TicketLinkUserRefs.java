package com.edunext.edutrack.api.feature.tickets.links;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C-064 · turns {@code ticket_links.created_by} into the contract's
 * {@code UserRef}, on {@code AttachmentUserRefs}'s exact precedent — including
 * its reasoning for why an id with no matching user resolves to nothing rather
 * than a placeholder name, and why this takes a collection rather than one id
 * at a time.
 */
@Component
class TicketLinkUserRefs {

    private final UserRepository users;

    TicketLinkUserRefs(UserRepository users) {
        this.users = users;
    }

    Map<Long, TicketLinkDtos.UserRef> resolve(Collection<Long> ids) {
        Set<Long> wanted = ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (wanted.isEmpty()) {
            return Map.of();
        }

        List<User> found = users.findAllById(wanted);
        return found.stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(
                        User::getId,
                        user -> new TicketLinkDtos.UserRef(user.getId(), displayNameOf(user)),
                        (first, duplicate) -> first));
    }

    private static String displayNameOf(User user) {
        return Objects.requireNonNullElseGet(
                blankToNull(user.getFullName()),
                () -> Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
