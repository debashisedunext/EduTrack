package com.edunext.edutrack.api.feature.fixtures.onboarding;

import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.ClientSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.JourneySpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.StepSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureData.TemplateSpec;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureSchedule.JourneySchedule;
import com.edunext.edutrack.api.feature.fixtures.onboarding.OnboardingFixtureSchedule.StepSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-101 · the walk, without a database.
 *
 * <p>{@link OnboardingFixtureSchedule} is the only real logic in the corpus and
 * the piece that decides what states it actually contains — whether there is an
 * overdue step in it at all, whether a completed step can be dated after the
 * one that follows it. Those are properties the SLA scanner and the TAT roll-up
 * will be tested against, so they are asserted here rather than inferred from
 * the fact that eight clients loaded without an exception.
 */
class OnboardingFixtureScheduleTest {

    private static final List<StepSpec> STANDARD = OnboardingFixtureData.STANDARD_SAAS_STEPS;
    private static final int TODAY = OnboardingFixtureData.TODAY_SERIAL;

    // ── the corpus's own claims ─────────────────────────────────────────────

    @Test
    @DisplayName("the template is the prototype's eight steps, 24 items, 4 documents, 3 sign-off gates")
    void theTemplateIsWhatTheBuildPlanAdopted() {
        assertThat(STANDARD).hasSize(8);
        assertThat(STANDARD.stream().mapToInt(s -> s.items().size()).sum()).isEqualTo(24);
        // Four, not the five PHASE-2-BUILD-PLAN.md §2 claims. The prototype's
        // TPL_STEPS carries one on Requirements confirmation, two on Data
        // migration and one on Admin & user training; the other five steps carry
        // none. The plan's count is off by one and this is the transcription, so
        // it asserts what the design actually says.
        assertThat(STANDARD.stream().mapToInt(s -> s.requiredDocs().size()).sum()).isEqualTo(4);
        assertThat(STANDARD.stream().filter(StepSpec::requiresSignoff).count()).isEqualTo(3);
        assertThat(STANDARD.stream().mapToInt(StepSpec::tatDays).max().orElseThrow()).isEqualTo(5);
        assertThat(STANDARD.get(0).name()).isEqualTo("Kickoff call");
        assertThat(STANDARD.get(7).name()).isEqualTo("Go-live sign-off");
    }

    @Test
    @DisplayName("every template step depends on an earlier one, so no journey can deadlock")
    void dependenciesAlwaysPointBackwards() {
        for (TemplateSpec template : OnboardingFixtureData.TEMPLATES) {
            for (int i = 0; i < template.steps().size(); i++) {
                Integer dependsOn = template.steps().get(i).dependsOnIndex();
                if (dependsOn != null) {
                    assertThat(dependsOn)
                            .as("%s step %d depends on step %d", template.name(), i, dependsOn)
                            .isLessThan(i);
                }
            }
        }
    }

    @Test
    @DisplayName("at most one active template version per product — the active_key unique index")
    void oneActiveVersionPerProduct() {
        for (var product : OnboardingFixtureData.PRODUCTS) {
            long active = OnboardingFixtureData.TEMPLATES.stream()
                    .filter(t -> t.productKey().equals(product.key()) && t.active())
                    .count();
            assertThat(active).as("active versions of %s", product.name()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("every journey's product is one the client actually bought")
    void everyJourneyHasAMatchingPurchase() {
        for (ClientSpec client : OnboardingFixtureData.CLIENTS) {
            for (JourneySpec journey : client.journeys()) {
                String productKey = templateFor(journey).productKey();
                assertThat(client.applications())
                        .as("%s has a journey on %s but has not bought it — "
                                + "fk_ob_journeys_application would refuse the row", client.name(), productKey)
                        .anyMatch(a -> a.productKey().equals(productKey));
            }
        }
    }

    @Test
    @DisplayName("every client has exactly one primary SPOC")
    void everyClientHasOnePrimaryContact() {
        for (ClientSpec client : OnboardingFixtureData.CLIENTS) {
            assertThat(client.contacts().stream().filter(c -> c.primary()).count())
                    .as("primary contacts at %s", client.name())
                    .isEqualTo(1);
        }
    }

    // ── the walk ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a finished journey is all DONE, with a completion date and nothing in flight")
    void greenValleyIsComplete() {
        JourneySchedule schedule = OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 8, 12));

        assertThat(schedule.steps()).hasSize(8).allMatch(StepSchedule::done);
        assertThat(schedule.steps()).noneMatch(StepSchedule::inFlight);
        assertThat(schedule.startSerial()).isEqualTo(12);
        assertThat(schedule.completedSerial()).isEqualTo(39);
    }

    @Test
    @DisplayName("a journey in flight is DONE behind, one step live, PENDING ahead")
    void sunriseIsMidJourney() {
        JourneySchedule schedule = OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 3, 58));

