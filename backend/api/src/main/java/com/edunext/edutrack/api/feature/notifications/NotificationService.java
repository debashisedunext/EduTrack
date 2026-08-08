package com.edunext.edutrack.api.feature.notifications;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * D-041 · the notification centre (S-26).
 *
 * <p>The bell dropdown and the full page are the same query with a different
 * {@code limit} — "last 10" is not a second endpoint, and building one would
 * mean two places to keep the tab filter honest.
 */
@Service
public class NotificationService {

    /** Blueprint §S-26: the dropdown shows the last 10. */
    static final int BELL_LIMIT = 10;

    private final NotificationReadRepository repository;

    NotificationService(NotificationReadRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page list(long userId, NotificationTab tab, boolean unreadOnly, Long cursor, int limit) {
        // One row beyond the page, so `hasMore` is answered by what came back
        // rather than by a second COUNT over the same predicate.
        List<NotificationReadRepository.NotificationRow> rows =
                repository.list(userId, cursor, unreadOnly, tab.eventCodes(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<NotificationReadRepository.NotificationRow> page =
                hasMore ? rows.subList(0, limit) : rows;

        String nextCursor = hasMore ? String.valueOf(page.getLast().id()) : null;

        return new Page(
                page.stream().map(NotificationService::toDto).toList(),
                new NotificationDtos.Meta(nextCursor, hasMore, repository.unreadCount(userId)));
    }

    /**
     * What happened to a mark-read.
     *
     * <p>{@code AlreadyRead} is separated from {@code Marked} so the caller
     * cannot mistake "nothing changed" for "no such notification". Both answer
     * 204 — the caller asked for a state that now holds either way — but
     * collapsing them here would make the 404 case impossible to tell apart.
     */
    public enum ReadOutcome { MARKED, ALREADY_READ, NOT_FOUND }

    @Transactional
    public ReadOutcome markRead(long id, long userId) {
        if (repository.markRead(id, userId)) {
            return ReadOutcome.MARKED;
        }
        // Nothing was updated. Either it was already read, or it is not the
        // caller's — and those must not answer the same way. 404 rather than
        // 403 for the second, on CLAUDE.md's rule: a 403 would confirm that
        // notification 91 exists and belongs to somebody else.
        return repository.exists(id, userId) ? ReadOutcome.ALREADY_READ : ReadOutcome.NOT_FOUND;
    }

    @Transactional
    public int markAllRead(long userId) {
        return repository.markAllRead(userId);
    }

    private static NotificationDtos.Notification toDto(NotificationReadRepository.NotificationRow row) {
        return new NotificationDtos.Notification(
                row.id(),
                // The stored string, not a parsed enum. A row written by a
                // newer deploy must render rather than fail the whole page.
                row.eventCode(),
                row.title(),
                row.body(),
                row.ticketCode(),
                row.isRead(),
                row.createdAt().toInstant(),
                row.linkUrl());
    }

    public record Page(List<NotificationDtos.Notification> data, NotificationDtos.Meta meta) {
    }
}
