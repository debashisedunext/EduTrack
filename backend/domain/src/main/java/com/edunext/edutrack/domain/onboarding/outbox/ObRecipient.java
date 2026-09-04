package com.edunext.edutrack.domain.onboarding.outbox;

/**
 * B-110 · who an onboarding notification is for.
 *
 * <p>{@code ob_notification_outbox} carries two recipient columns because §7's
 * events go to two populations that live in two tables: staff are
 * {@code users}, client SPOCs are {@code ob_client_contacts}. The CHECK
 * {@code ck_ob_outbox_recipient} insists exactly one is set. A sealed type
 * makes that a property of the value rather than something every caller has
 * to get right — there is no way to build a recipient with both or neither.
 */
public sealed interface ObRecipient {

    /** The value {@code recipient_type} stores. */
    String type();

    /** A member of staff — {@code users.id}. */
    record Staff(long userId) implements ObRecipient {

        public Staff {
            if (userId <= 0) {
                throw new IllegalArgumentException("userId must be positive");
            }
        }

        @Override
        public String type() {
            return "STAFF";
        }
    }

    /** A client contact — {@code ob_client_contacts.id}. */
    record Client(long contactId) implements ObRecipient {

        public Client {
            if (contactId <= 0) {
                throw new IllegalArgumentException("contactId must be positive");
            }
        }

        @Override
        public String type() {
            return "CLIENT";
        }
    }
}
