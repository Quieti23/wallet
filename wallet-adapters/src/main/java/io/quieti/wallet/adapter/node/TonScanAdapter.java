package io.quieti.wallet.adapter.node;

import com.fasterxml.jackson.databind.JsonNode;
import io.quieti.wallet.adapter.ton.ToncenterApi;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class TonScanAdapter implements ChainScanAdapter {

    public record JettonConfig(String master, int decimals) {
        public JettonConfig {
            if (master == null || master.isBlank() || decimals < 0) {
                throw new IllegalArgumentException("ton adapter: invalid jetton configuration");
            }
        }
    }

    private static final long MASTERCHAIN_WORKCHAIN = -1;
    private static final String MASTERCHAIN_SHARD = "-9223372036854775808";

    private final ToncenterApi api;
    private final CanonicalReader history;
    private final ChainRef chain;
    private final long initialSeqno;
    private final Map<String, JettonConfig> jettons;

    public TonScanAdapter(
            String network,
            long initialSeqno,
            ToncenterApi api,
            CanonicalReader history,
            List<JettonConfig> jettons) {
        if (!Set.of("mainnet", "testnet").contains(network) || initialSeqno < 0) {
            throw new IllegalArgumentException("ton adapter: invalid network or initial seqno");
        }
        this.api = Objects.requireNonNull(api, "api");
        this.history = Objects.requireNonNull(history, "history");
        this.chain = ChainRef.parse("ton:" + network);
        this.initialSeqno = initialSeqno;
        Map<String, JettonConfig> configured = new HashMap<>();
        for (JettonConfig jetton : Objects.requireNonNull(jettons, "jettons")) {
            if (configured.putIfAbsent(jetton.master(), jetton) != null) {
                throw new IllegalArgumentException("ton adapter: duplicate jetton configuration");
            }
        }
        this.jettons = Map.copyOf(configured);
    }

    @Override
    public ChainRef chain() {
        return chain;
    }

    @Override
    public ChainPoint observationHead(FinalityPolicy policy) {
        return policyHead(policy.observationTarget(chain));
    }

    @Override
    public ChainPoint finalityHead(FinalityPolicy policy) {
        return policyHead(policy.finalityTarget(chain));
    }

    @Override
    public CursorCheck verifyCursor(ScanCursor cursor) {
        requireCursor(cursor);
        if (cursor.point() == null) {
            return new CursorCheck(true, "");
        }
        ChainPoint node = point(masterchainBlock(position(cursor.point())));
        boolean canonical = node.hash().equals(cursor.point().hash());
        return new CursorCheck(canonical, canonical ? "" : "cursor root hash differs from node");
    }

    @Override
    public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
        requireCursor(cursor);
        long next = cursor.point() == null ? initialSeqno : Math.addExact(position(cursor.point()), 1);
        if (next > position(Objects.requireNonNull(head, "head"))) {
            return new ScanBatch(List.of(), List.of(), null, false);
        }
        JsonNode block = masterchainBlock(next);
        ChainPoint point = point(block);
        if (cursor.point() != null && point.parents().stream().noneMatch(parent ->
                parent.scope().equals(cursor.point().scope())
                        && parent.position().equals(cursor.point().position())
                        && parent.hash().equals(cursor.point().hash()))) {
            throw new IllegalStateException("ton adapter: chain discontinuity");
        }
        List<JsonNode> transfers = api.jettonTransfers(blockTime(block));
        List<DepositObservation> observations = observations(
                api.transactions(next), transfers, point, watches);
        return new ScanBatch(
                List.of(new CanonicalUnit(chain, point, true)),
                observations,
                new ScanCursor(chain, cursor.scannerId(), point, cursor.version() + 1),
                true);
    }

    @Override
    public ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth) {
        requireCursor(cursor);
        if (cursor.point() == null) {
            throw deepReorg();
        }
        long seqno = position(cursor.point());
        for (long depth = 0; depth <= maxDepth && seqno >= 0; depth++, seqno--) {
            ChainPoint node = point(masterchainBlock(seqno));
            Optional<ChainPoint> local = history.canonicalPoint(chain, Long.toString(seqno));
            if (local.isPresent() && local.orElseThrow().hash().equals(node.hash())) {
                return local.orElseThrow();
            }
        }
        throw deepReorg();
    }

    private ChainPoint policyHead(FinalityTarget target) {
        if (!Objects.requireNonNull(target, "target").kind().equals("masterchain-depth")) {
            throw new IllegalArgumentException("ton adapter: unsupported finality target");
        }
        long depth;
        try {
            depth = Long.parseLong(target.value());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("ton adapter: invalid masterchain depth", error);
        }
        JsonNode last = api.masterchainInfo().path("last");
        long head = requiredLong(last, "seqno");
        if (head == 0 || last.path("root_hash").asText().isBlank() || depth < 0 || depth > head) {
            throw new IllegalArgumentException("ton adapter: incomplete masterchain head");
        }
        return point(masterchainBlock(head - depth));
    }

    private JsonNode masterchainBlock(long seqno) {
        JsonNode block = api.block(MASTERCHAIN_WORKCHAIN, MASTERCHAIN_SHARD, seqno);
        if (requiredLong(block, "seqno") != seqno || requiredLong(block, "workchain") != MASTERCHAIN_WORKCHAIN) {
            throw new IllegalArgumentException("ton adapter: unexpected masterchain block");
        }
        return block;
    }

    private ChainPoint point(JsonNode block) {
        List<PointRef> parents = new ArrayList<>();
        JsonNode previous = block.path("prev_blocks");
        if (!previous.isArray()) {
            throw new IllegalArgumentException("ton adapter: prev_blocks must be an array");
        }
        for (JsonNode reference : previous) {
            long workchain = requiredLong(reference, "workchain");
            String shard = requiredText(reference, "shard");
            long seqno = requiredLong(reference, "seqno");
            JsonNode parent = api.block(workchain, shard, seqno);
            parents.add(new PointRef(
                    workchain == MASTERCHAIN_WORKCHAIN ? "masterchain" : workchain + ":" + shard,
                    Long.toString(seqno),
                    requiredText(parent, "root_hash")));
        }
        return new ChainPoint(
                "masterchain",
                Long.toString(requiredLong(block, "seqno")),
                requiredText(block, "root_hash"),
                parents,
                Instant.ofEpochSecond(blockTime(block)));
    }

    private List<DepositObservation> observations(
            List<JsonNode> transactions,
            List<JsonNode> transfers,
            ChainPoint point,
            WatchSnapshot snapshot) {
        Map<String, WatchTarget> watched = new HashMap<>();
        snapshot.targets().forEach(target -> watched.put(target.normalizedTarget(), target));
        Map<String, List<JsonNode>> transfersByTransaction = new HashMap<>();
        transfers.forEach(transfer -> transfersByTransaction
                .computeIfAbsent(transfer.path("transaction_hash").asText(), ignored -> new ArrayList<>())
                .add(transfer));
        List<DepositObservation> result = new ArrayList<>();
        for (JsonNode transaction : transactions) {
            String transactionHash = transaction.path("hash").asText();
            if (transactionHash.isBlank() || transaction.path("description").path("aborted").asBoolean()) {
                continue;
            }
            List<JsonNode> transactionTransfers = transfersByTransaction.getOrDefault(transactionHash, List.of());
            if (!transactionTransfers.isEmpty()) {
                for (int index = 0; index < transactionTransfers.size(); index++) {
                    DepositObservation observation = jettonObservation(
                            transactionHash, transactionTransfers.get(index), index, watched, point);
                    if (observation != null) {
                        result.add(observation);
                    }
                }
            } else {
                DepositObservation observation = nativeObservation(transactionHash, transaction, watched, point);
                if (observation != null) {
                    result.add(observation);
                }
            }
        }
        return List.copyOf(result);
    }

    private DepositObservation nativeObservation(
            String transactionHash,
            JsonNode transaction,
            Map<String, WatchTarget> watched,
            ChainPoint point) {
        JsonNode message = transaction.path("in_msg");
        String destination = message.path("destination").asText();
        String messageHash = message.path("hash").asText();
        WatchTarget target = watched.get(destination);
        BigInteger amount = positiveAmount(message.path("value").asText());
        if (target == null || messageHash.isBlank() || message.path("bounced").asBoolean() || amount == null) {
            return null;
        }
        return observation(transactionHash, "message:" + messageHash, new AssetRef(chain, "native", "ton"),
                message.path("source").asText(), destination, amount, 9, point, target, Map.of());
    }

    private DepositObservation jettonObservation(
            String transactionHash,
            JsonNode transfer,
            int index,
            Map<String, WatchTarget> watched,
            ChainPoint point) {
        String master = transfer.path("jetton_master").asText();
        String destination = transfer.path("destination").asText();
        JettonConfig config = jettons.get(master);
        WatchTarget target = watched.get(destination);
        BigInteger amount = positiveAmount(transfer.path("amount").asText());
        if (config == null || target == null || transfer.path("transaction_aborted").asBoolean() || amount == null) {
            return null;
        }
        String queryId = transfer.path("query_id").asText();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("jetton_master", master);
        metadata.put("jetton_source_wallet", transfer.path("source_wallet").asText());
        return observation(transactionHash, "jetton-transfer:" + (queryId.isBlank() ? index : queryId),
                new AssetRef(chain, "jetton", master), transfer.path("source").asText(), destination,
                amount, config.decimals(), point, target, metadata);
    }

    private DepositObservation observation(
            String transactionHash,
            String source,
            AssetRef asset,
            String from,
            String to,
            BigInteger amount,
            int decimals,
            ChainPoint point,
            WatchTarget target,
            Map<String, String> extraMetadata) {
        Map<String, String> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put("owner_id", target.ownerId());
        metadata.put("account_id", target.accountId());
        return new DepositObservation(
                new ObservationId(chain, new TransactionRef("transaction-hash", transactionHash), source),
                asset, from, to, new Amount(amount.toString(), decimals), point, metadata);
    }

    private void requireCursor(ScanCursor cursor) {
        if (!Objects.requireNonNull(cursor, "cursor").chain().equals(chain)) {
            throw new IllegalArgumentException("ton adapter: cursor chain mismatch");
        }
    }

    private static long position(ChainPoint point) {
        if (!point.scope().isEmpty() && !point.scope().equals("masterchain")) {
            throw new IllegalArgumentException("ton adapter: point is not masterchain scoped");
        }
        try {
            return Long.parseLong(point.position());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("ton adapter: invalid masterchain position", error);
        }
    }

    private static long blockTime(JsonNode block) {
        JsonNode value = block.path("gen_utime");
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("ton adapter: invalid block time", error);
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException("ton adapter: " + field + " is required");
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("ton adapter: " + field + " is required");
        }
        return value;
    }

    private static BigInteger positiveAmount(String raw) {
        try {
            BigInteger value = new BigInteger(raw);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static IllegalStateException deepReorg() {
        return new IllegalStateException("ton adapter: no common ancestor within configured depth");
    }
}