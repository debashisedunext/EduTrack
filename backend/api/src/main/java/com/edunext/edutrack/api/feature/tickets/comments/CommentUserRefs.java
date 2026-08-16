package com.edunext.edutrack.api.feature.tickets.comments;

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
 * C-029 · turns the author ids on a thread into the contract's {@code UserRef}
 * and {@code RoleCode}.
 *
 * <p>§4B.5's stamping rule is "author, author's role, stage, iteration,
 * timestamp", and the first two are the ones a reader actually uses: "Ravi
 * Kumar (Developer)" is what makes a two-year-old thread legible, and an id
 * renders as "47 (null)".
 *
 * <p>Deliberately a near-twin of {@code AttachmentUserRefs} rather than a shared
 * class the two packages import. The precedent is stated in
 * {@code AttachmentDtos.UserRef}: features declare their own view of a contract
 * shape, because a common one across four streams has to be renegotiated
 * whenever any of them wants another field — which is exactly what happened
 * here. This one resolves a role as well, and folding that into the attachments
 * copy would put a join behind every file listing for a field no gallery draws.
 *
 * <h2>One query per listing, not one per row</h2>
 *
 * <p>{@link #resolve} takes every id a thread needs at once, so a forty-comment
 * argument between five people costs one {@code IN} lookup rather than forty
 * point reads. The n+1 is the obvious way to write this and is the reason both
 * methods take a collection.
 *
 * <p>An id with no matching user resolves to <b>nothing</b> rather than to a
 * placeholder. A deleted account or a fixture-written row should render the
 * comment without an author instead of inventing "Unknown user", which is a
 * name-shaped string where the product means it does not know.
 */
@Component
class CommentUserRefs {

    private final UserRepository users;

    CommentUserRefs(UserRepository users) {
        this.users = users;
    }

    /**
     * Both maps for one set of ids, from one query.
     *
     * <p>Returned together rather than as two methods because they are always
     * wanted together and are always the same rows — two methods would be two
     * {@code IN} queries for one listing, and the second one is the kind of
     * thing that gets added later by someone who did not notice the first.
     *
     * @param ids may contain nulls and duplicates; {@code author_id} is
     *            {@code NOT NULL} in the schema, but an unidentified caller
     *            under {@code dev-noauth} can still put a null in front of this
     *            method, and making every caller clean that up is how one of
     *            them forgets
     */
    Resolved resolve(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                wanted.add(id);
            }
        }
        if (wanted.isEmpty()) {
            return Resolved.empty();
        }

        List<User> found = users.findAllById(wanted);
        Map<Long, CommentDtos.UserRef> refs = new HashMap<>(found.size());
        Map<Long, String> roles = new HashMap<>(found.size());

        for (User user : found) {
            if (user.getId() == null) {
                continue;
            }
            refs.put(user.getId(), new CommentDtos.UserRef(user.getId(), displayNameOf(user)));
            // Absent rather than null when the user has no role row: the map is
            // read with get(), so both render as a null field, and leaving it out
            // keeps "no role" and "no user" the same shape.
            if (user.getRole() != null && user.getRole().getCode() != null) {
                roles.put(user.getId(), user.getRole().getCode());
            }
        }
        return new Resolved(Map.copyOf(refs), Map.copyOf(roles));
    }

    /**
     * @param people ids that named a real user, mapped to their reference. Ids
     *               that did not are simply absent, so {@code get} returns null
     *               and the field serialises as null
     * @param roles  the same ids mapped to their {@code RoleCode}
     */
    record Resolved(Map<Long, CommentDtos.UserRef> people, Map<Long, String> roles) {

        static Resolved empty() {
            return new Resolved(Map.of(), Map.of());
        }
    }

    /**
     * {@code full_name}, falling back to the username.
     *
     * <p>The fallback is not decoration: {@code full_name} is nullable on
     * {@code users} and an SSO-provisioned account can arrive without one. A
     * comment attributed to "null" is worse than one attributed to "rkumar",
     * which is at least the handle colleagues know.
     */
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
