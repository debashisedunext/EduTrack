package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_template_stages} row for the given id. */
class StageGroupNotFoundException extends RuntimeException {

    StageGroupNotFoundException(long stageGroupId) {
        super("no journey template stage group " + stageGroupId);
    }
}
