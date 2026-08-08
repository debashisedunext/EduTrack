package com.edunext.edutrack.domain.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * The bell badge. Runs on every page load for every user, and is the reason
     * {@code ix_notifications_unread (user_id, is_read, created_at)} exists —
     * keep the argument order matching the index prefix.
     */
    long countByUserIdAndIsReadFalse(Long userId);

    /** The bell panel, newest first. */
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Everything raised about one ticket, across recipients. */
    List<Notification> findByTicketId(Long ticketId);
}
