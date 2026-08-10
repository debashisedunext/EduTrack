package com.edunext.edutrack.api.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * D-012 · STOMP over WebSocket, replacing the blueprint's Socket.IO
 * (PLAN.md §2.2). Socket.IO's auto-reconnect and transport fallback are
 * covered by SockJS on the client.
 *
 * <p>The broker is Spring's <em>simple</em> in-memory broker, not a full STOMP
 * relay — we have no RabbitMQ or ActiveMQ in the stack. That broker only knows
 * about sessions attached to its own JVM, so on its own a second instance
 * would never see the first instance's messages. {@link RealtimeRelay} closes
 * that gap over Redis pub/sub.
 *
 * <p>Destination naming (blueprint §9.3 — {@code /topic/ticket.{id}} and the
 * rest) is D-014's job. This class only establishes the prefixes those
 * destinations live under.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** The single WebSocket handshake endpoint the frontend connects to (D-015). */
    public static final String STOMP_ENDPOINT = "/ws";

    private final String[] allowedOrigins;
    private final SubscriptionAuthorisation subscriptionAuthorisation;

    public WebSocketConfig(
            @Value("${edutrack.realtime.allowed-origins:http://localhost:5173}") String[] allowedOrigins,
            SubscriptionAuthorisation subscriptionAuthorisation) {
        this.allowedOrigins = allowedOrigins;
        this.subscriptionAuthorisation = subscriptionAuthorisation;
    }

    /**
     * D-013 · every SUBSCRIBE passes the scope guard before the broker sees it.
     *
     * <p>On the <em>inbound</em> channel deliberately. Filtering what the broker
     * sends out instead would mean the subscription still exists and the check
     * runs on every delivered message — the wrong cost, and it leaves a
     * subscription the client believes in.
     */
    @Override
    public void configureClientInboundChannel(
            org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(subscriptionAuthorisation);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic — fan-out rooms; /queue — per-user destinations (§9.3).
        registry.enableSimpleBroker("/topic", "/queue");

        // Client → server messages are routed to @MessageMapping methods.
        // Nothing maps these yet; chat (D-050) is the first real consumer.
        registry.setApplicationDestinationPrefixes("/app");

        // Turns a send to /user/{id}/queue/events into that user's session
        // queue. Blueprint §9.3's `user:{id}` room.
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origins are explicit rather than "*": the handshake carries the
        // session cookie, so a wildcard would let any page open an
        // authenticated socket on a logged-in user's behalf.
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();
    }
}
