package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelAdapterRegistryTest {

    private static ObChannelAdapter adapterFor(ObChannel channel) {
        return new ObChannelAdapter() {
            @Override
            public ObChannel channel() {
                return channel;
            }

            @Override
            public DeliveryOutcome deliver(ObOutboxMessage message) {
                return new DeliveryOutcome.Sent(null);
            }
        };
    }

    @Test
    void supportsExactlyTheChannelsThatHaveABean() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(adapterFor(ObChannel.EMAIL)));

        assertThat(registry.supported()).containsExactly(ObChannel.EMAIL);
        assertThat(registry.adapterFor(ObChannel.EMAIL)).isPresent();
        assertThat(registry.adapterFor(ObChannel.WHATSAPP)).isEmpty();
        assertThat(registry.adapterFor(ObChannel.IN_APP)).isEmpty();
    }

    @Test
    void addingAChannelIsAddingAClass() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(
                List.of(adapterFor(ObChannel.EMAIL), adapterFor(ObChannel.WHATSAPP)));

        assertThat(registry.supported()).containsExactlyInAnyOrder(ObChannel.EMAIL, ObChannel.WHATSAPP);
    }

    @Test
    void noAdaptersMeansNothingIsClaimed() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of());

        assertThat(registry.supported()).isEmpty();
    }

    @Test
    void twoAdaptersForOneChannelFailStartup() {
        assertThatThrownBy(() -> new ChannelAdapterRegistry(
                List.of(adapterFor(ObChannel.EMAIL), adapterFor(ObChannel.EMAIL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two adapters for EMAIL");
    }

    @Test
    void anAdapterWithNoChannelFailsStartup() {
        assertThatThrownBy(() -> new ChannelAdapterRegistry(List.of(adapterFor(null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no channel");
    }
}
