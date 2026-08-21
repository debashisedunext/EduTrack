package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentTypePolicy;
import com.edunext.edutrack.api.feature.tickets.attachments.ImageMetadataStripper;
import com.edunext.edutrack.domain.chat.ChatAttachment;
import com.edunext.edutrack.domain.chat.ChatAttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-053 · file and image share in chat — blueprint §7.6.
 *
 * <h2>One answer to "is this file safe", called from a second place</h2>
 *
 * <p>The half of D-053 that stayed blocked for months was recorded as needing
 * a table shape decision, and under it a sharper worry: *"C-024/C-025 own
 * upload, MinIO keys, MIME sniffing and AV scan, and a second pipeline here
 * would put two answers next to each other for 'is this file safe'"*.
 *
 * <p>That worry is answered rather than overruled. {@link AttachmentTypePolicy}
 * (sniff the bytes, reconcile against the declared name, refuse what is not on
 * the allow-list), {@link ImageMetadataStripper} (EXIF, which carries GPS) and
 * the AV scanner behind {@link ChatAttachmentScanTask} are the <em>same beans</em>
 * C-025 uses. They were already separate and injectable; the only thing that
 * was ticket-shaped was the storage key, and {@code StorageKey} now covers both
 * namespaces. Nothing about the safety decision is duplicated, overridden or
 * relaxed here.
 *
 * <h2>Upload and send are two requests, deliberately</h2>
 *
 * <p>The file is sniffed, stripped, stored and queued for scanning while the
 * author is still typing. The send is then instant, and a file that is going to
 * be refused is refused before they have written anything — rather than after,
 * with their message lost. The cost is that a row can exist with no message:
 * a file nobody sent. {@code ix_chat_attachments_orphans} is what a sweeper
 * would claim on, and that sweeper is not built here.
 *
 * <h2>Scope is thread participation, and a stranger gets 404</h2>
 *
 * <p>{@code ChatRepository.threadForParticipant} is the same check every other
 * chat read makes, and it returns empty both for a thread that does not exist
 * and for one this caller is not in — so uploading into a stranger's thread is
 * indistinguishable from uploading into a thread that was never created. That
 * is the same 404-not-403 rule row scoping follows, one surface over.
 */
@Service
class ChatAttachmentService {

    private final ChatRepository threads;
    private final ChatAttachmentRepository attachments;
    private final AttachmentTypePolicy types;
    private final ImageMetadataStripper stripper;
    private final AttachmentStorage storage;
    private final ChatAttachmentScanTask scans;
    private final AttachmentProperties properties;

    ChatAttachmentService(ChatRepository threads,
                          ChatAttachmentRepository attachments,
                          AttachmentTypePolicy types,
                          ImageMetadataStripper stripper,
                          AttachmentStorage storage,
                          ChatAttachmentScanTask scans,
                          AttachmentProperties properties) {
        this.threads = threads;
        this.attachments = attachments;
        this.types = types;
        this.stripper = stripper;
        this.storage = storage;
        this.scans = scans;
        this.properties = properties;
    }

    /**
     * Store one file against a thread.
     *
     * @return empty when the caller is not a participant — the controller turns
     *         that into 404, never 403
     */
    @Transactional
    Optional<ChatAttachmentDtos.ChatAttachmentView> upload(long threadId, long userId,
                                                           String fileName, byte[] content) {
        if (threads.threadForParticipant(threadId, userId).isEmpty()) {
            return Optional.empty();
        }
        if (content.length > properties.maxFileBytes()) {
            throw new ChatAttachmentTooLargeException(content.length, properties.maxFileBytes());
        }

        // Sniffed and reconciled against the declared name — C-025's policy
        // bean, unchanged. A renamed executable is refused here, not rendered
        // as an image by whichever client trusts the extension.
        AttachmentTypePolicy.Accepted accepted = types.reconcile(fileName, content);
        byte[] cleaned = stripper.strip(accepted.type(), content);

        ChatAttachmentStorageKey key = ChatAttachmentStorageKey.mint(threadId);
        storage.put(key, cleaned, accepted.mediaType());

        ChatAttachment row = new ChatAttachment();
        row.setThreadId(threadId);
        row.setFileName(fileName);
        row.setStorageKey(key.toString());
        row.setMimeType(accepted.mediaType());
        row.setSizeBytes(cleaned.length);
        row.setScanStatus(ChatAttachmentScanTask.PENDING);
        row.setUploadedBy(userId);

        ChatAttachment saved = attachments.saveAndFlush(row);
        scans.submit(saved.getId());
        return Optional.of(view(saved, null));
    }

