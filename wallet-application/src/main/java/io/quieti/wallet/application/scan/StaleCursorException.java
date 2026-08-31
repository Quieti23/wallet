package io.quieti.wallet.application.scan;

public final class StaleCursorException extends RuntimeException {

    public StaleCursorException(String operation) {
        super(operation == null || operation.isEmpty()
                ? "stale cursor"
                : "stale cursor during " + operation);
    }
}