package io.quieti.wallet.domain.signing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class SigningPayloadTest {

    @Test
    void protectsPayloadWithDefensiveCopies() {
        byte[] source = {1, 2, 3};
        SigningPayload payload = new SigningPayload(source);
        source[0] = 9;

        byte[] exposed = payload.bytes();
        exposed[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, payload.bytes());
    }
}