package com.edunext.edutrack.domain.onboarding;

/**
 * C-115 · {@code ob_escalations.level} — the scanner's L1 → L2 → L3 ladder
 * (plan §5.11). Closed, matching {@code ck_ob_escalations_level} and the
 * contract's {@code ObEscalationLevel}: a fourth rung has no level to be.
 */
public enum ObEscalationLevel {
    L1, L2, L3
}
