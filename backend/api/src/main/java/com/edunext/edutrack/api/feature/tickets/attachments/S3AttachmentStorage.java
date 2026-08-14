package com.edunext.edutrack.api.feature.tickets.attachments;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * C-025 · S3 and MinIO — blueprint §4B.4's "stored in S3/MinIO under
 * {@code tickets/{ticket_id}/{uuid}}, served only through short-lived signed
 * URLs, never a public bucket".
 *
 * <h2>"Never a public bucket" is enforced by omission, and that is deliberate</h2>
 *
 * <p>There is no ACL on the {@link PutObjectRequest} below. Not
 * {@code PRIVATE} — <b>none at all</b>, so the object takes the bucket's default,
 * and on any bucket created this decade that default is private with public
 * access blocked at the account level. Setting {@code ACL: private} explicitly
 * would read as more careful and would be worse: it puts the ACL vocabulary into
 * this file, and the distance from {@code ObjectCannedACL.PRIVATE} to
 * {@code ObjectCannedACL.PUBLIC_READ} is one word, in a line that already looks
 * like it was reasoned about. {@code AttachmentStorageSecurityTest} asserts the
 * whole feature package contains no reference to a canned ACL at all, so making
 * an attachment public is not a one-word change anywhere.
 *
 * <h2>What the signed URL says about the file it serves</h2>
 *
 * <p>Three response overrides, and each closes something:
 *
 * <ul>
 *   <li>{@code Content-Type} is the <em>sniffed</em> type. The object was stored
 *       with it too, but overriding on the presign means a row edited in the
 *       database cannot change what the browser is told to do with the bytes.</li>
 *   <li>{@code Content-Disposition: attachment} — the browser saves rather than
 *       renders. This is what makes a {@code .txt} full of markup harmless even
 *       if everything above it were wrong, and it is why an uploaded file can
 *       never be a stored XSS against the person who opens it.</li>
 *   <li>The user's filename rides in that same header, which is the reason the
 *       storage key does not have to carry it — see {@link AttachmentStorageKey}.</li>
 * </ul>
 */
class S3AttachmentStorage implements AttachmentStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3AttachmentStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void put(AttachmentStorageKey key, byte[] content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key.toString())
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public URI signedDownloadUrl(AttachmentStorageKey key, String fileName, String contentType, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.toString())
                .responseContentType(contentType)
                .responseContentDisposition(contentDisposition(fileName))
                .build();

        var presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(get)
                        .build());

        // Through the string form rather than URL#toURI: the presigned query
        // carries an already-encoded signature, and URL#toURI re-parses it under
        // stricter rules that reject characters S3 legitimately emits.
        return URI.create(presigned.url().toString());
    }

    @Override
    public void delete(AttachmentStorageKey key) {
        // S3 DELETE is idempotent and answers 204 for a key that was never
        // there, which is the behaviour both callers want: the AV path deletes
        // an object it may have failed to store, and C-028's delete window may
        // race a retry.
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key.toString()).build());
    }

    @Override
    public Optional<byte[]> read(AttachmentStorageKey key) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key.toString()).build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException gone) {
            return Optional.empty();
        }
        // Every other S3Exception — denied, throttled, bucket missing — is
        // deliberately NOT swallowed. Empty here means "no such object", and a
        // credentials failure that answered empty would be read by the scan path
        // as a vanished file and reported as such, sending whoever investigates
        // to the wrong system.
    }

    /**
     * RFC 6266, both forms.
     *
     * <p>{@code filename=} is a quoted ASCII string, so a name that is not ASCII
     * — which in this product is routine, not exotic — cannot be expressed in it
     * at all. The fallback is a transliteration-free ASCII skeleton, and the
     * real name goes in {@code filename*} as RFC 5987 percent-encoded UTF-8,
     * which every browser since IE11 prefers when both are present.
     *
     * <p>Quotes, backslashes and CR/LF are removed from the ASCII form. That is
     * not cosmetic: a filename is the one field in this feature a user writes
     * freely, and a newline in a response header is header injection. The
     * percent-encoding of the {@code filename*} form makes the same characters
     * inert there by construction.
     */
    private static String contentDisposition(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            name = "attachment";
        }

        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replaceAll("[\"\\\\]", "");
        if (ascii.isBlank()) {
            ascii = "attachment";
        }

        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
                // URLEncoder is form encoding, not RFC 5987: a space becomes '+'
                // there and must be %20 here, or the saved file gains plus signs.
                .replace("+", "%20");

        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
