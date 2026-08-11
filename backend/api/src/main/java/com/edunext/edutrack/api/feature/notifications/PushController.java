package com.edunext.edutrack.api.feature.notifications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * D-045 · opting a browser in to push, and out again.
 *
 * <p>Two paths rather than one resource: the public key is a property of the
 * deployment and the subscription is a property of the caller, and giving the
 * key a {@code /me} path would imply it varied per user.
 */
@RestController
@Tag(name = "notifications")
class PushController {

    private final PushSubscriptionService push;

    PushController(PushSubscriptionService push) {
        this.push = push;
    }

    @GetMapping(path = "/api/v1/push/public-key", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPushPublicKey", summary = "The VAPID application server key")
    ResponseEntity<?> publicKey() {
        return push.publicKey()
                .<ResponseEntity<?>>map(key -> ResponseEntity.ok(PushDtos.PushPublicKeyResponse.of(key)))
                // 404 rather than an empty 200: "push is not configured here" is
                // a different fact from "your key is the empty string", and a
                // client that got the latter would happily subscribe with it and
                // then never receive anything.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(notConfigured()));
    }

    @PostMapping(path = "/api/v1/me/push-subscriptions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "subscribeToPush", summary = "Register this browser for push")
    ResponseEntity<?> subscribe(Authentication authentication,
                                @RequestBody PushDtos.PushSubscriptionRequest request) {

        return push.subscribe(CurrentUser.idOf(authentication), request)
                .<ResponseEntity<?>>map(field -> ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(invalid(field)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/api/v1/me/push-subscriptions")
    @Operation(operationId = "unsubscribeFromPush", summary = "Forget this browser")
    ResponseEntity<Void> unsubscribe(Authentication authentication,
                                     @RequestParam("endpoint") String endpoint) {
        // 204 whether or not it was there, and whether or not it was theirs.
        // The alternative — 404 for an endpoint that exists but belongs to
        // somebody else — would answer "does this endpoint exist" to anyone who
        // asked, and the delete is already scoped so nothing is destroyed.
        push.unsubscribe(CurrentUser.idOf(authentication), endpoint);
        return ResponseEntity.noContent().build();
    }

    /** RFC 9457, per CONVENTIONS.md §3. */
    private static Map<String, Object> notConfigured() {
        return Map.of(
                "type", "https://edutrack/errors/not-configured",
                "title", "Push is not configured",
                "status", HttpStatus.NOT_FOUND.value(),
                "detail", "This deployment has no VAPID key pair, so browsers cannot subscribe");
    }

    private static Map<String, Object> invalid(String field) {
        return Map.of(
                "type", "https://edutrack/errors/validation",
                "title", "Invalid push subscription",
                "status", HttpStatus.BAD_REQUEST.value(),
                "detail", "The browser's subscription could not be stored",
                "errors", Map.of(field, java.util.List.of("is missing or malformed")));
    }
}
