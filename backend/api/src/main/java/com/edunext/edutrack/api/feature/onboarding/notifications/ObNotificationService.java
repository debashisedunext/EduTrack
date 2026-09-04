package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.common.pagination.PageMeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * B-112 · OB-13, the onboarding notification centre.
 *
 * <p>The bell popover and the full page are the same query with a different
 * {@code limit}. PHASE-2-BUILD-PLAN.md §73 asks for <em>both</em> surfaces —
 * "the popover is the daily surface; a full page is needed for history and for
 * the digest links to land somewhere" — and they are two renderings of one
 * endpoint, not two endpoints. Two would mean two places to keep the tab filter
 * and the badge honest, which is D-041's reason and holds here unchanged.
 */
@Service
public class ObNotificationService {

    private final ObNotificationReadRepository repository;

    ObNotificationService(ObNotificationReadRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObNotificationDtos.ObNotificationListResponse list(long userId,
                                                             ObNotificationTab tab,
                                                             boolean unreadOnly,
                                                             Long cursor,
                                                             int limit) {
        // One row beyond the page, so `hasMore` is answered by what came back
        // rather than by a second COUNT over the same predicate.
        List<ObNotificationReadRepository.ObNotificationRow> rows =
                repository.list(userId, cursor, unreadOnly, tab.categoryCodes(), limit + 1);

        boolean hasMore = rows.size() > limit;
        List<ObNotificationReadRepository.ObNotificationRow> page =
                hasMore ? rows.subList(0, limit) : rows;

        // The cursor is the last row *sent*, never the probe row — A-053's
        // CursorPage.of makes that decision once for lists that use its base64
        // envelope, and this endpoint deliberately does not: its sibling
        // /notifications takes a plain row id, and two notification centres
        // whose cursors look different on the wire would be a difference with
        // no reason behind it. So the boundary is spelt out here, and the meta
        // shape is still PageMeta's.
        PageMeta pageMeta = hasMore
                ? new PageMeta(String.valueOf(page.getLast().id()), true)
                : PageMeta.last();

        return new ObNotificationDtos.ObNotificationListResponse(
                page.stream().map(ObNotificationService::toDto).toList(),
                new ObNotificationDtos.Meta(pageMeta, repository.unreadCount(userId)));
    }

    /**
     * What happened to a mark-read.
     *
     * <p>{@code ALREADY_READ} is separated from {@code MARKED} so the caller
     * cannot mistake "nothing changed" for "no such entry". Both answer 204 —
     * the caller asked for a state and it now holds either way — but collapsing
     * them here would make the 404 case impossible to tell apart.
     */
    public enum ReadOutcome { MARKED, ALREADY_READ, NOT_FOUND }

    @Transactional
    public ReadOutcome markRead(long id, long userId) {
        if (repository.markRead(id, userId)) {
            return ReadOutcome.MARKED;
        }
        // Nothing was updated: either it was already read, or it is not the
        // caller's. Those must not answer the same way — and the second is a
        // 404 rather than a 403, on CLAUDE.md's rule, because a 403 would
        // confirm that entry 91 exists and belongs to somebody else.
        return repository.exists(id, userId) ? ReadOutcome.ALREADY_READ : ReadOutcome.NOT_FOUND;
    }

    /**
     * @return how many were still unread. Ignores the open tab: the contract
     *         takes none there, and a "mark all read" that left some unread is
     *         a lie the badge contradicts a second later.
     */
    @Transactional
    public int markAllRead(long userId) {
        return repository.markAllRead(userId);
    }

    private static ObNotificationDtos.ObNotification toDto(
            ObNotificationReadRepository.ObNotificationRow row) {
        return new ObNotificationDtos.ObNotification(
                row.id(),
                row.eventKey(),
                row.category(),
                row.title(),
                row.body(),
                row.obClientId(),
                row.journeyId(),
                row.stepId(),
                row.isRead(),
                row.createdAt() == null ? null : row.createdAt().toInstant(),
                row.linkUrl());
    }
}
