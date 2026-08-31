package io.quieti.wallet.domain.chain;

import java.util.Objects;
import java.util.regex.Pattern;

public record AssetRef(ChainRef chain, String standard, String locator) {

    private static final Pattern STANDARD = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,31}$");
    private static final Pattern LOCATOR = Pattern.compile("^[^\\s/:]+$");

    public AssetRef {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(standard, "standard");
        Objects.requireNonNull(locator, "locator");
        if (!STANDARD.matcher(standard).matches()) {
            throw new IllegalArgumentException("invalid asset standard: " + standard);
        }
        if (!LOCATOR.matcher(locator).matches()) {
            throw new IllegalArgumentException("invalid asset locator: " + locator);
        }
    }

    public static AssetRef parse(String value) {
        Objects.requireNonNull(value, "value");
        int slash = value.indexOf('/');
        int colon = slash < 0 ? -1 : value.indexOf(':', slash + 1);
        if (slash <= 0 || colon <= slash + 1 || colon != value.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "asset reference must use namespace:reference/standard:locator");
        }
        return new AssetRef(
                ChainRef.parse(value.substring(0, slash)),
                value.substring(slash + 1, colon),
                value.substring(colon + 1));
    }

    @Override
    public String toString() {
        return chain + "/" + standard + ":" + locator;
    }
}