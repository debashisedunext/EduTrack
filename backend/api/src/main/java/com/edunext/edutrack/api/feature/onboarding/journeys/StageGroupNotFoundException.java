package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_template_stages} row for the given id. */
class StageGroupNotFoundException extends RuntimeException {

    StageGroupNotFoundException(long stageGroupId) {
        super("no journey template stage group " + stageGroupId);
    }

    private StageGroupNotFoundException(String message) {
        super(message);
    }

    /**
     * {@link ObJourneyTemplateService#ensureStageGroup} could not bind a stage
     * because the OB-15 master row is gone or has been retired.
     *
     * <p>Its own message rather than the one above: the id in hand is an
     * {@code ob_implementation_stages} id, and reporting it as a missing stage
     * <em>group</em> sends a reader looking in the wrong table. Reachable only
     * as a race — the import validates every Step name against the active
     * master before it resolves any of them — which is exactly when a precise
     * message is worth having.
     */
    static StageGroupNotFoundException forImplementationStage(long implementationStageId) {
        return new StageGroupNotFoundException(
                "implementation stage " + implementationStageId + " does not exist or is retired");
    }
}
