package com.edunext.edutrack.domain.notifications.push;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.math.ec.ECPoint;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * D-045 · RFC 8291 message encryption, in RFC 8188's {@code aes128gcm} coding.
 *
 * <p><strong>Why this is written here rather than taken from a library.</strong>
 * BouncyCastle and Nimbus are already dependencies, and the maintained Java
 * web-push libraries are not: the most-used one last published in 2021. Adding
 * a stale dependency to avoid composing primitives we already ship is the worse
 * trade — but only because <em>this is checked against the RFC's own worked
 * example</em>. Composition of standard primitives, verified against published
 * vectors, is defensible. The same code without that test would not be.
 *
 * <p>Nothing novel happens here. ECDH on P-256, HKDF-SHA256 twice, one
 * AES-128-GCM record. The risk is not the cryptography, it is the
 * exact-bytes detail — one wrong info string and the push simply never
 * arrives, which is indistinguishable from a browser that went away.
 */
public class PushEncryption {

    /** RFC 8291 §3.1, byte for byte, including the trailing NUL. */
    private static final byte[] IKM_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    /** RFC 8188 §2 — the record padding delimiter for the final record. */
    private static final byte LAST_RECORD = 0x02;

    private static final int SALT_BYTES = 16;
    private static final int CEK_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PUBLIC_KEY_BYTES = 65;

    /**
     * One record, so the whole payload rides in a single AES-GCM operation.
     *
     * <p>4096 matches the RFC's example and is far above anything a
     * notification body reaches — the title and body of a ticket alert are a
     * few hundred bytes. A payload that ever exceeded it would need real
     * multi-record framing, so {@link #encrypt} refuses rather than silently
     * truncating: a notification cut in half is worse than one that failed
     * loudly on the server.
     */
    static final int RECORD_SIZE = 4096;

    private static final X9ECParameters P256 = CustomNamedCurves.getByName("secp256r1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            P256.getCurve(), P256.getG(), P256.getN(), P256.getH());

    private final SecureRandom random = new SecureRandom();

    /**
     * @param uaPublic    the browser's {@code p256dh}, 65 uncompressed bytes
     * @param authSecret  the browser's {@code auth}, 16 bytes
     * @return the complete request body: header, then one encrypted record
     */
    public byte[] encrypt(String plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);

        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(DOMAIN, random));
        var pair = generator.generateKeyPair();
        BigInteger asPrivate = ((ECPrivateKeyParameters) pair.getPrivate()).getD();
        byte[] asPublic = ((ECPublicKeyParameters) pair.getPublic()).getQ().getEncoded(false);

        return encrypt(plaintext, uaPublic, authSecret, salt, asPrivate, asPublic);
    }

    /**
     * The deterministic form, so the RFC 8291 §5 example can be reproduced
     * exactly. Package-private and used only by the test — an ephemeral key
     * that a caller could pin is a footgun, since reusing one across messages
     * to the same browser destroys the forward secrecy the ECDH is there for.
     */
    byte[] encrypt(String plaintext, byte[] uaPublic, byte[] authSecret,
                   byte[] salt, BigInteger asPrivate, byte[] asPublic) {

        byte[] payload = plaintext.getBytes(StandardCharsets.UTF_8);
        // Header (86) + payload + delimiter + tag (16) must fit one record.
        if (payload.length + 1 + 16 > RECORD_SIZE) {
            throw new IllegalArgumentException(
                    "push payload of " + payload.length + " bytes exceeds one record");
        }

        byte[] sharedSecret = agree(uaPublic, asPrivate);

        // RFC 8291 §3.3: the auth secret is the HKDF *salt* here, and the info
        // binds the derivation to both public keys — which is what stops a
        // message encrypted for one browser being replayable at another.
        byte[] ikm = hkdf(authSecret, sharedSecret, concat(IKM_INFO_PREFIX, uaPublic, asPublic), 32);

        byte[] cek = hkdf(salt, ikm, CEK_INFO, CEK_BYTES);
        byte[] nonce = hkdf(salt, ikm, NONCE_INFO, NONCE_BYTES);

        byte[] record = new byte[payload.length + 1];
        System.arraycopy(payload, 0, record, 0, payload.length);
        record[payload.length] = LAST_RECORD;

        byte[] ciphertext = seal(cek, nonce, record);

        // RFC 8188 §2.1: salt(16) || record size(4, big endian) || idlen(1) || keyid
        ByteBuffer body = ByteBuffer.allocate(
                SALT_BYTES + 4 + 1 + asPublic.length + ciphertext.length);
        body.put(salt);
        body.putInt(RECORD_SIZE);
        body.put((byte) asPublic.length);
        body.put(asPublic);
        body.put(ciphertext);
        return body.array();
    }

    private static byte[] agree(byte[] uaPublic, BigInteger asPrivate) {
        if (uaPublic.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("p256dh must be 65 uncompressed bytes");
        }
        // decodePoint validates that the point is on the curve. An attacker who
        // could register an off-curve public key would otherwise learn bits of
        // our ephemeral private key from the resulting shared secret — the
        // invalid-curve attack, and the reason this is not a raw multiply.
        ECPoint point = P256.getCurve().decodePoint(uaPublic);
        ECDHBasicAgreement agreement = new ECDHBasicAgreement();
        agreement.init(new ECPrivateKeyParameters(asPrivate, DOMAIN));
        BigInteger z = agreement.calculateAgreement(new ECPublicKeyParameters(point, DOMAIN));
        // Left-padded to the field size: BigInteger drops leading zero bytes,
        // and a 31-byte secret would derive a different key on one message in
        // 256 — an intermittent failure nobody would ever reproduce.
        return leftPad(z.toByteArray(), 32);
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        hkdf.generateBytes(out, 0, length);
        return out;
    }

    private static byte[] seal(byte[] cek, byte[] nonce, byte[] record) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(record);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("push: AES-GCM unavailable or misconfigured", e);
        }
    }

    private static byte[] leftPad(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        byte[] padded = new byte[length];
        if (value.length > length) {
            // BigInteger.toByteArray() prepends a sign byte when the high bit
            // is set; drop it rather than the significant bytes.
            System.arraycopy(value, value.length - length, padded, 0, length);
        } else {
            System.arraycopy(value, 0, padded, length - value.length, value.length);
        }
        return padded;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
