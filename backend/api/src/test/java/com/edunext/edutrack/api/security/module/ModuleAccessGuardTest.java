package com.edunext.edutrack.api.security.module;

import com.edunext.edutrack.api.security.CallerIdentity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-111 · the module gate.
 *
 * <p>Every assertion here is about a direction rather than a mechanism: the
 * gate has one job, and the only way it can fail usefully is by letting
 * somebody through. So the cases that matter are the ones where the answer is
 * ambiguous — no caller, no claim, an empty claim, a claim that arrived
 * malformed — and in all of them the expected answer is "blocked".
 */
class ModuleAccessGuardTest {

    private final ModuleAccessGuard guard = new ModuleAccessGuard();

    private static Optional<CallerIdentity> caller(String... modules) {
        return Optional.of(new CallerIdentity(7L, "ADMIN", List.of(), List.of(modules)));
    }

    @Nested
    @DisplayName("which paths it has an opinion about")
    class Guards {

        @Test
        void guardsTheOnboardingTree() {
            assertThat(guard.guards("/api/v1/onboarding/clients")).isTrue();
            assertThat(guard.guards("/api/v1/onboarding/journey-templates/4/steps")).isTrue();
        }

        @Test
        void guardsThePortalTreeToo() {
            // Plan §2.3 puts the client principal's routes in their own tree,
            // and those are onboarding routes. A staff caller without the
            // module must not reach them either.
            assertThat(guard.guards("/api/v1/portal/onboarding/home")).isTrue();
        }

        @Test
        void leavesTicketingAlone() {
            assertThat(guard.guards("/api/v1/tickets")).isFalse();
            assertThat(guard.guards("/api/v1/clients")).isFalse();
            assertThat(guard.guards("/api/v1/auth/login")).isFalse();
        }

        @Test
        void doesNotConfuseAPrefixWithASegment() {
            // `/api/v1/onboardingx` is not the onboarding tree. The guarded
            // prefixes end in a slash precisely so this cannot be read as one.
            assertThat(guard.guards("/api/v1/onboardingx/clients")).isFalse();
        }

        @Test
        void treatsANullPathAsUnguarded() {
            // Unguarded rather than blocked: a null path is not a request into
            // the module, and answering "blocks" here would 404 every route in
            // the application if the chain ever handed one over.
            assertThat(guard.guards(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("who it lets through")
    class Blocks {

        @Test
        void admitsACallerHoldingTheModule() {
            assertThat(guard.blocks(caller("TICKETING", "ONBOARDING"), "/api/v1/onboarding/clients"))
                    .isFalse();
        }

        @Test
        void refusesACallerHoldingOnlyTicketing() {
            // The disclosure this whole gate exists to prevent: this caller
            // must not learn the onboarding module is deployed.
            assertThat(guard.blocks(caller("TICKETING"), "/api/v1/onboarding/clients"))
                    .isTrue();
        }

        @Test
        void refusesACallerHoldingNoModules() {
            assertThat(guard.blocks(caller(), "/api/v1/onboarding/clients")).isTrue();
        }

        @Test
        void refusesAnUnidentifiableCaller() {
            // CallerIdentity.of returns empty for no authentication, an
            // anonymous token, an unrecognised principal or an unreadable sub,
            // and its javadoc requires callers to treat that as "sees nothing".
            // A gate that let this through on the grounds that authentication
            // should have caught it first is a gate that depends on another
            // guard being present.
            assertThat(guard.blocks(Optional.empty(), "/api/v1/onboarding/clients")).isTrue();
        }

        @Test
        void refusesTheClientPortalTreeJustTheSame() {
            assertThat(guard.blocks(caller("TICKETING"), "/api/v1/portal/onboarding/home"))
                    .isTrue();
        }

        @Test
        void hasNoOpinionOnTicketingRoutes() {
            // Not "allows" — has nothing to say. A caller with no modules at
            // all still reaches the ticketing routes their role permits,
            // because this gate answers one question and that is not it.
            assertThat(guard.blocks(caller(), "/api/v1/tickets")).isFalse();
            assertThat(guard.blocks(Optional.empty(), "/api/v1/tickets")).isFalse();
        }
    }

    @Nested
    @DisplayName("how the entitlement itself is read")
    class ModuleMatching {

        @Test
        void ignoresCase() {
            // The claim is minted from a database column and compared against a
            // constant in Java — two places a case can drift apart, and neither
            // is worth a 404 nobody can explain.
            assertThat(guard.blocks(caller("onboarding"), "/api/v1/onboarding/clients")).isFalse();
        }

        @Test
        void ignoresSurroundingWhitespace() {
            assertThat(guard.blocks(caller("  ONBOARDING  "), "/api/v1/onboarding/clients")).isFalse();
        }

        @Test
        void doesNotMatchOnAPrefixOfTheModuleName() {
            // "ONBOARD" is not "ONBOARDING". A startsWith comparison here would
            // grant the module to a code that merely begins like it.
            assertThat(guard.blocks(caller("ONBOARD"), "/api/v1/onboarding/clients")).isTrue();
        }
    }
}
