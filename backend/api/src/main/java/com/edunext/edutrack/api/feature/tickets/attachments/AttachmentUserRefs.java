package com.edunext.edutrack.api.feature.tickets.attachments;

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
 * C-028 · turns the user ids on an attachment row into the contract's
 * {@code UserRef}.
 *
 * <p>Needed because §4B.4's tombstone is worded "file removed by <b>X</b> on
 * date". An id renders as "removed by 47", which is not a record anybody can
 * read — the whole value of keeping the row is that a person can look at the
 * ticket months later and see who took the file off it.
 *
 * <h2>It also closes a drift C-025 left</h2>
 *
 * <p>{@code contracts/openapi.yaml} has always typed {@code Attachment.uploadedBy}
 * as a {@code UserRef}, and the committed TypeScript client has always declared it
 * as one — but {@link AttachmentDtos.AttachmentDto} was serialising a bare
 * {@code Long}. Nothing failed, because the field had no consumer: the gallery
 * shows file names and the picker shows sizes, so a number arriving where an
 * object was declared went unnoticed through C-025, C-026 and C-027. The tombstone
 * is the first consumer, and it would have been the thing that discovered it. Both
 * fields go through this class rather than only the new one, because leaving
 * {@code uploadedBy} a number beside a {@code deletedBy} object would be a
 * strictly stranger record than the one being fixed.
 *
 * <h2>One query per listing, not one per row</h2>
 *
 * <p>{@link #resolve} takes the whole set of ids a listing needs, so a twenty-file
 * gallery whose files came from four people costs one {@code IN} lookup rather than
 * forty point reads. The n+1 is the obvious way to write this and is the reason it
 * takes a collection.
 *
 * <p>An id with no matching user resolves to <b>nothing</b> rather than to a
 * placeholder name. A deleted user account, a row written by a fixture, or an
 * import that named somebody who was never created should leave the field null so
 * the client renders "removed" without an actor — inventing "Unknown user" would
 * put a name-shaped string where the product means "we do not know".
 */
@Component
class AttachmentUserRefs {

    private final UserRepository users;

    AttachmentUserRefs(UserRepository users) {
        this.users = users;
    }

    /**
     * @param ids may contain nulls and duplicates — an attachment's
     *            {@code uploaded_by} and {@code deleted_by} are both nullable and
     *            are frequently the same person, and making every caller clean
     *            that up before asking is how one of them forgets
     * @return ids that named a real user, mapped to their reference. Ids that did
     *         not are simply absent, so {@code get} returns null and the field
     *         serialises as null
     */
    Map<Long, AttachmentDtos.UserRef> resolve(Collection<Long> ids) {
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
                        user -> new AttachmentDtos.UserRef(user.getId(), displayNameOf(user)),
                        // findAllById cannot return two rows with one primary key.
                        // The merge function is required by the collector and is
                        // written to keep the first rather than to throw, so a
                        // surprise here costs a duplicate rather than the listing.
                        (first, duplicate) -> first));
    }

    /**
     * {@code full_name}, falling back to the username.
     *
     * <p>The fallback is not decoration: {@code full_name} is nullable on
     * {@code users} and an SSO-provisioned account can arrive without one. A
     * tombstone reading "removed by null" is worse than one reading "removed by
     * rkumar", and the username is at least the handle colleagues know.
     */
    private static String displayNameOf(User user) {
        return Objects.requireNonNullElseGet(
                blankToNull(user.getFullName()),
                () -> Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
