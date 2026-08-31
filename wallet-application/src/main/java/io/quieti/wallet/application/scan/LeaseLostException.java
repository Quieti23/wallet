package io.quieti.wallet.application.scan;

public final class LeaseLostException extends RuntimeException {

    public LeaseLostException(String partitionId) {
        super("Lease lost for partition: " + partitionId);
    }
}