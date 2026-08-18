package com.edunext.edutrack.api.feature.imports;

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
 * B-037 · turns the {@code imported_by} ids on a page of history into names.
 *
 * <p>The history panel's whole purpose is that a bad import can be
 * <em>identified</em>, and "at 14:02 on Tuesday, by someone" identifies half of
 * it. A batch id and a file name tell an Admin which run went wrong; the name
 * tells them who to ask what the spreadsheet was.
 *
 * <p>A near-twin of {@code EffortLogUserRefs} and, through it, of
 * {@code CommentUserRefs}. All three are deliberate copies rather than a shared
 * component: {@code CommentUserRefs}' javadoc states the precedent — a feature
 * declares its own view of a user reference, because a common DTO across four
 * streams is renegotiated every time one of them wants another field. This one
 * is the smallest of the three, returning a string rather than a record, because
 * {@code ImportDtos.Batch} already carries the id beside the name and a
 * two-field object for a batch row would be a shape with no second reader.
 *
 * <p>One query for the whole page, not one per row. A morning of an Admin
 * correcting a spreadsheet and re-uploading it is a dozen batches naming one
 * person.
 */
@Component
class ImportBatchUserNames {

    private final UserRepository users;

    ImportBatchUserNames(UserRepository users) {
        this.users = users;
    }

    /**
     * @param ids may contain nulls and duplicates. {@code imported_by} is
     *           nullable by design — {@code ImportCommitService} records the
     *           caller "best-effort", so an unidentifiable {@code dev-noauth}
     *           caller starts a run with no actor rather than being refused one
     * @return ids that named a real user, mapped to their display name. An id
     *         that did not is absent, so the panel renders the run without a name
     *         rather than inventing "Unknown user" for it — a deleted account
     *         should leave the run attributed to nobody, which is the truth,
     *         instead of to a placeholder that looks like a person
     */
    Map<Long, String> resolve(Collection<Long> ids) {
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
        Map<Long, String> names = new HashMap<>(found.size());
        for (User user : found) {
            if (user.getId() != null) {
                names.put(user.getId(), displayNameOf(user));
            }
        }
        return Map.copyOf(names);
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
