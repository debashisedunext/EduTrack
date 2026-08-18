package com.edunext.edutrack.api.arch;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * A-037 · the append-only rule, enforced against callers rather than against
 * the three interfaces.
 *
 * <h2>What this adds to {@code AppendOnlyContractTest}</h2>
 *
 * <p>B-005's reflection test already asserts that {@code TicketHistoryRepository},
 * {@code TicketEffortLogRepository} and {@code TicketStageTransitionRepository}
 * publish no mutator. It does so over a hard-coded list of three classes, which
 * is exactly right for the failure it was written for — someone swaps
 * {@code extends Repository} for {@code extends JpaRepository} to get
 * {@code findAll} for free.
 *
 * <p>It cannot see two things. The first is a <em>fourth</em> append-only type:
 * the list is a literal, so a new one is unprotected until somebody remembers
 * to add it, and the person adding an append-only table is the least likely
 * person to know that list exists. The rules here are stated over
 * {@code assignableTo(AppendOnly.class)}, so a new implementor is covered on
 * the commit that declares it.
 *
 * <p>The second is the service layer — the half CLAUDE.md actually words as the
 * rule: <em>"No service method may expose {@code update()} or {@code delete()}
 * on them."</em> A repository can be immaculate while the service wrapping it
 * offers {@code deleteEntry(id)} that inserts a tombstone, or
 * {@code updateHours()} that appends a correction. Both are defensible
 * implementations behind indefensible names, and the name is what the next
 * caller reads. A-040 builds those services in M2, so this rule lands first and
 * constrains them rather than being retrofitted around whatever gets written.
 *
 * <p><b>{@code correct*} is deliberately legal.</b> A correction <em>is</em> the
 * sanctioned operation — a new compensating row carrying {@code correctsEntryId},
 * like an accounting reversal. Banning the verb would push the next author
 * towards {@code updateEntry}, which is the outcome this rule exists to
 * prevent.
 */
class AppendOnlyRulesTest {

    /**
     * Mutation verbs. Anchored with a required uppercase letter after the verb
     * so {@code updatedAt} and {@code deletionPolicy} are not caught, and
     * {@code correct}, {@code append}, {@code insert} and {@code record} are
     * absent because each of them is the operation that is allowed.
     */
    private static final String MUTATORS =
            "^(update|delete|remove|modify|edit|purge|truncate|overwrite|rewrite|amend"
                    + "|save|merge|drop|clear|reset|revise|patch)([A-Z].*)?$";

    /** A-040's write path. The only package allowed to hold one of the three repositories. */
    private static final String JOURNAL_PACKAGE = "com.edunext.edutrack.domain.journal..";

    /** The three protected tables as they appear in a URL. */
    private static final Pattern PROTECTED_RESOURCE =
            Pattern.compile("(?i).*/(history|effort-logs?|stage-transitions?)(/.*)?");


    /**
     * A-071 · the audit log, as it appears in a URL.
     *
     * <p>Kept apart from {@link #PROTECTED_RESOURCE} rather than folded into it,
     * because the guarantee is genuinely weaker and merging the two would state
     * otherwise. {@code audit_logs} has four layers — no writer method beyond
     * {@code AuditTrail.record}, no mutating route, {@code SELECT, INSERT} only
     * for {@code edutrack_app}, and V20260818_1500's two triggers — but it has
     * no hash chain, so it lacks the fifth thing the three protected tables
     * have: a way to detect tampering that defeated the other four. Same rule,
     * different footing, said in two names rather than one.
     */
    private static final Pattern EXPORT_ONLY_RESOURCE =
            Pattern.compile("(?i).*/audit-logs?(/.*)?");

