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
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class EvmNodeAdapter extends AbstractRpcNodeAdapter implements ChainScanAdapter {

    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private final RpcTransport rpc;
    private final CanonicalReader history;
    private final ChainRef chainRef;
    private final long initialHeight;
    private final long fallbackConfirmations;
    private final boolean includeInternal;
    private final Set<String> tokenContracts;

    public EvmNodeAdapter(ChainCheckpoint initialCheckpoint, RpcTransport rpc) {
        this(1, initialCheckpoint, rpc, (chain, position) -> Optional.empty(), 0, false, null);
    }

    public EvmNodeAdapter(
            long chainId,
            ChainCheckpoint initialCheckpoint,
            RpcTransport rpc,
            CanonicalReader history,
            long fallbackConfirmations,
            boolean includeInternal,
            Set<String> tokenContracts) {
        super("EVM", initialCheckpoint);
        if (chainId <= 0 || fallbackConfirmations < 0) {
            throw new IllegalArgumentException("EVM chain ID must be positive and confirmations non-negative");
        }
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.history = Objects.requireNonNull(history, "history");
        this.chainRef = ChainRef.parse("eip155:" + chainId);
        this.initialHeight = Math.addExact(initialCheckpoint.height(), 1);
        this.fallbackConfirmations = fallbackConfirmations;
        this.includeInternal = includeInternal;
        this.tokenContracts = tokenContracts == null
                ? null
                : tokenContracts.stream().map(EvmNodeAdapter::address).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ChainBlock fetchBlock(String chain, long height) {
        requireChain(chain);
        JsonNode block = blockByNumber(Long.toString(height), false);
        ChainPoint point = blockPoint(block, height);
        return new ChainBlock(chain, height, point.hash(), point.parents().getFirst().hash());
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
        String nodeHash = blockPoint(blockByNumber(Long.toString(height), false), height).hash();
        boolean canonical = nodeHash.equalsIgnoreCase(cursor.point().hash());
        return new CursorCheck(canonical, canonical ? "" : "cursor hash differs from node");
    }

    @Override
    public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
        requireCursorChain(cursor);
        Objects.requireNonNull(watches, "watches");
        long nextHeight = cursor.point() == null
                ? initialHeight
                : Math.addExact(pointHeight(cursor.point()), 1);
        if (nextHeight > pointHeight(Objects.requireNonNull(head, "head"))) {
            return new ScanBatch(List.of(), List.of(), null, false);
        }
        JsonNode block = blockByNumber(Long.toString(nextHeight), true);
        ChainPoint point = blockPoint(block, nextHeight);
        if (cursor.point() != null
                && !requiredHash(block, "parentHash").equalsIgnoreCase(cursor.point().hash())) {
            throw new IllegalStateException("evm adapter: chain discontinuity");
        }
        Map<String, WatchTarget> watched = watchMap(watches);
        List<DepositObservation> observations = new ArrayList<>();
        observations.addAll(transactionObservations(block, point, watched));
        observations.addAll(logObservations(point, watched));
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
            throw new IllegalStateException("evm adapter: reorg exceeds retained window");
        }
        long latest = pointHeight(blockPoint(blockByNumber("latest", false), null));
        long start = Math.min(pointHeight(cursor.point()), latest);
        long lowest = Math.max(0, start - maxDepth);
        for (long height = start; ; height--) {
            ChainPoint node = blockPoint(blockByNumber(Long.toString(height), false), height);
            Optional<ChainPoint> local = history.canonicalPoint(chainRef, Long.toString(height));
            if (local.isPresent() && local.orElseThrow().hash().equalsIgnoreCase(node.hash())) {
                return local.orElseThrow();
            }
            if (height == lowest) {
                break;
            }
        }
        throw new IllegalStateException("evm adapter: reorg exceeds retained window");
    }

    private ChainPoint policyHead(FinalityTarget target) {
        Objects.requireNonNull(target, "target");
        return switch (target.kind()) {
            case "tag" -> resolveTag(target.value(), fallbackConfirmations);
            case "confirmations" -> {
                long confirmations = parseNonNegative(target.value(), "confirmations");
                yield resolveTag("safe", confirmations);
            }
            default -> throw new IllegalArgumentException("evm adapter: unsupported finality target");
        };
    }

    private ChainPoint resolveTag(String tag, long fallback) {
        if (!tag.equals("latest") && !tag.equals("safe") && !tag.equals("finalized")) {
            throw new IllegalArgumentException("evm adapter: unsupported head tag " + tag);
        }
        try {
            return blockPoint(blockByNumber(tag, false), null);
        } catch (RuntimeException exception) {
            if (tag.equals("latest") || fallback == 0) {
                throw exception;
            }
            ChainPoint latest = blockPoint(blockByNumber("latest", false), null);
            long latestHeight = pointHeight(latest);
            long fallbackHeight = latestHeight < fallback ? 0 : latestHeight - fallback;
            return blockPoint(blockByNumber(Long.toString(fallbackHeight), false), fallbackHeight);
        }
    }

    private List<DepositObservation> transactionObservations(
            JsonNode block,
            ChainPoint point,
            Map<String, WatchTarget> watched) {
        JsonNode transactions = block.get("transactions");
        if (transactions == null || !transactions.isArray()) {
            throw new IllegalArgumentException("evm adapter: block transactions are required");
        }
        List<DepositObservation> result = new ArrayList<>();
        for (JsonNode transaction : transactions) {
            String to = optionalAddress(transaction.get("to"));
            BigInteger value = quantity(transaction.get("value"), "transaction value");
            WatchTarget target = watched.get(to);
            boolean topLevel = target != null && value.signum() > 0;
            if (!topLevel && !includeInternal) {
                continue;
            }
            String transactionHash = requiredHash(transaction, "hash");
            JsonNode receipt = rpc.call("eth_getTransactionReceipt", transactionHash);
            if (!quantity(receipt.get("status"), "receipt status").equals(BigInteger.ONE)
                    || !requiredHash(receipt, "blockHash").equalsIgnoreCase(point.hash())) {
                continue;
            }
            if (topLevel) {
                result.add(observation(
                        transactionHash,
                        "tx",
                        requiredAddress(transaction, "from"),
                        to,
                        new AssetRef(chainRef, "native", "native"),
                        value,
                        18,
                        target,
                        point));
            }
            if (includeInternal) {
                result.addAll(internalObservations(transactionHash, watched, point));
            }
        }
        return result;
    }

    private List<DepositObservation> internalObservations(
            String transactionHash,
            Map<String, WatchTarget> watched,
            ChainPoint point) {
        JsonNode root;
        try {
            root = rpc.call("debug_traceTransaction", transactionHash, Map.of(
                    "tracer", "callTracer",
                    "tracerConfig", Map.of("onlyTopCall", false)));
        } catch (RuntimeException exception) {
            if (degradableTraceFailure(exception)) {
                return List.of();
            }
            throw exception;
        }
        List<DepositObservation> result = new ArrayList<>();
        walkTrace(root, "0", true, false, transactionHash, watched, point, result);
        return result;
    }

    private void walkTrace(
            JsonNode frame,
            String path,
            boolean root,
            boolean parentFailed,
            String transactionHash,
            Map<String, WatchTarget> watched,
            ChainPoint point,
            List<DepositObservation> result) {
        boolean failed = parentFailed
                || frame.hasNonNull("error") && !frame.path("error").asText().isEmpty()
                || frame.hasNonNull("revertReason") && !frame.path("revertReason").asText().isEmpty();
        String type = frame.path("type").asText().toUpperCase(Locale.ROOT);
        if (!root && (type.equals("CALL") || type.equals("SELFDESTRUCT"))) {
            String to = optionalAddress(frame.get("to"));
            WatchTarget target = watched.get(to);
            BigInteger value = frame.hasNonNull("value")
                    ? quantity(frame.get("value"), "trace value")
                    : BigInteger.ZERO;
            if (!failed && target != null && value.signum() > 0) {
                result.add(observation(
                        transactionHash,
                        "trace:" + path,
                        requiredAddress(frame, "from"),
                        to,
                        new AssetRef(chainRef, "native", "native"),
                        value,
                        18,
                        target,
                        point));
            }
        }
        JsonNode calls = frame.get("calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (int index = 0; index < calls.size(); index++) {
            walkTrace(
                    calls.get(index),
                    path + "." + index,
                    false,
                    failed,
                    transactionHash,
                    watched,
                    point,
                    result);
        }
    }

    private List<DepositObservation> logObservations(
            ChainPoint point,
            Map<String, WatchTarget> watched) {
        JsonNode logs = rpc.call("eth_getLogs", Map.of(
                "blockHash", point.hash(),
                "topics", List.of(List.of(TRANSFER_TOPIC))));
        if (!logs.isArray()) {
            throw new IllegalArgumentException("evm adapter: logs result must be an array");
        }
        List<DepositObservation> result = new ArrayList<>();
        for (JsonNode event : logs) {
            JsonNode topics = event.get("topics");
            if (event.path("removed").asBoolean(false)
                    || topics == null
                    || !topics.isArray()
                    || topics.size() != 3
                    || !TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asText())) {
                continue;
            }
            String token = requiredAddress(event, "address");
            if (tokenContracts != null && !tokenContracts.contains(token)) {
                continue;
            }
            String to = topicAddress(topics.get(2).asText());
            WatchTarget target = watched.get(to);
            BigInteger value = quantity(event.get("data"), "log value");
            if (target == null || value.signum() <= 0) {
                continue;
            }
            long index = quantity(event.get("logIndex"), "log index").longValueExact();
            result.add(observation(
                    requiredHash(event, "transactionHash"),
                    "log:" + index,
                    topicAddress(topics.get(1).asText()),
                    to,
                    new AssetRef(chainRef, "erc20", token),
                    value,
                    0,
                    target,
                    point));
        }
        return result;
    }

    private DepositObservation observation(
            String transactionHash,
            String source,
            String from,
            String to,
            AssetRef asset,
            BigInteger value,
            int decimals,
            WatchTarget target,
            ChainPoint point) {
        return new DepositObservation(
                new ObservationId(chainRef, new TransactionRef("hash", transactionHash), source),
                asset,
                from,
                to,
                new Amount(value.toString(), decimals),
                point,
                Map.of("owner_id", target.ownerId(), "account_id", target.accountId()));
    }

    private JsonNode blockByNumber(String numberOrTag, boolean transactions) {
        String value = numberOrTag.chars().allMatch(Character::isDigit)
                ? "0x" + Long.toHexString(Long.parseLong(numberOrTag))
                : numberOrTag;
        JsonNode block = rpc.call("eth_getBlockByNumber", value, transactions);
        if (block == null || block.isNull() || !block.isObject()) {
            throw new IllegalArgumentException("evm adapter: block is unavailable");
        }
        return block;
    }

    private static ChainPoint blockPoint(JsonNode block, Long expectedHeight) {
        long height = quantity(block.get("number"), "block number").longValueExact();
        if (height < 0 || expectedHeight != null && height != expectedHeight) {
            throw new IllegalArgumentException("evm adapter: unexpected block number");
        }
        String hash = requiredHash(block, "hash");
        long timestamp = quantity(block.get("timestamp"), "block timestamp").longValueExact();
        List<PointRef> parents = height == 0
                ? List.of()
                : List.of(new PointRef("", Long.toString(height - 1), requiredHash(block, "parentHash")));
        return new ChainPoint("", Long.toString(height), hash, parents, Instant.ofEpochSecond(timestamp));
    }

    private static Map<String, WatchTarget> watchMap(WatchSnapshot watches) {
        Map<String, WatchTarget> result = new HashMap<>();
        for (WatchTarget target : watches.targets()) {
            result.put(address(target.normalizedTarget()), target);
        }
        return result;
    }

    private static long pointHeight(ChainPoint point) {
        return parseNonNegative(point.position(), "point position");
    }

    private void requireCursorChain(ScanCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        if (!cursor.chain().equals(chainRef)) {
            throw new IllegalArgumentException("evm adapter: cursor chain differs");
        }
    }

    private static BigInteger quantity(JsonNode value, String name) {
        if (value == null || !value.isTextual() || !value.textValue().startsWith("0x")) {
            throw new IllegalArgumentException("evm adapter: invalid " + name);
        }
        try {
            String digits = value.textValue().substring(2);
            return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits, 16);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("evm adapter: invalid " + name, exception);
        }
    }

    private static long parseNonNegative(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("evm adapter: invalid " + name, exception);
        }
    }

    private static String requiredHash(JsonNode value, String field) {
        String hash = requiredText(value, field).toLowerCase(Locale.ROOT);
        if (!hash.matches("0x[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evm adapter: invalid " + field);
        }
        return hash;
    }

    private static String requiredAddress(JsonNode value, String field) {
        return address(requiredText(value, field));
    }

    private static String optionalAddress(JsonNode value) {
        return value == null || value.isNull() ? "" : address(value.asText());
    }

    private static String address(String value) {
        String normalized = Objects.requireNonNull(value, "address").toLowerCase(Locale.ROOT);
        if (!normalized.matches("0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("evm adapter: invalid address " + value);
        }
        return normalized;
    }

    private static String topicAddress(String topic) {
        String normalized = Objects.requireNonNull(topic, "topic").toLowerCase(Locale.ROOT);
        if (!normalized.matches("0x[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evm adapter: invalid address topic");
        }
        return address("0x" + normalized.substring(26));
    }

    private static boolean degradableTraceFailure(RuntimeException exception) {
        String message = Objects.toString(exception.getMessage(), "").toLowerCase(Locale.ROOT);
        return message.contains("-32601")
                || message.contains("method not found")
                || message.contains("not supported")
                || message.contains("unsupported")
                || message.contains("missing trie")
                || message.contains("pruned");
    }
}