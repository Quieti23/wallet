package io.quieti.wallet.application.scan;

public final class ChainContinuityException extends RuntimeException {

    public ChainContinuityException(long height) {
        super("Block does not extend the current checkpoint at height: " + height);
    }
}