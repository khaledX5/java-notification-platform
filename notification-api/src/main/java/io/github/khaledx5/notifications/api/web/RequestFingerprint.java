package io.github.khaledx5.notifications.api.web;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Short, stable summary of the content of a request: equal content gives an equal fingerprint. It is
 * stored next to an idempotency key to notice the key being sent again with different content,
 * without storing the content itself.
 */
final class RequestFingerprint {

    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    private RequestFingerprint() {}

    /**
     * SHA-256 over the fields, as lowercase hex.
     *
     * <p>Every field is written with its length in front, so {@code ["ab", "c"]} and {@code ["a",
     * "bc"]} do not collide, and a missing field differs from an empty one.
     *
     * @param fields in a fixed order; elements may be null
     */
    static String of(List<String> fields) {
        MessageDigest digest = sha256();
        for (String field : fields) {
            if (field == null) {
                digest.update(ABSENT);
                continue;
            }
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(PRESENT);
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has to provide SHA-256", ex);
        }
    }
}
