package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-113 · OB-11's wire shapes — the constants PHASE-2-BUILD-PLAN §2 locked,
 * as configuration.
 *
 * <p>The bounds are declared here as Bean Validation because the contract's
 * header says annotations are the single source of truth for validation rules,
 * and repeated in {@code V20260909_1600}'s CHECK constraints because a service
 * is not the only thing that can write a row.
 */
final class ObSettingsDtos {

    private ObSettingsDtos() {
    }

    /**
     * <p>{@code updatedBy} is a {@code UserRef}-shaped projection rather than
     * the id: OB-11 shows "last changed by" beside a value somebody is about to
     * override, and an id would send the reader to look it up.
     */
    @Schema(name = "ObSettings")
    record Settings(
            int amberThresholdPercent,
            int scannerIntervalMinutes,
            List<Rung> ladder,
            UserRef updatedBy,
            Instant updatedAt
    ) {
    }

    record SettingsResponse(Settings data) {
    }

    /**
     * The minimum a screen needs to name a person.
     *
     * <p>{@code displayName} rather than {@code name}, because the contract's
     * {@code UserRef} is {@code id · displayName · avatarUrl · role · handle}
     * and a field called something else here would serialise to a key no
     * generated client has. Only the two required fields are served: OB-11
     * shows "last changed by" beside a value somebody is about to override, and
     * an avatar on that line would be decoration on an audit fact.
     */
    record UserRef(long id, String displayName) {
    }

    /**
     * One rung of the ladder.
     *
     * <p><b>{@code afterWorkingHours}, not wall-clock.</b> CLAUDE.md routes all
     * duration maths through the working calendar, and this is why a
     * Friday-evening breach escalates on Monday morning rather than at two on
     * Saturday.
     *
     * <p>{@code recipient} is a role resolved when the rung fires, never a user
     * id — storing a person sends L2 to somebody who has since left, and needs
     * re-editing every time the team changes.
     */
    @Schema(name = "ObEscalationRung")
    record Rung(
            @NotNull
            ObEscalationLevel level,

            /**
             * Zero on L1, which fires at the breach itself — so the lower bound
             * is 0 rather than 1.
             */
            @NotNull
            @Min(0)
            @Max(720)
            Integer afterWorkingHours,

            @NotNull
            Recipient recipient
    ) {
    }

    /**
     * <p>{@code BACKUP_OWNER} is a distinct value rather than a fallback baked
     * into {@code STEP_OWNER}, which is plan §1.1 #4's whole point: the backup
     * owner is who a leave-aware resolution reaches, and folding the two
     * together would make "escalate past the owner" unexpressible.
     */
    enum Recipient {
        STEP_OWNER, BACKUP_OWNER, ONBOARDING_MANAGER, OB_ADMIN
    }

    /**
     * The whole settings object — a wholesale replace behind one Save button,
     * which is why the operation is a {@code PUT} and why {@code If-Match} is
     * not optional on it.
     */
    @Schema(name = "ObSettingsWriteRequest")
    record WriteRequest(
            @NotNull
            @Min(1)
            @Max(99)
            Integer amberThresholdPercent,

            @NotNull
            @Min(1)
            @Max(60)
            Integer scannerIntervalMinutes,

            /**
             * Exactly three, one per {@code ObEscalationLevel}. Fixed at three
             * because that enum is closed and
             * {@code uq_ob_escalations_open (step_id, level, open_key)} is keyed
             * on it — a fourth rung would have no level to be. The intervals are
             * configuration; the number of rungs is not.
             *
             * <p>The ascending rule is not expressible here and is checked in
             * {@code ObSettingsService}: Bean Validation sees one element at a
             * time, and "L2 after L1" is a fact about the list.
             */
            @NotNull
            @Size(min = 3, max = 3)
            List<@Valid @NotNull Rung> ladder
    ) {
    }
}
