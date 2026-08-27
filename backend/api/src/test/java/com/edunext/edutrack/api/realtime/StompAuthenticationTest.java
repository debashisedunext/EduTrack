package com.edunext.edutrack.api.realtime;

import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The CONNECT frame carries the token; this is what reads it. Without it the
 * socket is anonymous and {@link SubscriptionAuthorisation} refuses every
 * SUBSCRIBE under real authentication — see that class and
 * {@link StompAuthentication} for what that broke.
 */
class StompAuthenticationTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final JwtAuthoritiesConverter authorities = mock(JwtAuthoritiesConverter.class);
    private final MessageChannel channel = mock(MessageChannel.class);

    private final StompAuthentication interceptor = new StompAuthentication(decoder, authorities);

    private final TestingAuthenticationToken authenticated =
            new TestingAuthenticationToken("42", "n/a", "ticket.read");

    @Test
    @DisplayName("a CONNECT carrying a valid bearer token gets that user on the session")
    void connectWithTokenSetsTheUser() {
        Jwt jwt = jwt();
        when(decoder.decode("good-token")).thenReturn(jwt);
        when(authorities.convert(jwt)).thenReturn(authenticated);

        StompHeaderAccessor accessor = connect("Bearer good-token");
        interceptor.preSend(message(accessor), channel);

        // Read back off the SAME accessor the message was built with — a copy
        // would show null here, which is the whole point of using getAccessor
        // rather than StompHeaderAccessor.wrap.
        assertThat(accessor.getUser()).isSameAs(authenticated);
    }

    @Test
    @DisplayName("a CONNECT with no Authorization header is left anonymous, not refused")
    void connectWithoutTokenIsLeftAnonymous() {
        StompHeaderAccessor accessor = connect(null);

        Message<?> result = interceptor.preSend(message(accessor), channel);

        assertThat(result).isNotNull();
        assertThat(accessor.getUser()).isNull();
        verify(decoder, never()).decode(any());
    }

    @Test
    @DisplayName("a token that does not verify leaves the socket anonymous rather than dropping it")
    void badTokenDoesNotKillTheSocket() {
        when(decoder.decode("expired")).thenThrow(new JwtException("expired"));

        StompHeaderAccessor accessor = connect("Bearer expired");

        // SockJS reconnects on a drop, so failing the CONNECT would turn an
        // expired token into a reconnect loop instead of one legible refusal.
        Message<?> result = interceptor.preSend(message(accessor), channel);

        assertThat(result).isNotNull();
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    @DisplayName("a non-Bearer scheme is ignored — nothing is handed to the decoder")
    void nonBearerIsIgnored() {
        StompHeaderAccessor accessor = connect("Basic YWRtaW46YWRtaW4=");

        interceptor.preSend(message(accessor), channel);

        assertThat(accessor.getUser()).isNull();
        verify(decoder, never()).decode(any());
    }

    @Test
    @DisplayName("frames other than CONNECT are passed through untouched")
    void subscribeIsNotTouched() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/events");
        accessor.setNativeHeader("Authorization", "Bearer good-token");

        interceptor.preSend(message(accessor), channel);

        // The user is established once, on CONNECT. Re-reading it per frame
        // would let a client swap identity mid-session.
        assertThat(accessor.getUser()).isNull();
        verify(decoder, never()).decode(any());
    }

    @Test
    @DisplayName("the shared JwtDecoder and JwtAuthoritiesConverter are used, not a second copy")
    void usesTheSharedChainBeans() {
        Jwt jwt = jwt();
        when(decoder.decode("good-token")).thenReturn(jwt);
        when(authorities.convert(jwt)).thenReturn(authenticated);

        interceptor.preSend(message(connect("Bearer good-token")), channel);

        // One implementation of "who is this and what may they do" — the same
        // pair the HTTP chain runs. A second would drift.
        verify(decoder).decode(eq("good-token"));
        verify(authorities).convert(eq(jwt));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static StompHeaderAccessor connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return accessor;
    }

    /**
     * Built the way Spring builds an inbound STOMP frame, so {@code getAccessor}
     * finds it <em>and</em> it is still mutable when the interceptor runs.
     *
     * <p>{@code setLeaveMutable(true)} is not a test convenience:
     * {@code getMessageHeaders()} seals an accessor, and without it
     * {@code setUser} throws "Already immutable". Spring's own
     * {@code StompSubProtocolHandler} sets exactly this flag on inbound frames
     * for exactly this reason - so an interceptor can attach the principal.
     * Omitting it here would have made the production code look broken.
     */
    private static Message<byte[]> message(MessageHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("good-token")
                .header("alg", "HS256")
                .subject("42")
                .claim("role", "QA")
                .issuedAt(Instant.parse("2026-08-27T09:00:00Z"))
                .expiresAt(Instant.parse("2026-08-27T10:00:00Z"))
                .build();
    }
}
