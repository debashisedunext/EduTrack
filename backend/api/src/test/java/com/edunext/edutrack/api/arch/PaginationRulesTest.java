package com.edunext.edutrack.api.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-053 · one paging kernel, enforced.
 *
 * <h2>What went wrong without this</h2>
 *
 * <p>CONVENTIONS.md §6 fixes the wire shape — {@code meta.nextCursor},
 * {@code meta.hasMore}, default 50, maximum 200 — and every stream implemented
 * it separately and correctly-ish. By the time A-053 looked, there were
 * <b>five</b> meta records in <b>three</b> different shapes:
 *
 * <pre>
 *   ChatDtos.SearchMeta   (nextCursor, hasMore)                  D
 *   ProjectDtos.Meta      (nextCursor, hasMore)                  B
 *   CalendarDtos.Meta     (nextCursor, hasMore, long totalCount) B
 *   ResourceDtos.Meta     (nextCursor, hasMore, Long totalCount) B
 *   NotificationDtos.Meta (nextCursor, hasMore, int unreadCount) D
 * </pre>
 *
 * <p>Note {@code long} against {@code Long} in two records written by the same
 * developer a fortnight apart — one can express "unknown" and the other cannot,
 * and no reviewer would catch that across two files. Nothing here is a bug
 * today. The point is that nothing stops the sixth, and the sixth is where the
 * fetch-one-extra boundary gets written wrong for the first time — a mistake
 * that never throws and surfaces as a row nobody sees.
 *
 * <h2>Why the rule keys off a field name and not a class name</h2>
 *
 * <p>{@code ChatRepository.ReadCursor(userId, lastReadMessageId)} is called a
 * cursor and is not one — it is a read-receipt position, D-051's, and a
 * name-based rule would either forbid it wrongly or need an exemption that
 * teaches the next person exemptions are normal. A record that declares a
 * component named {@code nextCursor} <em>is</em> pagination meta, whatever it
 * is called, and that is the thing this asserts.
 *
 * <h2>The grandfathered five are debt, not permission</h2>
 *
 * <p>They are listed by name so the list has to be edited to grow, and editing
 * it is the moment somebody asks why. Migrating them is not A-053's to do:
 * every one lives in Stream B's or Stream D's directory, and
 * {@code CalendarDtos.Meta} and {@code ResourceDtos.Meta} would lose their
 * {@code totalCount} — a contract change, and Ayush's call. See
 * {@code PageMeta}'s javadoc for why the canonical shape omits it.
 *
 * <p><b>Shrinking this set is the win.</b> When it empties, delete the rule's
 * exemption and this paragraph with it.
 */
class PaginationRulesTest {

    private static final JavaClasses CLASSES = ProductionClasses.get();

    /** The canonical shape. Everything else is either this or an exemption. */
    private static final String CANONICAL = "com.edunext.edutrack.common.pagination.PageMeta";

    /**
     * Written before A-053 existed. Each entry is a migration nobody has done
     * yet, in a directory Stream A does not own.
     */
    private static final Set<String> GRANDFATHERED = Set.of(
            "com.edunext.edutrack.api.feature.chat.ChatDtos$SearchMeta",
            "com.edunext.edutrack.api.feature.masters.projects.ProjectDtos$Meta",
            "com.edunext.edutrack.api.feature.masters.CalendarDtos$Meta",
            "com.edunext.edutrack.api.feature.masters.resources.ResourceDtos$Meta",
            "com.edunext.edutrack.api.feature.notifications.NotificationDtos$Meta");

    @Test
    @DisplayName("a sixth pagination meta record may not be written")
    void paginationMetaIsDeclaredOnlyOnce() {
        ArchRule rule = classes()
                .that(new DeclaresNextCursor())
                .should(new BeTheCanonicalMetaOrGrandfathered())
                .because("""
                        CONVENTIONS.md §6 defines one wire shape and five records already \
                        implement it three different ways. Use common.pagination.PageMeta \
                        and CursorPage.of, which decide the fetch-one-extra boundary once — \
                        getting it wrong loses rows silently rather than failing.""");

        rule.check(CLASSES);
    }

    /**
     * The exemption list is only honest while every name in it resolves. A
     * renamed or deleted record would leave a string here that forbids nothing
     * and reads exactly like one that does — the same failure {@code
     * ArchImportSanityTest} exists to catch for the import itself, and the same
     * one CODEOWNERS had for 17 of its 20 rules until 14 Aug.
     */
    @Test
    @DisplayName("every grandfathered name still resolves to a real class")
    void theExemptionListHasNoDeadEntries() {
        Set<String> present = CLASSES.stream().map(JavaClass::getFullName).collect(java.util.stream.Collectors.toSet());
        assertThat(present)
                .as("a stale exemption silently permits what it claims to grandfather")
                .containsAll(GRANDFATHERED);
    }

    /**
     * The rule is worthless if its predicate matches nothing — it would pass on
     * an empty set for ever. Five known records plus the canonical one is six,
     * and this asserts the predicate actually sees them.
     */
    @Test
    @DisplayName("the predicate finds the records it is meant to police")
    void thePredicateIsNotVacuous() {
        long matched = CLASSES.stream().filter(new DeclaresNextCursor()::test).count();
        assertThat(matched)
                .as("a predicate that matches nothing passes every rule, silently and for ever")
                .isGreaterThanOrEqualTo(GRANDFATHERED.size() + 1L);
    }

    // ── predicate and condition ──────────────────────────────────────────────

    /** A record carrying a {@code nextCursor} component is pagination meta, whatever it is named. */
    private static final class DeclaresNextCursor
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        private DeclaresNextCursor() {
            super("declare a nextCursor field");
        }

        @Override
        public boolean test(JavaClass type) {
            return type.getFields().stream().anyMatch(f -> "nextCursor".equals(f.getName()));
        }
    }

    private static final class BeTheCanonicalMetaOrGrandfathered extends ArchCondition<JavaClass> {

        private BeTheCanonicalMetaOrGrandfathered() {
            super("be common.pagination.PageMeta, or a record predating A-053");
        }

        @Override
        public void check(JavaClass type, ConditionEvents events) {
            String name = type.getFullName();
            boolean allowed = CANONICAL.equals(name) || GRANDFATHERED.contains(name);
            events.add(new SimpleConditionEvent(type, allowed,
                    allowed
                            ? name + " is permitted"
                            : name + " declares its own nextCursor. Use "
                                    + "com.edunext.edutrack.common.pagination.PageMeta with "
                                    + "CursorPage.of(rows, limit, cursorOf) instead — see A-053."));
        }
    }
}
