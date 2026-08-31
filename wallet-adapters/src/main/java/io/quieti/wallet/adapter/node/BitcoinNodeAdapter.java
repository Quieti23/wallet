package io.quieti.wallet.adapter.node;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.application.port.CanonicalReader;
import io.quieti.wallet.application.scan.ChainScanAdapter;
import io.quieti.wallet.application.scan.CursorCheck;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.ScanBatch;
import io.quieti.wallet.application.scan.ScanCursor;
import io.quieti.wallet.application.scan.WatchSnapshot;
import io.quieti.wallet.application.scan.WatchTarget;
import io.quieti.wallet.domain.chain.Amount;
import io.quieti.wallet.domain.chain.AssetRef;
import io.quieti.wallet.domain.chain.CanonicalUnit;
import io.quieti.wallet.domain.chain.ChainBlock;
import io.quieti.wallet.domain.chain.ChainCheckpoint;
import io.quieti.wallet.domain.chain.ChainPoint;
import io.quieti.wallet.domain.chain.ChainRef;
import io.quieti.wallet.domain.chain.PointRef;
import io.quieti.wallet.domain.deposit.DepositObservation;
import io.quieti.wallet.domain.deposit.ObservationId;
import io.quieti.wallet.domain.deposit.TransactionRef;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BitcoinNodeAdapter extends AbstractRpcNodeAdapter implements ChainScanAdapter {

    private final RpcTransport rpc;
    private final CanonicalReader history;
    private final ChainRef chainRef;
    private final AssetRef asset;
    private final long initialHeight;
    private final String initialPreviousHash;
    private final long requiredConfirmations;
    private final String nodeNetwork;

    public BitcoinNodeAdapter(String chain, ChainCheckpoint initialCheckpoint, RpcTransport rpc) {
        this(chain, initialCheckpoint, rpc, (ignoredChain, ignoredPosition) -> Optional.empty(), 1);
    }

    public BitcoinNodeAdapter(
            String chain,
            ChainCheckpoint initialCheckpoint,
            RpcTransport rpc,
            CanonicalReader history,
            long requiredConfirmations) {
        super(chain, initialCheckpoint);
        Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
        this.chainRef = switch (chain) {
            case "BTC_TESTNET" -> ChainRef.parse("bip122:testnet");
            case "BTC_SIGNET" -> ChainRef.parse("bip122:signet");
            default -> throw new IllegalArgumentException(
                    "Bitcoin scanner is restricted to Testnet or Signet");
        };
        this.nodeNetwork = chain.equals("BTC_SIGNET") ? "signet" : "test";
        this.asset = new AssetRef(chainRef, "native", "btc");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.history = Objects.requireNonNull(history, "history");
        this.initialHeight = Math.addExact(initialCheckpoint.height(), 1);
        this.initialPreviousHash = initialCheckpoint.blockHash();
        this.requiredConfirmations = requiredConfirmations == 0 ? 1 : requiredConfirmations;
        if (requiredConfirmations < 0) {
            throw new IllegalArgumentException("required confirmations must not be negative");
        }
    }

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        requireChain(chain);
        JsonNode hashResult = rpc.call("getblockhash", height);
        if (!hashResult.isTextual()) {
            throw new IllegalArgumentException("Bitcoin getblockhash result must be text");
        }
        String hash = hashResult.textValue();
        JsonNode block = rpc.call("getblock", hash, 1);
        if (block.path("height").asLong(-1) != height || !hash.equals(requiredText(block, "hash"))) {
            throw new IllegalArgumentException("Bitcoin block does not match requested height/hash");
        }
        return new ChainBlock(chain, height, hash, requiredText(block, "previousblockhash"));
    }

    @Override
    public ChainRef chain() {
        return chainRef;
    }

    @Override
    public ChainPoint observationHead(FinalityPolicy policy) {
        return policyHead(policy.observationTarget(chainRef));
    }

    @Override
    public ChainPoint finalityHead(FinalityPolicy policy) {
        return policyHead(policy.finalityTarget(chainRef));
    }

    @Override
    public CursorCheck verifyCursor(ScanCursor cursor) {
        requireCursorChain(cursor);
        if (cursor.point() == null) {
            return new CursorCheck(true, "");
        }
        long height = pointHeight(cursor.point());
        NodeInfo info = nodeInfo();
        if (height > info.blocks()) {
            return new CursorCheck(false, "cursor is above node tip");
        }
        String nodeHash = blockHash(height);
        boolean canonical = nodeHash.equals(cursor.point().hash());
        return new CursorCheck(canonical, canonical ? "" : "cursor hash differs from node");
    }

    @Override
    public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
        requireCursorChain(cursor);
        Objects.requireNonNull(watches, "watches");
        long headHeight = pointHeight(Objects.requireNonNull(head, "head"));
        long nextHeight = cursor.point() == null
                ? initialHeight
                : Math.addExact(pointHeight(cursor.point()), 1);
        String expectedParent = cursor.point() == null
                ? initialPreviousHash
                : cursor.point().hash();
        if (nextHeight > headHeight) {
            return new ScanBatch(List.of(), List.of(), null, false);
        }
        String hash = blockHash(nextHeight);
        JsonNode block = rpc.call("getblock", hash, 2);
        ChainPoint point = blockPoint(block, hash, nextHeight);
        if (nextHeight > 0 && !requiredText(block, "previousblockhash").equals(expectedParent)) {
            throw new IllegalStateException("bitcoin adapter: chain discontinuity");
        }
        List<DepositObservation> observations = observations(block, point, watches);
        return new ScanBatch(
                List.of(new CanonicalUnit(chainRef, point, true)),
                observations,
                new ScanCursor(chainRef, cursor.scannerId(), point, cursor.version() + 1),
                true);
    }

    @Override
    public ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth) {
        requireCursorChain(cursor);
        if (cursor.point() == null) {
            throw new IllegalStateException("bitcoin adapter: reorg exceeds retained window");
        }
        long start = Math.min(pointHeight(cursor.point()), nodeInfo().blocks());
        long lowest = Math.max(0, start - maxDepth);
        for (long height = start; ; height--) {
            String nodeHash = blockHash(height);
            Optional<ChainPoint> local = history.canonicalPoint(chainRef, Long.toString(height));
            if (local.isPresent() && local.orElseThrow().hash().equals(nodeHash)) {
                return local.orElseThrow();
            }
            if (height == lowest) {
                break;
            }
        }
        throw new IllegalStateException("bitcoin adapter: reorg exceeds retained window");
    }

    private ChainPoint policyHead(FinalityTarget target) {
        Objects.requireNonNull(target, "target");
        NodeInfo info = nodeInfo();
        long height;
        if (target.kind().equals("tag") && target.value().equals("latest")) {
            height = info.blocks();
        } else if (target.kind().equals("confirmations")) {
            long confirmations;
            try {
                confirmations = Long.parseLong(target.value());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("bitcoin adapter: invalid confirmations target", exception);
            }
            if (confirmations <= 0) {
                throw new IllegalArgumentException("bitcoin adapter: invalid confirmations target");
            }
            height = info.blocks() + 1 < confirmations
                    ? 0
                    : info.blocks() - (confirmations - 1);
        } else {
            throw new IllegalArgumentException("bitcoin adapter: unsupported finality target");
        }
        return pointAt(height);
    }

    private NodeInfo nodeInfo() {
        JsonNode value = rpc.call("getblockchaininfo");
        long blocks = requiredNonNegativeLong(value, "blocks");
        long headers = requiredNonNegativeLong(value, "headers");
        boolean initialBlockDownload = value.path("initialblockdownload").asBoolean(false);
        String chain = requiredText(value, "chain");
        boolean networkMatches = nodeNetwork.equals("signet")
                ? chain.equals("signet")
                : chain.equals("test") || chain.equals("testnet3");
        if (!networkMatches) {
            throw new IllegalStateException(
                    "bitcoin adapter: configured network does not match node chain " + chain);
        }
        if (initialBlockDownload || blocks > headers) {
            throw new IllegalStateException("bitcoin adapter: node is not synchronized");
        }
        return new NodeInfo(blocks, headers);
    }

    private ChainPoint pointAt(long height) {
        String hash = blockHash(height);
        return blockPoint(rpc.call("getblock", hash, 1), hash, height);
    }

    private String blockHash(long height) {
        JsonNode value = rpc.call("getblockhash", height);
        if (!value.isTextual() || !isHash(value.textValue())) {
            throw new IllegalArgumentException("bitcoin adapter: invalid block hash");
        }
        return value.textValue();
    }

    private ChainPoint blockPoint(JsonNode block, String expectedHash, long expectedHeight) {
        String hash = requiredText(block, "hash");
        long height = requiredNonNegativeLong(block, "height");
        if (!hash.equals(expectedHash) || !isHash(hash) || height != expectedHeight) {
            throw new IllegalArgumentException("bitcoin adapter: unexpected block identity");
        }
        long time = requiredNonNegativeLong(block, "time");
        List<PointRef> parents = List.of();
        if (height > 0) {
            String previous = requiredText(block, "previousblockhash");
            if (!isHash(previous)) {
                throw new IllegalArgumentException("bitcoin adapter: invalid previous block hash");
            }
            parents = List.of(new PointRef("", Long.toString(height - 1), previous));
        }
        return new ChainPoint("", Long.toString(height), hash, parents, Instant.ofEpochSecond(time));
    }

    private List<DepositObservation> observations(
            JsonNode block,
            ChainPoint point,
            WatchSnapshot watches) {
        Map<String, WatchTarget> watched = new HashMap<>();
        for (WatchTarget target : watches.targets()) {
            watched.put(target.normalizedTarget().toLowerCase(Locale.ROOT), target);
        }
        JsonNode transactions = block.get("tx");
        if (transactions == null || !transactions.isArray()) {
            throw new IllegalArgumentException("bitcoin adapter: block transactions are required");
        }
        List<DepositObservation> result = new ArrayList<>();
        for (JsonNode transaction : transactions) {
            String transactionId = requiredText(transaction, "txid");
            if (!isHash(transactionId)) {
                throw new IllegalArgumentException("bitcoin adapter: invalid transaction hash");
            }
            JsonNode outputs = transaction.get("vout");
            if (outputs == null || !outputs.isArray()) {
                throw new IllegalArgumentException("bitcoin adapter: transaction outputs are required");
            }
            for (int index = 0; index < outputs.size(); index++) {
                JsonNode output = outputs.get(index);
                String script = requiredText(output.path("scriptPubKey"), "hex")
                        .toLowerCase(Locale.ROOT);
                validateHex(script, "scriptPubKey");
                WatchTarget target = watched.get(script);
                if (target == null) {
                    continue;
                }
                BigInteger satoshis = satoshis(output.get("value"));
                result.add(new DepositObservation(
                        new ObservationId(
                                chainRef,
                                new TransactionRef("hash", transactionId),
                                "vout:" + index),
                        asset,
                        "",
                        script,
                        new Amount(satoshis.toString(), 8),
                        point,
                        Map.of(
                                "owner_id", target.ownerId(),
                                "account_id", target.accountId(),
                                "required_confirmations", Long.toString(requiredConfirmations))));
            }
        }
        return List.copyOf(result);
    }

    private static BigInteger satoshis(JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("bitcoin adapter: output value is required");
        }
        try {
            BigInteger satoshis = new BigDecimal(value.asText()).movePointRight(8).toBigIntegerExact();
            if (satoshis.signum() < 0) {
                throw new IllegalArgumentException("bitcoin adapter: negative output value");
            }
            return satoshis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("bitcoin adapter: output has fractional satoshis", exception);
        }
    }

    private void requireCursorChain(ScanCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        if (!cursor.chain().equals(chainRef)) {
            throw new IllegalArgumentException("bitcoin adapter: cursor chain differs");
        }
    }

    private static long pointHeight(ChainPoint point) {
        try {
            long height = Long.parseLong(point.position());
            if (height < 0) {
                throw new NumberFormatException("negative");
            }
            return height;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("bitcoin adapter: invalid point position", exception);
        }
    }

    private static long requiredNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("bitcoin adapter: invalid " + field);
        }
        return value.longValue();
    }

    private static boolean isHash(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void validateHex(String value, String name) {
        try {
            HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("bitcoin adapter: invalid " + name, exception);
        }
    }

    private record NodeInfo(long blocks, long headers) {
    }
}