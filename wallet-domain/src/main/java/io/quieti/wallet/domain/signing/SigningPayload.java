package io.quieti.wallet.domain.signing;

import java.util.Arrays;
import java.util.Objects;

public final class SigningPayload {

    private final byte[] bytes;

    public SigningPayload(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }
}