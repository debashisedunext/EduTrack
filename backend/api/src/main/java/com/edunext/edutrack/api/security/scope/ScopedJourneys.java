package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A-112 · the door for the onboarding module. Every journey read in the
 * application goes through this bean, and it is the only place
 * {@link OnboardingScopeResolver}'s specification is composed in.
 *
 * <p>{@link ScopedTickets} with a different noun, and the argument for its
 * existence is unchanged: a guard nobody is obliged to call is decoration.
 * {@code OnboardingScopeResolver} can produce a perfect specification and the
 * module still leaks the first time somebody writes
 * {@code journeyRepository.findAll()} in a service, because nothing about that
 * line looks wrong. So the scope is not something feature code remembers to
 * add — it is something feature code cannot remove: these methods take the
 * caller and apply it themselves, and any extra criteria a feature needs are
 * {@code AND}-ed <em>on top of</em> the scope, never instead of it.
 *
 * <p>{@code ScopeGuardRulesTest} fails the build if any class in {@code api}
 * outside this package touches {@link ObJourneyRepository}, so the convention
 * is checked rather than remembered — the same enforcement A-037 put on
 * {@code TicketRepository}.
 *
 * <h2>This is the row scope, not the module gate</h2>
 *
 * <p>A-111's {@code ModuleAccessGuard} has already refused a caller with no
 * {@code ONBOARDING} grant before any of this runs. This class answers the
 * narrower question — <em>which</em> journeys, for a caller who may reach the
 * module at all — and the two are deliberately separate: one is a route
 * filter, this is a predicate on rows, and collapsing them would mean the gate
 * could be relaxed by editing a query.
 *
 * <h2>Absence, not refusal</h2>
 *
 * <p>An out-of-scope id comes back as {@link Optional#empty()} — the same
 * answer as an id that was never issued. {@link #require} turns that into
 * {@link JourneyNotFoundException}, a 404. There is no method here that
 * reports "exists but not yours", because having one would make it available
 * to be returned.
 */
@Component
public class ScopedJourneys {

    private final ObJourneyRepository journeys;
    private final OnboardingScopeResolver scope;

    ScopedJourneys(ObJourneyRepository journeys, OnboardingScopeResolver scope) {
        this.journeys = journeys;
        this.scope = scope;
    }

    /** @return the journey, or empty if it does not exist <em>or</em> is out of scope. */
    public Optional<ObJourney> byId(Authentication caller, long journeyId) {
        return journeys.findOne(scoped(caller, hasId(journeyId)));
    }

    /**
     * The same lookup for a detail route, with the 404 already decided.
     *
     * <p>Prefer this over {@link #byId} in a handler. {@code byId} hands back an
     * {@link Optional} and leaves the status code to whoever is writing the
     * endpoint, which means the correct answer depends on their remembering a
     * rule — and the wrong answer, {@code 403}, is the one a reviewer's eye
     * slides past because it reads as "access denied". A-035 records that
     * happening on the ticketing side. This method removes the choice: there is
     * no branch to write, so there is nothing to write incorrectly.
     *
     * @throws JourneyNotFoundException identically for a journey that does not
     *         exist and one the caller may not see
     */
    public ObJourney require(Authentication caller, long journeyId) {
        return byId(caller, journeyId).orElseThrow(JourneyNotFoundException::new);
    }

    public List<ObJourney> list(Authentication caller, Specification<ObJourney> criteria, Sort sort) {
        return journeys.findAll(scoped(caller, criteria), sort);
    }

    public Page<ObJourney> page(Authentication caller, Specification<ObJourney> criteria, Pageable pageable) {
        return journeys.findAll(scoped(caller, criteria), pageable);
    }

    /**
     * Batch form of {@link #byId}, for rendering references to journeys the
     * caller did not ask for by id. Ids outside the caller's scope are silently
     * absent from the result — the same "absence, not refusal" contract every
     * other method here keeps; a caller wanting to know <em>which</em> ids were
     * dropped is asking the question this class exists to refuse.
     */
    public List<ObJourney> byIds(Authentication caller, Collection<Long> journeyIds) {
        if (journeyIds == null || journeyIds.isEmpty()) {
            return List.of();
        }
        return journeys.findAll(scoped(caller, hasIdIn(journeyIds)));
    }

    /**
     * Counts only what the caller can see, which is what makes a list header
     * honest: a total that counted rows the body cannot show would tell a Step
     * Owner how many journeys exist for clients they have no access to.
     */
    public long count(Authentication caller, Specification<ObJourney> criteria) {
        return journeys.count(scoped(caller, criteria));
    }

    /**
     * May this caller see this journey at all — the question without the row.
     *
     * <p>Implemented by running the same specification rather than by re-stating
     * §3 as a boolean, so the two cannot drift: one rule, one evaluator.
     */
    public boolean canSee(Authentication caller, long journeyId) {
        return journeys.exists(scoped(caller, hasId(journeyId)));
    }

    /** The same question from an identity rather than a servlet {@link Authentication}. */
    public boolean canSee(CallerIdentity caller, long journeyId) {
        return journeys.exists(OnboardingScopeResolver.journeyScope(caller).and(hasId(journeyId)));
    }

    /**
     * The scope first, the feature's criteria second. Written this way round on
     * purpose: {@code scope.and(criteria)} cannot be turned into
     * {@code criteria} by a null, whereas a helper that started from the
     * caller's filter and appended the scope could be.
     */
    private Specification<ObJourney> scoped(Authentication caller, Specification<ObJourney> criteria) {
        Specification<ObJourney> mandatory = scope.journeyScope(caller);
        return criteria == null ? mandatory : mandatory.and(criteria);
    }

    private static Specification<ObJourney> hasId(long journeyId) {
        return (root, query, builder) -> builder.equal(root.get("id"), journeyId);
    }

    private static Specification<ObJourney> hasIdIn(Collection<Long> ids) {
        return (root, query, builder) -> root.get("id").in(ids);
    }
}
