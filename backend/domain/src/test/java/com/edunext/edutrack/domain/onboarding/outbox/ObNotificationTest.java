package com.edunext.edutrack.domain.onboarding.outbox;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-110 · the event record refuses what the table would refuse, before a
 * transaction is spent finding out.
 */
class ObNotificationTest {

    private static final ObRecipient STAFF = new ObRecipient.Staff(7);
    private static final ObRecipient CONTACT = new ObRecipient.Client(9);

    @Test
    void aboutStepDedupesOnEventChannelStepAndRecipient() {
        ObNotification n = ObNotification.aboutStep("TAT_REMINDER", ObChannel.EMAIL, CONTACT,
                3, 11, 412, Map.of("stepTitle", "Data migration"));

        assertThat(n.dedupeKey()).isEqualTo("TAT_REMINDER:EMAIL:step:412:contact:9");
        assertThat(n.obClientId()).isEqualTo(3);
        assertThat(n.journeyId()).isEqualTo(11);
        assertThat(n.stepId()).isEqualTo(412);
        assertThat(n.recipient().type()).isEqualTo("CLIENT");
    }

    @Test
    void aboutClientHasNoJourneyOrStepAndDedupesOnTheClient() {
        ObNotification n = ObNotification.aboutClient("CLIENT_LOGIN_CREATED", ObChannel.EMAIL, STAFF,
                3, Map.of());

        assertThat(n.dedupeKey()).isEqualTo("CLIENT_LOGIN_CREATED:EMAIL:client:3:user:7");
        assertThat(n.journeyId()).isNull();
        assertThat(n.stepId()).isNull();
        assertThat(n.recipient().type()).isEqualTo("STAFF");
    }

    @Test
    void theSameEventOnTwoChannelsAreTwoRows() {
        String email = ObNotification.dedupeKeyFor("SIGNOFF_REQUESTED", ObChannel.EMAIL, "step", 1, CONTACT);
        String whatsapp = ObNotification.dedupeKeyFor("SIGNOFF_REQUESTED", ObChannel.WHATSAPP, "step", 1, CONTACT);

        assertThat(email).isNotEqualTo(whatsapp);
    }

    @Test
    void theSameEventToTwoPeopleAreTwoRows() {
        String a = ObNotification.dedupeKeyFor("GATE_OPENED", ObChannel.EMAIL, "step", 1, new ObRecipient.Staff(1));
        String b = ObNotification.dedupeKeyFor("GATE_OPENED", ObChannel.EMAIL, "step", 1, new ObRecipient.Staff(2));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void payloadIsCopiedAndNullBecomesEmpty() {
        Map<String, Object> mutable = new HashMap<>(Map.of("k", "v"));
        ObNotification n = ObNotification.aboutClient("X", ObChannel.EMAIL, STAFF, 1, mutable);
        mutable.put("later", "edit");

        assertThat(n.payload()).containsOnlyKeys("k");
        assertThat(ObNotification.aboutClient("X", ObChannel.EMAIL, STAFF, 1, null).payload()).isEmpty();
    }

    @Test
    void refusesWhatTheColumnsWouldRefuse() {
        assertThatThrownBy(() -> new ObNotification(" ", ObChannel.EMAIL, STAFF, null, null, null, null, "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventKey");
        assertThatThrownBy(() -> new ObNotification("E".repeat(61), ObChannel.EMAIL, STAFF, null, null, null, null, "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("60");
        assertThatThrownBy(() -> new ObNotification("E", null, STAFF, null, null, null, null, "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel");
        assertThatThrownBy(() -> new ObNotification("E", ObChannel.EMAIL, null, null, null, null, null, "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient");
        assertThatThrownBy(() -> new ObNotification("E", ObChannel.EMAIL, STAFF, null, null, null, null, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedupeKey");
        assertThatThrownBy(() -> new ObNotification("E", ObChannel.EMAIL, STAFF, null, null, null, null, "k".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
    }

    @Test
    void recipientsRefuseNonPositiveIds() {
        assertThatThrownBy(() -> new ObRecipient.Staff(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObRecipient.Client(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void channelReadIsTolerant() {
        assertThat(ObChannel.of("email")).contains(ObChannel.EMAIL);
        assertThat(ObChannel.of(" WHATSAPP ")).contains(ObChannel.WHATSAPP);
        assertThat(ObChannel.of("PIGEON")).isEmpty();
        assertThat(ObChannel.of(null)).isEmpty();
    }
}
