package com.edunext.edutrack.api.realtime;

import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame, so the socket knows who is on it.
 *
 * <h2>⚠ Written in Stream D's directory - needs their sign-off</h2>
 *
 * <p>{@code api/realtime/} is Stream D's per TEAM-PLAN.md §6 and this is their
 * work. It was written here because it is what stopped C-062's stage queue
 * updating, and because nothing in any backlog claims it. CODEOWNERS requests
 * the review automatically; if Stream D would rather own it, this is a starting
 * point to take over rather than a decision imposed on them.
 *
 * <h2>What was broken</h2>
 *
 * <p>Nothing established a principal on the socket at all. The handshake is a
 * SockJS/WebSocket upgrade from the browser, which cannot set an
 * {@code Authorization} header, and the app is stateless JWT - so there was no
 * session for Spring to propagate and the STOMP session was anonymous.
 * {@link SubscriptionAuthorisation} then refused <em>every</em> SUBSCRIBE with
 * "the socket carries no identified user", including {@code /user/queue/events}
 * which it otherwise allows unconditionally.
 *
 * <p>That is not a stage-queue defect, it is realtime being entirely inert
 * under real authentication: chat, notifications, the live ribbon and the
 * queue nudges all subscribe through the same guard. It went unnoticed because
 * the {@code dev-noauth} profile puts a {@code DevPrincipal} on the socket,
 * which is the one shape the guard understood.
 *
 * <p><b>The client has been sending the token all along.</b>
 * {@code frontend/src/realtime/client.ts} sets
 * {@code connectHeaders = { Authorization: `Bearer ${token}` }} on activation.
 * Only the server end was missing, which is why the browser console showed a
 * refusal rather than a connection failure.
 *
 * <h2>Why a rejected token is left unauthenticated rather than refused here</h2>
 *
 * <p>A CONNECT with a bad or absent token proceeds with no user attached, and
 * {@link SubscriptionAuthorisation} refuses the first SUBSCRIBE. Failing the
 * CONNECT instead would drop the socket, and SockJS reconnects on a drop - so
 * an expired token would become a reconnect loop rather than one legible
 * error. The frame the client can actually report is the one it already
 * reports.
 */
@Component
class StompAuthentication implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(StompAuthentication.class);

    private final JwtDecoder jwtDecoder;
    private final JwtAuthoritiesConverter authorities;

    StompAuthentication(JwtDecoder jwtDecoder, JwtAuthoritiesConverter authorities) {
        this.jwtDecoder = jwtDecoder;
        this.authorities = authorities;
    }

    /**
     * <b>{@code getAccessor}, not {@code StompHeaderAccessor.wrap}.</b>
     * {@code wrap} returns a copy, so {@code setUser} on it would be discarded
     * and this class would appear to do nothing - the failure mode is a socket
     * that still refuses every subscription with the fix apparently applied.
     * {@code getAccessor} returns the mutable accessor the message was built
     * with, which is what Spring's own CONNECT-authentication guidance uses.
     * {@link SubscriptionAuthorisation} may keep using {@code wrap} because it
     * only reads.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        Authentication authentication = authenticate(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (authentication != null) {
            accessor.setUser(authentication);
        }
        return message;
    }

    /**
     * The token, verified by the same {@link JwtDecoder} the HTTP chain uses
     * and converted by the same {@link JwtAuthoritiesConverter} - one
     * implementation of "who is this and what may they do", not a second one
     * that drifts. {@code CallerIdentity}'s own javadoc makes the same point
     * about the two principal shapes it accepts.
     *
     * @return {@code null} when there is no usable token, which leaves the
     *         socket anonymous by design - see the class note
     */
    private Authentication authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return null;
        }
        try {
            Jwt jwt = jwtDecoder.decode(authorizationHeader.substring(BEARER.length()).trim());
            return authorities.convert(jwt);
        } catch (JwtException rejected) {
            // Not logged at warn: an expired token on a reconnect is ordinary,
            // and the SUBSCRIBE refusal that follows is the event worth seeing.
            log.debug("realtime: CONNECT carried a token that did not verify", rejected);
            return null;
        }
    }
}
