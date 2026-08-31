package io.quieti.wallet.domain.chain;

import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;

public record Amount(String raw, int decimals) {

    private static final Pattern RAW = Pattern.compile("^(0|[1-9][0-9]*)$");

    public Amount {
        Objects.requireNonNull(raw, "raw");
        if (!RAW.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "amount raw value must be a canonical unsigned base-10 integer");
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("amount decimals must not be negative");
        }
    }

    public BigInteger toBigInteger() {
        return new BigInteger(raw);
    }

    @Override
    public String toString() {
        return raw;
    }
}