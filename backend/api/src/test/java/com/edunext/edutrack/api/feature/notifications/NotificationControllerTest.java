package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-041 · the query-parameter edges, which are the parts a database cannot
 * check for us.
 */
class NotificationControllerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final NotificationController controller = new NotificationController(service);

    private static Authentication caller() {
        return new UsernamePasswordAuthenticationToken(
                new DevPrincipal(7L, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()),
                null, List.of());
    }

    @Test
    @DisplayName("a mistyped tab is a 400, never a silent fall-through to all")
    void anUnknownTabIsRejected() {
        ResponseEntity<?> response = controller.list(caller(), "mention", null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Showing everything would look correct to whoever mistyped it.
        verify(service, never()).list(anyLong(), any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void aCursorThatDidNotComeFromUsIsA400() {
        ResponseEntity<?> response = controller.list(caller(), null, null, "not-a-cursor", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).list(anyLong(), any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void anAbsentTabQueriesAll() {
        controller.list(caller(), null, null, null, null);

        verify(service).list(eq(7L), eq(NotificationTab.ALL), eq(false), eq(null), anyInt());
    }

    @Test
    @DisplayName("a caller asking for a million rows gets the cap, not a 400")
    void theLimitIsClampedRatherThanRejected() {
        controller.list(caller(), null, null, null, 1_000_000);

        // The page size is our resource decision, not a contract the caller can
        // violate.
        verify(service).list(anyLong(), any(), anyBoolean(), any(), eq(100));
    }

    @Test
    void theBellDropdownIsJustASmallLimit() {
        controller.list(caller(), null, null, null, 10);

        verify(service).list(anyLong(), any(), anyBoolean(), any(), eq(10));
    }

    @Test
    @DisplayName("already-read answers 204, the same as just-marked")
    void markingSomethingTwiceIsNotAnError() {
        when(service.markRead(91L, 7L)).thenReturn(NotificationService.ReadOutcome.ALREADY_READ);

        assertThat(controller.markRead(caller(), 91L).getStatusCode())
                .as("the caller asked for a state, and it holds")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("somebody else's notification is a 404, never a 403")
    void anotherUsersNotificationIsNotFound() {
        when(service.markRead(91L, 7L)).thenReturn(NotificationService.ReadOutcome.NOT_FOUND);

        // A 403 would confirm that notification 91 exists and belongs to
        // someone else, which is the same existence leak CLAUDE.md forbids on
        // tickets.
        assertThat(controller.markRead(caller(), 91L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void markAllReadAlwaysSucceeds() {
        when(service.markAllRead(7L)).thenReturn(0);

        assertThat(controller.markAllRead(caller()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).markAllRead(7L);
    }
}
