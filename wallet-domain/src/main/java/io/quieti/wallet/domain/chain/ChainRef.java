package io.quieti.wallet.domain.chain;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChainRef(String namespace, String reference) {

    private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9][a-z0-9-]{2,31}$");
    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    public ChainRef {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(reference, "reference");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid chain namespace: " + namespace);
        }
        if (!REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("invalid chain reference: " + reference);
        }
    }

    public static ChainRef parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator < 0 || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("chain reference must use namespace:reference");
        }
        return new ChainRef(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + reference;
    }
}