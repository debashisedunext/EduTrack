package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentType;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentTypePolicy;
import com.edunext.edutrack.api.feature.tickets.attachments.ImageMetadataStripper;
import com.edunext.edutrack.domain.chat.ChatAttachment;
import com.edunext.edutrack.domain.chat.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-053 · {@link ChatAttachmentService} — §7.6's file and image share.
 *
 * <p>What is worth pinning here is not that a row is inserted. It is the four
 * properties that stop this being a second, weaker attachment pipeline: the
 * safety beans are the ones C-025 uses, the type is the sniffed one, a URL is
 * minted only for a CLEAN row, and a stranger's thread is a 404.
 */
class ChatAttachmentServiceTest {

    private static final long THREAD = 7L;
    private static final long USER = 12L;

    private final ChatRepository threads = mock(ChatRepository.class);
    private final ChatAttachmentRepository attachments = mock(ChatAttachmentRepository.class);
    private final AttachmentTypePolicy types = mock(AttachmentTypePolicy.class);
    private final ImageMetadataStripper stripper = mock(ImageMetadataStripper.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final ChatAttachmentScanTask scans = mock(ChatAttachmentScanTask.class);
    private final AttachmentProperties properties = new AttachmentProperties(
            Duration.ofMinutes(5), 1024, 52_428_800L, 20, Duration.ofMinutes(15),
            new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false),
            new AttachmentProperties.Thumbnail(true, 320, 320));

    private final ChatAttachmentService service = new ChatAttachmentService(
            threads, attachments, types, stripper, storage, scans, properties);

