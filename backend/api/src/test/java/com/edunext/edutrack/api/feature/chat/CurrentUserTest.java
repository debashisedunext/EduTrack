package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The regression test the 500 got past — see
 * {@code feature.notifications.CurrentUserTest}, which is its deliberate twin
 * for the same reason the two {@link CurrentUser} classes are twins.
 */
class CurrentUserTest {

    @Test
    @DisplayName("a real access token resolves to its sub claim")
    void realChainPrincipal() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "HS256")
                .subject("42").claim("role", "SUPPORT").build();

        assertThat(CurrentUser.idOf(new JwtAuthenticationToken(token, List.of(), "42"))).isEqualTo(42L);
    }

    @Test
    @DisplayName("dev-noauth still resolves, so unblocking B, C and D is not traded away")
    void devPrincipalStillWorks() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new DevPrincipal(7L, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()), null, List.of());

        assertThat(CurrentUser.idOf(authentication)).isEqualTo(7L);
    }

    @Test
    @DisplayName("nobody identifiable still fails loudly rather than defaulting to a user id")
    void unidentifiableCallerThrows() {
        assertThatThrownBy(() -> CurrentUser.idOf(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no identifiable principal");
    }
}
