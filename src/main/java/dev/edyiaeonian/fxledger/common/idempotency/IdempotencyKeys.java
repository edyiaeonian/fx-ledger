package dev.edyiaeonian.fxledger.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;

/** What deposits and transfers share about idempotency keys. */
public final class IdempotencyKeys {

    // Printable ASCII, 1 to 255 characters: room for a UUID or any client's
    // own scheme, nothing that could smuggle control characters into logs.
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private IdempotencyKeys() {}

    /** The Idempotency-Key header, checked; a request that must be idempotent cannot omit it. */
    public static String require(String header) {
        if (header == null || !KEY.matcher(header).matches()) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "an Idempotency-Key header of 1 to 255 printable ASCII characters is required");
        }
        return header;
    }

    /**
     * SHA-256 of the parts that make two requests "the same", joined with a
     * separator no part contains. Stored with the key, so that a retry can be
     * told apart from a different request reusing it.
     */
    public static String requestHash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }
}
