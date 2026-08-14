package com.edunext.edutrack.domain.notifications.push;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D-045 · wiring for the push sender.
 *
 * <p>Declared here rather than by {@code @Component} on each class, because one
 * of them needs a {@link Clock} and <strong>only the worker defines one</strong>.
 * Component-scanning them broke every {@code api} test at once with
 * "No qualifying bean of type java.time.Clock" — a failure that says nothing
 * about push and everything about which module happened to declare a bean.
 *
 * <p>{@link ObjectProvider} rather than {@code @ConditionalOnMissingBean}: the
 * conditional is only reliable in auto-configuration, where Boot controls the
 * ordering. In ordinary configuration it silently depends on which class the
 * scanner reaches first, which is exactly the kind of thing that works until
 * somebody adds a class alphabetically earlier.
 *
 * <p>So the worker's fixed clock is honoured where it exists, and everything
 * else gets the system clock without needing to know push exists.
 */
@Configuration
@EnableConfigurationProperties(PushKeys.class)
public class PushConfiguration {

    @Bean
    PushEncryption pushEncryption() {
        return new PushEncryption();
    }

    @Bean
    VapidSigner vapidSigner(ObjectProvider<Clock> clock) {
        return new VapidSigner(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    WebPushSender webPushSender(PushEncryption encryption, VapidSigner vapid, PushKeys keys) {
        return new WebPushSender(encryption, vapid, keys);
    }
}
