package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-113 · the two facts about a ladder that Bean Validation cannot state, and
 * the one thing the settings must never do.
 */
class ObSettingsServiceTest {

    private static final long ACTOR = 7L;

    private ObSettingsRepository repository;
    private ObSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(ObSettingsRepository.class);
        service = new ObSettingsService(repository);
        when(repository.load()).thenReturn(seeded());
    }

    private static ObSettingsDtos.Settings seeded() {
        return new ObSettingsDtos.Settings(75, 5, List.of(
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L2, 4, ObSettingsDtos.Recipient.BACKUP_OWNER),
                rung(ObEscalationLevel.L3, 8, ObSettingsDtos.Recipient.ONBOARDING_MANAGER)),
                null, null);
    }

    private static ObSettingsDtos.Rung rung(ObEscalationLevel level, int hours,
                                            ObSettingsDtos.Recipient recipient) {
        return new ObSettingsDtos.Rung(level, hours, recipient);
    }

    private static ObSettingsDtos.WriteRequest request(List<ObSettingsDtos.Rung> ladder) {
        return new ObSettingsDtos.WriteRequest(60, 10, ladder);
    }

    private static List<ObSettingsDtos.Rung> validLadder() {
        return List.of(
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L2, 4, ObSettingsDtos.Recipient.BACKUP_OWNER),
                rung(ObEscalationLevel.L3, 8, ObSettingsDtos.Recipient.ONBOARDING_MANAGER));
    }

    @Test
    @DisplayName("the seeded values are PHASE-2-BUILD-PLAN §2's, and are the starting point")
    void seedsAreTheLockedConstants() {
        ObSettingsDtos.Settings settings = service.get();

        assertThat(settings.amberThresholdPercent()).isEqualTo(75);
        assertThat(settings.scannerIntervalMinutes()).isEqualTo(5);
        assertThat(settings.ladder()).extracting(ObSettingsDtos.Rung::afterWorkingHours)
                .containsExactly(0, 4, 8);
    }

    @Test
    @DisplayName("a valid ladder saves")
    void savesAValidLadder() {
        service.replace(request(validLadder()), ACTOR);

        verify(repository).save(any(), any());
    }

    @Test
    @DisplayName("L1 may fire at zero — it fires at the breach itself")
    void l1AtZeroIsValid() {
        service.replace(request(validLadder()), ACTOR);

        verify(repository).save(any(), any());
    }

    @Test
    @DisplayName("refuses a ladder that does not ascend")
    void refusesADescendingLadder() {
        List<ObSettingsDtos.Rung> ladder = List.of(
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L2, 8, ObSettingsDtos.Recipient.BACKUP_OWNER),
                rung(ObEscalationLevel.L3, 4, ObSettingsDtos.Recipient.ONBOARDING_MANAGER));

        // An escalation that reached a manager before the owner it was meant to
        // give a chance to — and it would show up weeks later, not now.
        assertThatExceptionOfType(InvalidLadderException.class)
                .isThrownBy(() -> service.replace(request(ladder), ACTOR));
        verify(repository, never()).save(any(), any());
    }

    @Test
    @DisplayName("refuses two rungs at the same hour — they fire on one sweep and read as a duplicate")
    void refusesEqualHours() {
        List<ObSettingsDtos.Rung> ladder = List.of(
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L2, 4, ObSettingsDtos.Recipient.BACKUP_OWNER),
                rung(ObEscalationLevel.L3, 4, ObSettingsDtos.Recipient.ONBOARDING_MANAGER));

        assertThatExceptionOfType(InvalidLadderException.class)
                .isThrownBy(() -> service.replace(request(ladder), ACTOR));
    }

    @Test
    @DisplayName("refuses a ladder naming one level twice, which would silently leave the others alone")
    void refusesADuplicateLevel() {
        List<ObSettingsDtos.Rung> ladder = List.of(
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L1, 4, ObSettingsDtos.Recipient.BACKUP_OWNER),
                rung(ObEscalationLevel.L3, 8, ObSettingsDtos.Recipient.ONBOARDING_MANAGER));

        // The worst outcome available: the screen would show what was submitted
        // and the scanner would fire something else.
        assertThatExceptionOfType(InvalidLadderException.class)
                .isThrownBy(() -> service.replace(request(ladder), ACTOR));
        verify(repository, never()).save(any(), any());
    }

    @Test
    @DisplayName("the order the client sent them in does not matter — the level does")
    void acceptsAnyOrder() {
        List<ObSettingsDtos.Rung> shuffled = List.of(
                rung(ObEscalationLevel.L3, 8, ObSettingsDtos.Recipient.ONBOARDING_MANAGER),
                rung(ObEscalationLevel.L1, 0, ObSettingsDtos.Recipient.STEP_OWNER),
                rung(ObEscalationLevel.L2, 4, ObSettingsDtos.Recipient.BACKUP_OWNER));

        service.replace(request(shuffled), ACTOR);

        verify(repository).save(any(), any());
    }

    @Test
    @DisplayName("saving touches settings only — never a step, an escalation or a notification")
    void neverRetroactive() {
        service.replace(request(validLadder()), ACTOR);

        // "Thresholds are read forward, never retroactively." A settings change
        // that reissued historical notifications would mail every owner in the
        // organisation about steps they closed last week. The repository is the
        // only collaborator this service has, which is how that holds.
        verify(repository).save(any(), any());
        assertThat(ObSettingsService.class.getDeclaredFields()).hasSize(1);
    }
}
