package com.edunext.edutrack.api.feature.reports;

import java.math.BigDecimal;
import java.util.List;

/**
 * A-069 · the shape {@code GET /users/{userId}/profile-360} answers with.
 *
 * <p>Wider than the contract's original {@code Profile360Response}, which
 * carried six scalars and a list of stage codes. The additions are the ones a
 * screen cannot work without and a client should not have to derive: who the
 * person is, which window the figures cover, and how much open work sits at
 * each stage rather than merely which stages are in play.
 *
 * <p>The window matters most. Every figure below except {@code openNow} is
 * measured over a range, and a payload that stated none would leave the screen
 * captioning "closed this month" over whatever the server happened to choose.
 */
final class Profile360Dtos {

    private Profile360Dtos() {
    }

    record Profile360Response(Profile data) {
    }

    /**
     * @param openNow          open right now, not over the window. The one
     *                         genuinely current figure, and labelled separately
     *                         because everything beside it is historical.
     * @param slaCompliancePct against tickets that carried a commitment, null
     *                         when none did.
     * @param reworkRatePct    closed tickets that had been reopened at least
     *                         once, over closed. Null when nothing closed.
     */
    record Profile(Person person, String from, String to,
                   long openNow, long closedInWindow, BigDecimal effortHours,
                   BigDecimal slaCompliancePct, BigDecimal reworkRatePct,
                   List<StageLoad> currentStages) {
    }

    /**
     * @param managerName who they report to, by name rather than id. The screen
     *                    shows it and never links to it — a link would be a
     *                    second profile the viewer may not be entitled to see,
     *                    and resolving that per row is a permission check the
     *                    header has no business making.
     */
    record Person(long id, String fullName, String username, String email, String role,
                  String department, String designation, boolean active,
                  String joinedOn, String managerName) {
    }

    /** How much open work sits at one ribbon stage. */
    record StageLoad(String stage, long openCount) {
    }
}