    /**
     * Bind uploaded files to the message that carries them.
     *
     * <p>Called from {@code ChatService.post} with the request's
     * {@code attachmentIds} — the field that has been on the contract and on the
     * DTO since D-001 and stored nothing until now.
     *
     * <p><b>Each id is checked against this thread, and one that does not belong
     * is skipped rather than refused.</b> Refusing would let a caller probe for
     * which attachment ids exist by watching which sends fail, and there is
     * nothing a user can do about an id their own client sent wrong. Skipping
     * loses a file the sender can re-attach; refusing loses the message.
     *
     * <p>An id that has already been claimed by another message is skipped for
     * the same reason and a second one: re-pointing it would silently remove a
     * file from a message somebody has already read, and §7.6 keeps chat as
     * evidence.
     */
    @Transactional
    void attachTo(long messageId, long threadId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (Long id : attachmentIds) {
            if (id == null) {
                continue;
            }
            attachments.findByIdAndThreadId(id, threadId)
                    .filter(row -> row.getMessageId() == null)
                    .ifPresent(row -> row.setMessageId(messageId));
        }
    }

    /**
     * Everything carried by a page of messages, keyed by message id.
     *
     * <p>One query for the page rather than one per message — a thread renders
     * fifty lines at a time and the N+1 would be a query per line.
     */
    @Transactional(readOnly = true)
    Map<Long, List<ChatAttachmentDtos.ChatAttachmentView>> forMessages(List<Long> messageIds,
                                                                       Map<Long, ChatDtos.UserRef> people) {
        Map<Long, List<ChatAttachmentDtos.ChatAttachmentView>> byMessage = new LinkedHashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return byMessage;
        }
        for (ChatAttachment row : attachments.findByMessageIdInOrderByIdAsc(messageIds)) {
            byMessage.computeIfAbsent(row.getMessageId(), key -> new java.util.ArrayList<>())
                    .add(view(row, people == null ? null : people.get(row.getUploadedBy())));
        }
        return byMessage;
    }

    /**
     * The row as the contract's {@code ChatAttachment}.
     *
     * <p><b>The download URL is minted only for a CLEAN, untombstoned row.</b>
     * That is the whole enforcement — C-025's own design, and its reason: the
     * requirement is that an unscanned or infected file not become
     * <em>readable</em>, and a signed URL that is never issued is stronger than
     * a row that is merely hidden. The URL is short-lived
     * ({@code edutrack.attachments.signed-url-ttl}, five minutes by default) so
     * it cannot usefully be pasted into a channel somebody else reads.
     */
    private ChatAttachmentDtos.ChatAttachmentView view(ChatAttachment row, ChatDtos.UserRef uploader) {
        boolean readable = ChatAttachmentScanTask.CLEAN.equals(row.getScanStatus())
                && row.getDeletedAt() == null;
        URI url = readable
                ? storage.signedDownloadUrl(
                        ChatAttachmentStorageKey.parse(row.getStorageKey()),
                        row.getFileName(),
                        row.getMimeType(),
                        properties.signedUrlTtl())
                : null;

        return new ChatAttachmentDtos.ChatAttachmentView(
                row.getId(),
                row.getFileName(),
                row.getMimeType(),
                row.getSizeBytes(),
                row.getScanStatus(),
                // From the sniffed type, never the file name — the client
                // renders an <img> off this.
                row.getMimeType() != null && row.getMimeType().startsWith("image/"),
                url == null ? null : url.toString(),
                uploader,
                row.getCreatedAt());
    }
}
