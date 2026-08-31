package io.quieti.wallet.domain.amount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ChainAmountTest {

    @Test
    void convertsDisplayAmountWithoutRounding() {
        ChainAmount amount = ChainAmount.fromDisplay("ETH", new BigDecimal("1.230000"), 18);

        assertEquals(new BigInteger("1230000000000000000"), amount.atomicUnits());
        assertEquals(0, new BigDecimal("1.23").compareTo(amount.toDisplay(18)));
    }

    @Test
    void rejectsFractionSmallerThanAtomicUnit() {
        assertThrows(ArithmeticException.class,
                () -> ChainAmount.fromDisplay("BTC", new BigDecimal("0.000000001"), 8));
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChainAmount("BTC", BigInteger.valueOf(-1)));
    }
}