    @BeforeEach
    void setUp() {
        when(threads.threadForParticipant(THREAD, USER)).thenReturn(Optional.of(
                new ChatRepository.ThreadAnchor(THREAD, "TICKET", 1L, 1L, "CRM-26-00347", "CRM")));
        when(types.reconcile(anyString(), any()))
                .thenReturn(new AttachmentTypePolicy.Accepted(AttachmentType.PNG, "image/png"));
        when(stripper.strip(any(), any())).thenAnswer(call -> call.getArgument(1));
        when(attachments.saveAndFlush(any())).thenAnswer(call -> {
            ChatAttachment row = call.getArgument(0);
            row.setId(99L);
            return row;
        });
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("stores the stripped bytes under a chat key and queues a scan")
        void storesAndQueues() {
            service.upload(THREAD, USER, "shot.png", new byte[]{1, 2, 3});

            ArgumentCaptor<com.edunext.edutrack.api.feature.tickets.attachments.StorageKey> key =
                    ArgumentCaptor.forClass(com.edunext.edutrack.api.feature.tickets.attachments.StorageKey.class);
            verify(storage).put(key.capture(), any(), eq("image/png"));
            // Namespaced under chat/, never tickets/ — the two are disjoint by
            // construction, so a chat key can never address a ticket's object.
            assertThat(key.getValue().value()).startsWith("chat/" + THREAD + "/");
            verify(scans).submit(99L);
        }

        @Test
        @DisplayName("the row carries the sniffed type, never the client's word for it")
        void carriesTheSniffedType() {
            // The policy answered image/png for bytes whose name says .exe.
            // A client that renders an <img> off a file extension is exactly
            // what this stops, and the decision does not live on that side.
            var view = service.upload(THREAD, USER, "totally-not-a-virus.exe", new byte[]{1});

            assertThat(view).get().extracting("contentType", "isImage")
                    .containsExactly("image/png", true);
        }

        @Test
        @DisplayName("a new upload is PENDING and has no download URL")
        void pendingHasNoUrl() {
            // The enforcement is the absent URL, not a hidden row: hiding it
            // makes a slow scan indistinguishable from a failed upload.
            var view = service.upload(THREAD, USER, "shot.png", new byte[]{1});

            assertThat(view).get().extracting("scanStatus", "downloadUrl")
                    .containsExactly("PENDING", null);
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a thread the caller is not in answers empty — the controller makes that a 404")
        void strangerGetsNothing() {
            when(threads.threadForParticipant(THREAD, 999L)).thenReturn(Optional.empty());

            assertThat(service.upload(THREAD, 999L, "shot.png", new byte[]{1})).isEmpty();

            // Nothing stored, nothing inserted, nothing scanned: a stranger
            // must not be able to write to a bucket for a thread they cannot
            // read, nor learn it exists.
            verify(storage, never()).put(any(), any(), anyString());
            verify(attachments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a file over the ceiling is refused before anything is sniffed or stored")
        void tooLargeIsRefusedFirst() {
            assertThatThrownBy(() -> service.upload(THREAD, USER, "huge.png", new byte[2048]))
                    .isInstanceOf(ChatAttachmentTooLargeException.class)
                    .hasMessageContaining("0.0 MB");

            verify(types, never()).reconcile(anyString(), any());
            verify(storage, never()).put(any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("binding files to the message that carries them")
    class AttachTo {

        @Test
        @DisplayName("claims an unclaimed file on this thread")
        void claimsIt() {
            ChatAttachment row = row(null);
            when(attachments.findByIdAndThreadId(5L, THREAD)).thenReturn(Optional.of(row));

            service.attachTo(41L, THREAD, List.of(5L));

            assertThat(row.getMessageId()).isEqualTo(41L);
        }

        @Test
        @DisplayName("skips an id that belongs to another thread rather than refusing the send")
        void skipsAForeignId() {
            // Refusing would let a caller probe for which ids exist by watching
            // which sends fail — and it loses the message rather than one file
            // the sender can simply re-attach.
            when(attachments.findByIdAndThreadId(anyLong(), eq(THREAD))).thenReturn(Optional.empty());

            service.attachTo(41L, THREAD, List.of(5L));
            // No exception is the assertion.
        }

        @Test
        @DisplayName("will not steal a file another message already carries")
        void willNotRepoint() {
            // Re-pointing would silently remove a file from a message somebody
            // has already read, and §7.6 keeps chat as evidence.
            ChatAttachment row = row(30L);
            when(attachments.findByIdAndThreadId(5L, THREAD)).thenReturn(Optional.of(row));

            service.attachTo(41L, THREAD, List.of(5L));

            assertThat(row.getMessageId()).isEqualTo(30L);
        }

        @Test
        @DisplayName("an empty or null list does not go near the repository")
        void nothingToDo() {
            service.attachTo(41L, THREAD, List.of());
            service.attachTo(41L, THREAD, null);

            verify(attachments, never()).findByIdAndThreadId(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("reading files back")
    class ForMessages {

        @Test
        @DisplayName("a CLEAN row gets a signed URL")
        void cleanGetsAUrl() {
            ChatAttachment row = row(41L);
            row.setScanStatus("CLEAN");
            when(attachments.findByMessageIdInOrderByIdAsc(List.of(41L))).thenReturn(List.of(row));
            when(storage.signedDownloadUrl(any(), anyString(), anyString(), any()))
                    .thenReturn(URI.create("https://minio.example/signed"));

            var byMessage = service.forMessages(List.of(41L), Map.of());

            assertThat(byMessage.get(41L)).singleElement()
                    .extracting("downloadUrl").isEqualTo("https://minio.example/signed");
        }

        @Test
        @DisplayName("an INFECTED row is returned, and is not downloadable")
        void infectedIsReturnedButNotDownloadable() {
            // The bytes are already gone from storage. What survives is the
            // record that somebody shared something that did not pass — §7.6
            // keeps chat as evidence, so the row is not hidden.
            ChatAttachment row = row(41L);
            row.setScanStatus("INFECTED");
            when(attachments.findByMessageIdInOrderByIdAsc(List.of(41L))).thenReturn(List.of(row));

            var byMessage = service.forMessages(List.of(41L), Map.of());

            assertThat(byMessage.get(41L)).singleElement()
                    .extracting("scanStatus", "downloadUrl").containsExactly("INFECTED", null);
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a tombstoned row loses its URL even though it is CLEAN")
        void tombstonedLosesTheUrl() {
            ChatAttachment row = row(41L);
            row.setScanStatus("CLEAN");
            row.setDeletedAt(java.time.Instant.now());
            when(attachments.findByMessageIdInOrderByIdAsc(List.of(41L))).thenReturn(List.of(row));

            assertThat(service.forMessages(List.of(41L), Map.of()).get(41L))
                    .singleElement().extracting("downloadUrl").isNull();
        }

        @Test
        @DisplayName("an empty page asks nothing")
        void emptyPageAsksNothing() {
            assertThat(service.forMessages(List.of(), Map.of())).isEmpty();
            verify(attachments, never()).findByMessageIdInOrderByIdAsc(any());
        }
    }

    private static ChatAttachment row(Long messageId) {
        ChatAttachment row = new ChatAttachment();
        row.setId(5L);
        row.setThreadId(THREAD);
        row.setMessageId(messageId);
        row.setFileName("shot.png");
        row.setStorageKey("chat/" + THREAD + "/11111111-2222-3333-4444-555555555555");
        row.setMimeType("image/png");
        row.setSizeBytes(3);
        row.setScanStatus("PENDING");
        row.setUploadedBy(USER);
        return row;
    }
}
