package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * B-113 · OB-11 — the amber threshold, the scanner cadence and the escalation
 * matrix, held as configuration rather than as constants.
 *
 * <h2>Why any of this is editable</h2>
 *
 * <p>PHASE-2-BUILD-PLAN §2 locked three numbers: amber at 75% of TAT, the
 * scanner every 5 minutes, and the ladder at breach → +4 working hours → +8.
 * Those are seeds, not the contract, and the reason they are editable at all is
 * that every one of them is a judgement about a particular organisation's pace
 * — and the version that is wrong for a client is the version nobody can change
 * without a release.
 *
 * <h2>Read forward, never retroactively</h2>
 *
 * <p>Lowering amber from 75% to 60% re-colours every open step on the scanner's
 * next pass. It does not re-open a step that already breached under the old
 * setting and it does not re-send an escalation that has already gone. A
 * settings change that reissued historical notifications would mail every owner
 * in the organisation about steps they closed last week. Nothing here writes to
 * a step or an escalation, which is how that property is held: the sweep reads
 * these values when it runs, and there is no back-fill to get wrong.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code edutrack.ob-stats.amber-share} — B-120's property, whose own note
 * says "B-113 is where it becomes a row on the TAT settings screen, and that key
 * is the single thing it replaces". The property remains as the migration's
 * seed default, so a deployment that never opens OB-11 behaves exactly as it
 * does today.
 *
 * <p>And B-114's {@code reporting_manager_id} join, whose note says "§5.11's
 * escalation matrix is where this becomes configuration; that is B-113, and this
 * join is the single thing it replaces". <b>The scanner and the digest are not
 * repointed at these rows by this task.</b> They live in
 * {@code worker/onboarding/}, which is a different stream's directory, and
 * repointing them is a behaviour change to a running scanner that wants its own
 * review rather than a quiet ride-along. The rows exist and are readable; the
 * hand-over is stated on the PR.
 */
@Service
class ObSettingsService {

    private final ObSettingsRepository settings;

    ObSettingsService(ObSettingsRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    ObSettingsDtos.Settings get() {
        return settings.load();
    }

    /**
     * A wholesale replace.
     *
     * @throws InvalidLadderException the three rungs are not one per level, or
     *         they do not ascend
     */
    @Transactional
    ObSettingsDtos.Settings replace(ObSettingsDtos.WriteRequest request, Long actorUserId) {
        requireOneRungPerLevel(request.ladder());
        requireAscending(request.ladder());
        settings.save(request, actorUserId);
        return settings.load();
    }

    /**
     * <p>Bean Validation has already fixed the size at three; what it cannot say
     * is that they are three <em>different</em> levels. Three copies of L1 would
     * otherwise write one rung and silently leave L2 and L3 at whatever they
     * were, which is the worst outcome available: the screen would show what was
     * submitted and the scanner would fire something else.
     */
    private static void requireOneRungPerLevel(List<ObSettingsDtos.Rung> ladder) {
        Set<ObEscalationLevel> seen = EnumSet.noneOf(ObEscalationLevel.class);
        for (ObSettingsDtos.Rung rung : ladder) {
            if (!seen.add(rung.level())) {
                throw new InvalidLadderException(
                        "the ladder names " + rung.level() + " more than once");
            }
        }
        if (seen.size() != ObEscalationLevel.values().length) {
            throw new InvalidLadderException("the ladder must name every level exactly once");
        }
    }

    /**
     * <p>A ladder whose L3 fires before its L2 is not a ladder, and the failure
     * would show up weeks later as an escalation that reached a manager before
     * the owner it was meant to give a chance to.
     *
     * <p>Checked here rather than as a CHECK constraint because MySQL CHECKs are
     * per row and cannot see a sibling — and a trigger that rejected a
     * mid-transaction ordering would refuse a legitimate save that swaps two
     * rungs. This is the only place that sees all three at once.
     *
     * <p><b>Strictly ascending, not merely non-decreasing.</b> Two rungs at the
     * same hour both fire on the same sweep, which is one escalation as far as
     * anybody reading OB-02 can tell, and the second is then indistinguishable
     * from a duplicate.
     */
    private static void requireAscending(List<ObSettingsDtos.Rung> ladder) {
        List<ObSettingsDtos.Rung> byLevel = ladder.stream()
                .sorted(Comparator.comparing(ObSettingsDtos.Rung::level))
                .toList();
        for (int i = 1; i < byLevel.size(); i++) {
            ObSettingsDtos.Rung previous = byLevel.get(i - 1);
            ObSettingsDtos.Rung current = byLevel.get(i);
            if (current.afterWorkingHours() <= previous.afterWorkingHours()) {
                throw new InvalidLadderException(
                        current.level() + " fires at " + current.afterWorkingHours()
                                + " working hours, which is not after " + previous.level()
                                + " at " + previous.afterWorkingHours());
            }
        }
    }
}
