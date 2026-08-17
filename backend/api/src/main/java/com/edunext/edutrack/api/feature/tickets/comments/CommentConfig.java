package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * C-033 · registers {@link CommentProperties}.
 *
 * <p>{@code EduTrackApplication} carries no {@code @ConfigurationPropertiesScan},
 * so a {@code @ConfigurationProperties} record is not a bean until something
 * enables it — {@code AttachmentScanConfig} does the same for
 * {@code AttachmentProperties}. Without this the injection fails at startup with
 * a message naming the record and not the omission, which is a five-minute
 * detour nobody should have to repeat.
 *
 * <p>Deliberately empty otherwise. This feature has no beans to define; adding
 * one here later would put a bean definition somewhere nobody would look for it.
 */
@Configuration
@EnableConfigurationProperties(CommentProperties.class)
class CommentConfig {
}
