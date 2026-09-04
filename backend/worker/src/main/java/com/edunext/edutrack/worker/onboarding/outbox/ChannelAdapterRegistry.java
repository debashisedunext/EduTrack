package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-110 · the adapters this deployment has, by channel.
 *
 * <p>Built from whatever {@link ObChannelAdapter} beans exist, so a channel is
 * "supported" by the presence of a class and nothing else — no property to
 * flip, no list to keep in step. Two beans for one channel is a configuration
 * error and fails startup: the alternative, picking one silently, is how a
 * message goes out twice on two transports.
 */
@Component
public class ChannelAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelAdapterRegistry.class);

    private final Map<ObChannel, ObChannelAdapter> adapters;

    public ChannelAdapterRegistry(List<ObChannelAdapter> discovered) {
        Map<ObChannel, ObChannelAdapter> byChannel = new EnumMap<>(ObChannel.class);
        for (ObChannelAdapter adapter : discovered) {
            ObChannel channel = adapter.channel();
            if (channel == null) {
                throw new IllegalStateException(
                        adapter.getClass().getName() + " declares no channel");
            }
            ObChannelAdapter previous = byChannel.putIfAbsent(channel, adapter);
            if (previous != null) {
                throw new IllegalStateException("Two adapters for " + channel + ": "
                        + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
        this.adapters = Collections.unmodifiableMap(byChannel);

        Set<ObChannel> missing = EnumSet.allOf(ObChannel.class);
        missing.removeAll(byChannel.keySet());
        log.info("ob-outbox: adapters for {}; rows for {} will wait in the queue",
                byChannel.keySet(), missing.isEmpty() ? "no other channel" : missing);
    }

    public Optional<ObChannelAdapter> adapterFor(ObChannel channel) {
        return Optional.ofNullable(adapters.get(channel));
    }

    /** The channels the dispatcher may claim rows for. Never null, possibly empty. */
    public Set<ObChannel> supported() {
        return adapters.isEmpty() ? EnumSet.noneOf(ObChannel.class) : EnumSet.copyOf(adapters.keySet());
    }
}