        assertThat(schedule.steps().subList(0, 3)).allMatch(StepSchedule::done);
        assertThat(schedule.steps().get(3).status()).isEqualTo("IN_PROGRESS");
        assertThat(schedule.steps().get(3).startSerial()).isEqualTo(66);
        assertThat(schedule.steps().get(3).finishSerial()).isNull();
        assertThat(schedule.steps().subList(4, 8)).allMatch(s -> "PENDING".equals(s.status()));
        assertThat(schedule.completedSerial()).as("an unfinished journey has no completion date").isNull();
    }

    @Test
    @DisplayName("the in-flight step's status carries the journey's override")
    void waitingAndBlockedReachTheStep() {
        JourneySchedule waiting =
                OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 1, 71).waitingOnClient());
        assertThat(waiting.steps().get(1).status()).isEqualTo("WAITING_ON_CLIENT");

        JourneySchedule blocked =
                OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 4, 55).blocked("vendor"));
        assertThat(blocked.steps().get(4).status()).isEqualTo("BLOCKED");
        assertThat(blocked.steps().get(4).inFlight()).isTrue();
    }

    @Test
    @DisplayName("a locked gate and a held journey have not started at all")
    void nothingRunsBeforeItsGate() {
        JourneySchedule locked =
                OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 0, 80).gateLocked());
        assertThat(locked.steps()).allMatch(s -> "PENDING".equals(s.status()));
        assertThat(locked.steps()).allMatch(s -> s.startSerial() == null);
        assertThat(locked.startSerial()).isNull();

        JourneySchedule held = OnboardingFixtureSchedule.walk(
                OnboardingFixtureData.BIOMETRIC_STEPS, JourneySpec.of("t3", 0, 65).heldBy("t1"));
        assertThat(held.steps()).allMatch(s -> "PENDING".equals(s.status()));
        assertThat(held.startSerial()).isNull();
    }

    @Test
    @DisplayName("nothing in the corpus is dated in the future")
    void noStepStartsOrFinishesAfterToday() {
        for (ClientSpec client : OnboardingFixtureData.CLIENTS) {
            for (JourneySpec journey : client.journeys()) {
                JourneySchedule schedule =
                        OnboardingFixtureSchedule.walk(templateFor(journey).steps(), journey);
                for (StepSchedule step : schedule.steps()) {
                    assertThat(step.startSerial()).as("%s start", client.name())
                            .satisfiesAnyOf(s -> assertThat(s).isNull(),
                                    s -> assertThat(s).isLessThanOrEqualTo(TODAY));
                    assertThat(step.finishSerial()).as("%s finish", client.name())
                            .satisfiesAnyOf(s -> assertThat(s).isNull(),
                                    s -> assertThat(s).isLessThanOrEqualTo(TODAY));
                }
            }
        }
    }

    @Test
    @DisplayName("a step never finishes before it starts, and never before the step it depends on")
    void theTimelineIsOrdered() {
        for (ClientSpec client : OnboardingFixtureData.CLIENTS) {
            for (JourneySpec journey : client.journeys()) {
                List<StepSpec> steps = templateFor(journey).steps();
                JourneySchedule schedule = OnboardingFixtureSchedule.walk(steps, journey);

                for (int i = 0; i < steps.size(); i++) {
                    StepSchedule step = schedule.steps().get(i);
                    if (step.startSerial() != null && step.finishSerial() != null) {
                        assertThat(step.finishSerial()).isGreaterThanOrEqualTo(step.startSerial());
                    }
                    Integer dependsOn = steps.get(i).dependsOnIndex();
                    if (dependsOn == null || step.startSerial() == null) {
                        continue;
                    }
                    StepSchedule predecessor = schedule.steps().get(dependsOn);
                    assertThat(predecessor.finishSerial())
                            .as("%s: '%s' started before '%s' finished",
                                    client.name(), steps.get(i).name(), steps.get(dependsOn).name())
                            .isNotNull()
                            .isLessThanOrEqualTo(step.startSerial());
                }
            }
        }
    }

    @Test
    @DisplayName("the corpus contains early, on-time and late completions — not one shape eight times")
    void completionsVary() {
        JourneySchedule schedule = OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 8, 12));

        // index 1 closes early: a 2-day TAT spent in 1.
        assertThat(elapsed(schedule, 1)).isLessThan(STANDARD.get(1).tatDays());
        // index 3 closes late: a 5-day TAT spent in 7.
        assertThat(elapsed(schedule, 3)).isGreaterThan(STANDARD.get(3).tatDays());
        // index 2 closes on its budget.
        assertThat(elapsed(schedule, 2)).isEqualTo(STANDARD.get(2).tatDays());
    }

    @Test
    @DisplayName("the corpus has a step that is already overdue, and one that is not")
    void thereIsSomethingForTheScannerToFind() {
        // Sunrise's Data migration: a 5-day TAT that started 15 days ago.
        JourneySchedule breached = OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 3, 58));
        int age = TODAY - breached.steps().get(3).startSerial();
        assertThat(age).isGreaterThan(STANDARD.get(3).tatDays());

        // Little Scholars' Kickoff call has not started, so it cannot be late.
        JourneySchedule locked =
                OnboardingFixtureSchedule.walk(STANDARD, JourneySpec.of("t1", 0, 80).gateLocked());
        assertThat(locked.steps().get(0).startSerial()).isNull();
    }

    private int elapsed(JourneySchedule schedule, int index) {
        StepSchedule step = schedule.steps().get(index);
        return step.finishSerial() - step.startSerial();
    }

    private TemplateSpec templateFor(JourneySpec journey) {
        return OnboardingFixtureData.TEMPLATES.stream()
                .filter(t -> t.key().equals(journey.templateKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no template " + journey.templateKey()));
    }
}
