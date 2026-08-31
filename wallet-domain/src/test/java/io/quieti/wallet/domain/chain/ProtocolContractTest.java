package io.quieti.wallet.domain.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolContractTest {

    @Test
    void serializesGoCompatibleReferences() {
        ChainRef chain = ChainRef.parse("eip155:1");
        AssetRef asset = AssetRef.parse("eip155:1/erc20:0xabc");

        assertEquals("eip155:1", chain.toString());
        assertEquals(chain, asset.chain());
        assertEquals("eip155:1/erc20:0xabc", asset.toString());
    }

    @Test
    void preservesScopedMultipleParents() {
        ChainPoint point = new ChainPoint(
                "masterchain",
                "42",
                "root-hash",
                List.of(
                        new PointRef("masterchain", "41", "parent-root"),
                        new PointRef("0:8000000000000000", "17", "shard-root")),
                null);

        assertEquals(2, point.parents().size());
        assertEquals("masterchain", point.scope());
    }

    @Test
    void rejectsNonCanonicalUnsignedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new Amount("01", 8));
        assertThrows(IllegalArgumentException.class, () -> new Amount("-1", 8));
        assertEquals("0", new Amount("0", 9).toString());
    }
}