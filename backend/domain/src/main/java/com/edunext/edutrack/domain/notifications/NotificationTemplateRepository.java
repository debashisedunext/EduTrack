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

    /**
     * B-022 · the S-15 grid, grouped the way it renders.
     *
     * <p>Ordered by {@code event_code} then {@code channel} so the screen gets
     * its event groups without sorting, and so two channels of the same event
     * are always adjacent. Alphabetical on the code rather than on a display
     * name: the display name is derived in the presentation layer, and sorting
     * on something the database does not hold would mean sorting in memory over
     * a list this small anyway.
     */
    List<NotificationTemplate> findAllByOrderByEventCodeAscChannelAsc();

    /** The uniqueness rule behind {@code uq_notification_templates}, checked
     *  before the insert so a duplicate is a 409 rather than a constraint
     *  violation surfacing as a 500. */
    boolean existsByEventCodeAndChannel(String eventCode, String channel);
}
