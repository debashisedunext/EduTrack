package com.edunext.edutrack.api.feature.transitions;

/**
 * C-045 · a hop was sealed and the next inserted — {@link TransitionService#advance}
 * raised this, {@link RibbonLiveBroadcaster} is what turns it into the two
 * pushes D-058 and {@code StageQueueBroadcaster}'s own javadoc describe.
 *
 * <p>An {@code ApplicationEvent} rather than a direct call, because both of
 * those pushes have to run <strong>after commit</strong> — {@code
 * StageQueueBroadcaster}'s own doc: "a queue told to refetch before the row
 * is visible reads the state it was told had changed and finds it
 * unchanged." {@code advance} runs inside {@code @Transactional}; publishing
 * here and listening with {@code @TransactionalEventListener(AFTER_COMMIT)}
 * is what defers delivery to the point the row is actually readable,
 * {@code NotificationWriter}/{@code PushDispatcher}'s own precedent for the
 * identical problem.
 */
record TicketStageAdvanced(long ticketId, long projectId, String fromStage, String toStage) {
}