    private static final Set<RequestMethod> MUTATING_VERBS =
            Set.of(RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /**
     * A class that holds one of the three repositories — which, once A-040
     * lands, is what "an append-only service" means. Fields are the right
     * signal rather than the package: the constraint follows the collaborator,
     * so it applies to whichever stream ends up owning the wrapper.
     */
    private static final DescribedPredicate<JavaClass> HOLD_AN_APPEND_ONLY_REPOSITORY =
            new DescribedPredicate<>("hold an append-only repository") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return javaClass.getAllFields().stream()
                            .anyMatch(field -> field.getRawType().isAssignableTo(AppendOnly.class));
                }
            };

    /**
     * The CLAUDE.md sentence, as a build failure: no service method may expose
     * {@code update()} or {@code delete()} on the three protected tables.
     *
     * <p>{@code allowEmptyShould} because there is a legitimate window in which
     * nothing holds one of these repositories — A-040 has not landed and the
     * only current holder is a fixture generator. A rule that went red the day
     * its subject disappeared would be read as "the rule is broken" and
     * deleted, rather than as "nothing to guard yet".
     */
    @Test
    void noAppendOnlyServiceExposesUpdateOrDelete() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat(HOLD_AN_APPEND_ONLY_REPOSITORY)
                .should().haveNameMatching(MUTATORS)
                .allowEmptyShould(true)
                .because("""
                        ticket_history, ticket_effort_logs and ticket_stage_transitions are \
                        insert-only. A correction is a new compensating row carrying \
                        correctsEntryId, so name it correctX — a method called updateX or \
                        deleteX is a promise the storage cannot keep, whatever it does \
                        underneath.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * A-040 · the door rule. Nothing outside {@code domain.journal} may hold one
     * of the three append-only repositories.
     *
     * <p>{@link TicketJournal} is A-040's single write path, and A-034's
     * sentence about {@code ScopedTickets} applies to it word for word: the
     * journal can be perfect and the chain still forks the first time somebody
     * types {@code historyRepository.insert(entry)} in a service, because
     * nothing about that line looks wrong. It does not look wrong in review
     * either — it is one method call on a repository that publishes nothing
     * else, and the rule it skips (take {@code SELECT … FOR UPDATE} on the
     * parent ticket first, PLAN.md §3.7) is invisible at the call site. The
     * damage is invisible too: a forked chain is silently correct until A-044's
     * nightly verifier reports our own race as tampering, on a ticket nobody has
     * touched for months.
     *
     * <p>The two rules above constrain what an append-only <em>holder</em> may
     * be called and what a repository may inherit. Neither says who may hold
     * one, which is the half that decides whether the journal is a door or a
     * suggestion.
     *
     * <h2>The one exclusion</h2>
     *
     * <p>{@code areNotAssignableTo(AppendOnly.class)} keeps the three
     * repositories and {@code AppendOnlyImpl} out of their own rule — extending
     * an interface is a dependency on it, so without this the rule's first
     * failure would be {@code TicketHistoryRepository} declaring itself.
     *
     * <h2>There is no declared bypass any more (A-042)</h2>
     *
     * <p>A-040 shipped this with a second exclusion, {@code @DirectAppend}, on
     * A-037's precedent for {@code @UnscopedAccess} — declared at the site with
     * a mandatory reason rather than as a name in a test file. Exactly one class
     * ever used it, {@code SingleTicketFixture}, whose argument was sound while
     * it lasted: a single-threaded seed loader has no concurrent append to fork
     * the chain against.
     *
     * <p>A-042 removed the argument rather than the annotation's discipline.
     * Once the journal computes the chain, the cost of not going through it is
     * no longer a race — it is a row with a NULL {@code row_hash}, and 200 of
     * those in the seeded corpus would force A-044's verifier to treat NULL as
     * acceptable. A verifier that skips unhashed rows can be defeated by
     * unhashing a row. So the exemption went, and the journal is now a door with
     * no declared way around it. If a future class genuinely has no chain to
     * extend, the answer is a new annotation with a fresh argument, not this one
     * revived.
     */
    @Test
    void theProtectedTablesAreWrittenOnlyThroughTheJournal() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(JOURNAL_PACKAGE)
                .and().areNotAssignableTo(AppendOnly.class)
                .should().dependOnClassesThat().areAssignableTo(AppendOnly.class)
                .because("""
                        every append to ticket_history, ticket_effort_logs or \
                        ticket_stage_transitions has to hold the per-ticket lock first, or two \
                        concurrent writes read the same chain tail and fork it (PLAN.md §3.7) — \
                        and since A-042 it also has to be hashed into that ticket's chain, or \
                        the row lands with a NULL row_hash that A-044 cannot verify. \
                        TicketJournal does both; a repository call does neither, and nothing \
                        about the call site shows the difference. Inject TicketJournal.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * The inheritance half, stated over the interface rather than over a list
     * of three names — so the fourth append-only table is covered by the commit
     * that creates it.
     */
    @Test
    void noAppendOnlyRepositoryInheritsCrudMutators() {
        ArchRule rule = noClasses()
                .that().areAssignableTo(AppendOnly.class)
                .and().areInterfaces()
                .should().beAssignableTo(CrudRepository.class)
                .because("""
                        CrudRepository and JpaRepository publish delete, deleteById, \
                        deleteAll and a save that merges a detached instance into an \
                        UPDATE. Extend the bare Repository marker plus AppendOnly: not \
                        inheriting a mutator is a guarantee, hoping nobody calls it is \
                        not.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * Sealing a stage transition — {@code exited_at} NULL → timestamp — is the
     * single mutation the A-008 trigger permits, and {@code @Modifying} is the
     * only way to write one past an {@code @Immutable} entity. So every
     * {@code @Modifying} query on an append-only repository must be that one.
     */
    @Test
    void theOnlyModifyingQueryOnAnAppendOnlyRepositoryIsSeal() {
        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().areAssignableTo(AppendOnly.class)
                .and().areAnnotatedWith(Modifying.class)
                .should().haveName("seal")
                .because("""
                        PLAN.md §3.6: sealing is an explicit JPQL update on exactly the \
                        three columns the trigger allows. Any other @Modifying query is a \
                        rewrite of committed, hash-chained history — and the chain \
                        verifier will report it as tampering, correctly.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * CLAUDE.md, layer four: "No {@code PUT}, {@code PATCH} or {@code DELETE}
     * route may be registered for {@code /history} or {@code /effort-logs}."
     *
     * <p>The other three layers are asserted somewhere — the service surface
     * above, the {@code edutrack_app} grant in A-010, the triggers in A-008.
     * This one was guaranteed only by nobody having written the route yet,
     * which is not a layer. Stream C owns the ticket detail routes and will
     * write the History tab against a {@code GET}; the failure this catches is
     * the well-meaning {@code DELETE .../history/{entryId}} added to support an
     * "undo" button that the storage model cannot honour.
     */
    @Test
    void noRouteOffersToMutateAnAppendOnlyResource() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should(mutateAnAppendOnlyResource())
                .because("""
                        an append-only resource has no PUT, PATCH or DELETE to offer. A \
                        correction is a POST of a compensating entry; an undo is not \
                        available and a route implying otherwise is a promise the \
                        A-008 trigger will refuse at 3am.""");

        rule.check(ProductionClasses.get());
    }

    /**
     * A-071 · S-16 is "export only, never editable", and this is what stops the
     * second half of that sentence quietly becoming untrue.
     *
     * <p>Stricter than the rule above in one respect: <b>POST is banned too.</b>
     * On {@code ticket_history} a POST is the correction mechanism and must stay
     * — a reversal is a new row. The audit log has no such affordance and must
     * not grow one: a route that lets a caller submit an audit entry is a route
     * that lets a caller forge one, with whatever actor, action and timestamp
     * they choose, and no reader of the table could tell it from a row the
     * application wrote. Rows reach {@code audit_logs} through
     * {@code AuditTrail.record} alone, called from inside the application by
     * code that already knows who the caller is.
     *
     * <p>The failure this catches is the reasonable-sounding one: an "annotate
     * this entry" or "mark reviewed" route added to a screen that shows a wall
     * of entries and no way to respond to them. The right shape for that is a
     * new row, not an edit to an old one — and if it is ever built, it belongs
     * under its own resource, not under this path.
     */
    @Test
    void noRouteOffersToEditTheAuditLog() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .should(writeToTheAuditLog())
                .because("""
                        the audit log is export-only. It has no edit, no delete and no \
                        submit: a route that accepts an entry accepts a forged one, and \
                        nothing in the table distinguishes it from a row the application \
                        wrote. AuditTrail.record is the only writer and it takes no \
                        request.""");

        rule.check(ProductionClasses.get());
    }

    private static ArchCondition<JavaMethod> writeToTheAuditLog() {
        return new ArchCondition<>("map a writing verb onto the audit log") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!mutates(method) && !method.isAnnotatedWith(PostMapping.class)
                        && !declaresVerb(method, RequestMethod.POST)) {
                    return;
                }
                for (String path : fullPathsOf(method)) {
                    if (EXPORT_ONLY_RESOURCE.matcher(path).matches()) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                "%s maps a writing verb onto %s"
                                        .formatted(method.getFullName(), path)));
                    }
                }
            }
        };
    }

    /** {@code @RequestMapping(method = POST)}, the long way of writing it. */
    private static boolean declaresVerb(JavaMethod method, RequestMethod verb) {
        return method.tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> List.of(mapping.method()).contains(verb))
                .orElse(false);
    }

    private static ArchCondition<JavaMethod> mutateAnAppendOnlyResource() {
        return new ArchCondition<>("map PUT, PATCH or DELETE onto an append-only resource") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!mutates(method)) {
                    return;
                }
                for (String path : fullPathsOf(method)) {
                    if (PROTECTED_RESOURCE.matcher(path).matches()) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                "%s maps a mutating verb onto %s"
                                        .formatted(method.getFullName(), path)));
                    }
                }
            }
        };
    }

    private static boolean mutates(JavaMethod method) {
        if (method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(PatchMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class)) {
            return true;
        }
        return method.tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> List.of(mapping.method()).stream().anyMatch(MUTATING_VERBS::contains))
                .orElse(false);
    }

    /** Class-level prefix × method-level path, for every mapping shape in use. */
    private static List<String> fullPathsOf(JavaMethod method) {
        List<String> prefixes = new ArrayList<>(declaredPaths(
                method.getOwner().tryGetAnnotationOfType(RequestMapping.class).orElse(null)));
        if (prefixes.isEmpty()) {
            prefixes = new ArrayList<>(List.of(""));
        }

        Set<String> suffixes = new LinkedHashSet<>();
        suffixes.addAll(declaredPaths(method.tryGetAnnotationOfType(PutMapping.class).orElse(null)));
        suffixes.addAll(declaredPaths(method.tryGetAnnotationOfType(PatchMapping.class).orElse(null)));
        suffixes.addAll(declaredPaths(method.tryGetAnnotationOfType(DeleteMapping.class).orElse(null)));
        suffixes.addAll(declaredPaths(method.tryGetAnnotationOfType(RequestMapping.class).orElse(null)));
        if (suffixes.isEmpty()) {
            suffixes.add("");
        }

        List<String> full = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String suffix : suffixes) {
                full.add(prefix + suffix);
            }
        }
        return full;
    }

    /**
     * {@code value} and {@code path} are Spring {@code @AliasFor} twins, and
     * ArchUnit hands back the raw annotation values rather than Spring's
     * merged view — so a mapping declared {@code path = "/holidays/{id}"} has
     * an <em>empty</em> {@code value()}. Reading only one of the two would make
     * this rule silently blind to every route in {@code CalendarController},
     * which is the shape most of this codebase uses.
     */
    private static Set<String> declaredPaths(Annotation mapping) {
        Set<String> paths = new LinkedHashSet<>();
        switch (mapping) {
            case null -> {
                return paths;
            }
            case RequestMapping m -> {
                paths.addAll(List.of(m.value()));
                paths.addAll(List.of(m.path()));
            }
            case PutMapping m -> {
                paths.addAll(List.of(m.value()));
                paths.addAll(List.of(m.path()));
            }
            case PatchMapping m -> {
                paths.addAll(List.of(m.value()));
                paths.addAll(List.of(m.path()));
            }
            case DeleteMapping m -> {
                paths.addAll(List.of(m.value()));
                paths.addAll(List.of(m.path()));
            }
            default -> {
            }
        }
        return paths;
    }
}
