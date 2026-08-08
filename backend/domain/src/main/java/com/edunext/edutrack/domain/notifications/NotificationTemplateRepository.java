package com.edunext.edutrack.domain.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    /**
     * The render lookup: one row per (event, channel), guaranteed unique by
     * {@code uq_notification_templates}, so this can return a single value
     * rather than a list.
     */
    Optional<NotificationTemplate> findByEventCodeAndChannel(String eventCode, String channel);

    /** Deactivating a template silences that event on that channel. */
    List<NotificationTemplate> findByIsActiveTrue();
}
