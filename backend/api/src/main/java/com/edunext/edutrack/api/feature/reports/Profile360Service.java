package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A-069 · S-28's Resource 360° profile.
 *
 * <h2>Why this lives in {@code feature/reports} and serves {@code /users/…}</h2>
 *
 * <p>The path is the contract's and is not negotiable. The package is a choice:
 * {@code masters/resources} is Stream B's (B-010, B-011), and this is a Stream A
 * screen that reuses A-066's scorecard arithmetic and A-063's scope vocabulary.
 * Putting it there would mean editing another stream's directory for a task that
 * is entirely this one's, so it sits with the reporting code it is made of.
 * Spring maps URLs, not packages.
 *
 * <h2>What this endpoint does and does not carry</h2>
 *
 * <p>§S-28 asks for five things: current open tickets, the complete historical
 * ticket list, an effort timeline, a velocity chart and a scorecard. Only the
 * <b>scorecard</b> and the stage breakdown are here.
 *
 * <p>The two lists come from the ticket list endpoint, which already paginates,
 * already scopes, and already renders — embedding them would be a second ticket
 * list with no cursor, and "complete historical" is precisely the list that must
 * not be returned whole. The timeline and the velocity chart come from
 * {@code /reports/resource-velocity?resourceId=}, which A-066 built and which
 * returns closed and effort per week for one person: exactly both charts.
 *
 * <p>So the screen composes four calls rather than one fat one, and every part
 * of it is a surface that already existed. That is the opposite of A-052's
 * "one call, not a waterfall of six" — and deliberately: A-052 is about a ticket
 * detail whose parts are all small and all needed at once. Here two of the five
 * are unbounded lists.
 */
@Service
class Profile360Service {

    /** The window the headline figures cover when the caller names none. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final Profile360Repository repository;

    Profile360Service(Profile360Repository repository) {
        this.repository = repository;
    }

    /**
     * The profile, or empty when the subject does not exist <b>or</b> the caller
     * may not see them.
     *
     * <p>One answer for both, which the controller turns into a 404. §2 grants a
     * PM their reportees and their projects; distinguishing "no such person"
     * from "not yours to see" would let anyone enumerate the staff list by id
     * and learn who exists — the same existence leak CLAUDE.md's row-scoping
     * rule closes for tickets, and a person is at least as sensitive as a
     * ticket.
     */
    Optional<Profile360Dtos.Profile> profile(CallerIdentity caller, long userId,
                                             LocalDate from, LocalDate to) {

        if (!repository.isVisibleTo(userId, caller.userId(), caller.roleCode())) {
            return Optional.empty();
        }

        Optional<Profile360Repository.Subject> subject = repository.subject(userId);
        if (subject.isEmpty()) {
            return Optional.empty();
        }

        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);

        Profile360Repository.Headline h = repository.headline(userId, start, end);

        List<Profile360Dtos.StageLoad> stages = repository.currentStages(userId).stream()
                .map(s -> new Profile360Dtos.StageLoad(s.stageCode(), s.openCount()))
                .toList();

        Profile360Repository.Subject s = subject.get();

        return Optional.of(new Profile360Dtos.Profile(
                new Profile360Dtos.Person(s.id(), s.fullName(), s.username(), s.email(),
                        s.roleCode(), s.department(), s.designation(), s.active(),
                        s.joinedOn() == null ? null : s.joinedOn().toString(), s.managerName()),
                start.toString(),
                end.toString(),
                h.openNow(),
                h.closed(),
                round(h.effortHours()),
                // Against tickets that carried a commitment, never against
                // everything closed — a ticket with no planned date can neither
                // meet nor breach an SLA, and counting it as met would flatter
                // whoever works on unplanned tickets. A-057 and A-066 both draw
                // this line, and a third figure disagreeing would be the one
                // somebody quotes.
                percent(h.onTime(), h.committed()),
                percent(h.reopened(), h.closed()),
                stages));
    }

    /** Null rather than 0% when nothing qualifies — see {@code ResourceScorecardRunner}. */
    private static BigDecimal percent(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }
}
