package com.edunext.edutrack.api.feature.onboarding.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-107 · the key is the only thing between an over-permissive bucket and every
 * file in the module, so it is tested as a security boundary rather than as a
 * string formatter.
 *
 * <p>Modelled on {@code AttachmentStorageKeyTest}, and the cases that matter are
 * the same ones: what {@code parse} refuses, and what {@code belongsTo} answers
 * for a key that is well formed but somebody else's.
 */
class ObAttachmentStorageKeyTest {

    private static final UUID OBJECT = UUID.fromString("4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41");

    @Nested
    @DisplayName("minting")
    class Minting {

        @Test
        @DisplayName("names the owner arm, so a client key can never address a step's object")
        void namesTheOwner() {
            assertThat(ObAttachmentStorageKey.mint(ObAttachmentOwner.CLIENT, 7).toString())
                    .startsWith("onboarding/clients/7/");
            assertThat(ObAttachmentStorageKey.mint(ObAttachmentOwner.STEP, 7).toString())
                    .startsWith("onboarding/steps/7/");
        }

        /**
         * A sequence would make enumeration free, which is the whole reason the
         * object id is random. Two mints of the same owner must not collide and
         * must not be guessable from each other.
         */
        @Test
        @DisplayName("mints a fresh random object id every time")
        void mintsRandomly() {
            assertThat(ObAttachmentStorageKey.mint(ObAttachmentOwner.CLIENT, 7))
                    .isNotEqualTo(ObAttachmentStorageKey.mint(ObAttachmentOwner.CLIENT, 7));
        }

        @Test
        @DisplayName("refuses an owner id that could not name a row")
        void refusesNonPositiveOwnerId() {
            assertThatThrownBy(() -> ObAttachmentStorageKey.mint(ObAttachmentOwner.CLIENT, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("round-trips what it minted")
        void roundTrips() {
            var minted = ObAttachmentStorageKey.mint(ObAttachmentOwner.SIGNOFF, 91);
            assertThat(ObAttachmentStorageKey.parse(minted.toString())).isEqualTo(minted);
        }

        /**
         * The value reaches an S3 {@code GetObject} and a presigner. A row that
         * somehow held a traversal or an absolute URL has to fail at the anchored
         * pattern here rather than at a storage client that may or may not
         * normalise it the way we assume.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "onboarding/clients/7/../../tickets/1/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "https://bucket.example/onboarding/clients/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41",
                "/onboarding/clients/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41",
                "onboarding/clients/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41/extra",
                "onboarding/clients/-7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41",
                "onboarding/clients/7/not-a-uuid",
                "",
        })
        @DisplayName("refuses anything that is not exactly the shape it writes")
        void refusesEverythingElse(String candidate) {
            assertThatThrownBy(() -> ObAttachmentStorageKey.parse(candidate))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses a null key rather than dereferencing it")
        void refusesNull() {
            assertThatThrownBy(() -> ObAttachmentStorageKey.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * An owner segment that is not one of the four arms is not an onboarding
         * key at all. Without this the pattern's {@code [a-z-]+} would happily
         * accept {@code onboarding/anything/7/…} and hand it to the presigner.
         */
        @Test
        @DisplayName("refuses a well-shaped key naming an owner arm that does not exist")
        void refusesUnknownOwnerSegment() {
            assertThatThrownBy(() -> ObAttachmentStorageKey.parse(
                    "onboarding/invoices/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Plan §2's separability requirement reaching the bucket. The three
         * namespaces are disjoint by construction and no parser accepts
         * another's keys, so nothing in this module can address a ticket's or a
         * chat thread's object.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "tickets/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41",
                "chat/7/4b0b7f1e-9c2a-4d3e-8f10-2a6c9e5d7b41",
        })
        @DisplayName("refuses another module's namespace")
        void refusesForeignNamespaces(String foreign) {
            assertThatThrownBy(() -> ObAttachmentStorageKey.parse(foreign))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("belongsTo")
    class BelongsTo {

        @Test
        @DisplayName("accepts the owner and id it was minted for")
        void acceptsItsOwn() {
            String key = new ObAttachmentStorageKey(ObAttachmentOwner.CLIENT, 7, OBJECT).toString();
            assertThat(ObAttachmentStorageKey.belongsTo(key, ObAttachmentOwner.CLIENT, 7)).isTrue();
        }

        /**
         * The cross-client read this method exists to stop: a row whose
         * {@code storage_key} had been edited to name another client's object
         * would otherwise have that object signed and served, through a column
         * nobody watches.
         */
        @Test
        @DisplayName("rejects a well-formed key belonging to another client")
        void rejectsAnotherOwnersId() {
            String key = new ObAttachmentStorageKey(ObAttachmentOwner.CLIENT, 8, OBJECT).toString();
            assertThat(ObAttachmentStorageKey.belongsTo(key, ObAttachmentOwner.CLIENT, 7)).isFalse();
        }

        @Test
        @DisplayName("rejects the same id under a different owner arm")
        void rejectsAnotherArm() {
            String key = new ObAttachmentStorageKey(ObAttachmentOwner.STEP, 7, OBJECT).toString();
            assertThat(ObAttachmentStorageKey.belongsTo(key, ObAttachmentOwner.CLIENT, 7)).isFalse();
        }

        /**
         * False rather than a throw. A single corrupted row costs its own
         * download URL; it must not take the whole listing with it.
         */
        @Test
        @DisplayName("answers false for an unparseable key instead of throwing")
        void survivesGarbage() {
            assertThat(ObAttachmentStorageKey.belongsTo("../etc/passwd",
                    ObAttachmentOwner.CLIENT, 7)).isFalse();
        }
    }

    /**
     * The segment is written into object keys that outlive the Java constant, so
     * it is spelled out rather than derived from {@code name()}. Pinned here
     * because renaming a constant would otherwise silently orphan every object
     * stored under the old spelling, with nothing failing to say so.
     */
    @Test
    @DisplayName("every owner arm has a stable, distinct segment")
    void segmentsAreStableAndDistinct() {
        assertThat(ObAttachmentOwner.CLIENT.segment()).isEqualTo("clients");
        assertThat(ObAttachmentOwner.STEP.segment()).isEqualTo("steps");
        assertThat(ObAttachmentOwner.SIGNOFF.segment()).isEqualTo("signoffs");
        assertThat(ObAttachmentOwner.PREREQ_TEMPLATE_TASK.segment())
                .isEqualTo("prereq-template-tasks");

        assertThat(java.util.Arrays.stream(ObAttachmentOwner.values())
                .map(ObAttachmentOwner::segment).distinct().count())
                .isEqualTo(ObAttachmentOwner.values().length);
    }
}
