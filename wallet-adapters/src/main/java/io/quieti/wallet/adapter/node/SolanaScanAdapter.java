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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SolanaScanAdapter implements ChainScanAdapter {

    public static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    public static final String TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
    private static final String SYSTEM_PROGRAM = "11111111111111111111111111111111";

    public record AssetConfig(String programId, String mint, int decimals) {
        public AssetConfig {
            if (!supportedTokenProgram(programId) || mint == null || mint.isBlank() || decimals < 0) {
                throw new IllegalArgumentException("solana adapter: invalid asset configuration");
            }
        }
    }

    private record TokenBalance(BigInteger amount, String owner, int decimals) {
    }

    private final RpcTransport rpc;
    private final CanonicalReader history;
    private final ChainRef chain;
    private final long initialSlot;
    private final Map<String, AssetConfig> assets;
    private final Map<String, String> tokenAccounts;

    public SolanaScanAdapter(
            String genesisHash,
            long initialSlot,
            RpcTransport rpc,
            CanonicalReader history,
            List<AssetConfig> assets,
            Map<String, String> tokenAccounts) {
        if (genesisHash == null || genesisHash.isBlank() || initialSlot < 0) {
            throw new IllegalArgumentException("solana adapter: incomplete configuration");
        }
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.history = Objects.requireNonNull(history, "history");
        this.chain = ChainRef.parse("solana:" + genesisHash);
        this.initialSlot = initialSlot;
        Map<String, AssetConfig> configuredAssets = new HashMap<>();
        for (AssetConfig asset : Objects.requireNonNull(assets, "assets")) {
            if (configuredAssets.putIfAbsent(assetKey(asset.programId(), asset.mint()), asset) != null) {
                throw new IllegalArgumentException("solana adapter: duplicate asset configuration");
            }
        }
        this.assets = Map.copyOf(configuredAssets);
        this.tokenAccounts = Map.copyOf(Objects.requireNonNull(tokenAccounts, "tokenAccounts"));
        if (this.tokenAccounts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("solana adapter: invalid token account binding");
        }
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
        requireCursorChain(cursor);
        if (cursor.point() == null) {
            return new CursorCheck(true, "");
        }
        long slot = pointSlot(cursor.point());
        ChainPoint node = blockPoint(block(slot, "confirmed"), slot);
        boolean canonical = node.hash().equals(cursor.point().hash());
        return new CursorCheck(canonical, canonical ? "" : "cursor blockhash differs from node");
    }

    @Override
    public ScanBatch scanNext(ScanCursor cursor, WatchSnapshot watches, ChainPoint head) {
        requireCursorChain(cursor);
        long next = cursor.point() == null ? initialSlot : Math.addExact(pointSlot(cursor.point()), 1);
        long headSlot = pointSlot(Objects.requireNonNull(head, "head"));
        if (next > headSlot) {
            return new ScanBatch(List.of(), List.of(), null, false);
        }
        JsonNode slots = rpc.call("getBlocks", next, headSlot, Map.of("commitment", "confirmed"));
        if (!slots.isArray()) {
            throw new IllegalArgumentException("solana adapter: getBlocks result must be an array");
        }
        if (slots.isEmpty()) {
            return new ScanBatch(List.of(), List.of(), null, false);
        }
        long producedSlot = nonNegativeLong(slots.get(0), "produced slot");
        if (producedSlot < next || producedSlot > headSlot) {
            throw new IllegalArgumentException("solana adapter: produced slot outside requested range");
        }
        JsonNode block = block(producedSlot, "confirmed");
        ChainPoint point = blockPoint(block, producedSlot);
        if (cursor.point() != null
                && (requiredLong(block, "parentSlot") != pointSlot(cursor.point())
                || !requiredText(block, "previousBlockhash").equals(cursor.point().hash()))) {
            throw new IllegalStateException("solana adapter: chain discontinuity");
        }
        List<DepositObservation> observations = observations(block, point, watches);
        return new ScanBatch(
                List.of(new CanonicalUnit(chain, point, true)),
                observations,
                new ScanCursor(chain, cursor.scannerId(), point, cursor.version() + 1),
                true);
    }

    @Override
    public ChainPoint findCommonAncestor(ScanCursor cursor, long maxDepth) {
        requireCursorChain(cursor);
        if (cursor.point() == null) {
            throw deepReorg();
        }
        long slot = pointSlot(cursor.point());
        for (long depth = 0; depth <= maxDepth; depth++) {
            JsonNode block = block(slot, "confirmed");
            ChainPoint node = blockPoint(block, slot);
            Optional<ChainPoint> local = history.canonicalPoint(chain, Long.toString(slot));
            if (local.isPresent() && local.orElseThrow().hash().equals(node.hash())) {
                return local.orElseThrow();
            }
            long parent = requiredLong(block, "parentSlot");
            if (slot == 0 || parent >= slot) {
                break;
            }
            slot = parent;
        }
        throw deepReorg();
    }

    private ChainPoint policyHead(FinalityTarget target) {
        Objects.requireNonNull(target, "target");
        if (!target.kind().equals("commitment")
                || !target.value().equals("confirmed") && !target.value().equals("finalized")) {
            throw new IllegalArgumentException("solana adapter: unsupported finality target");
        }
        JsonNode value = rpc.call("getSlot", Map.of("commitment", target.value()));
        long slot = nonNegativeLong(value, "head slot");
        return blockPoint(block(slot, target.value()), slot);
    }

    private JsonNode block(long slot, String commitment) {
        JsonNode value = rpc.call("getBlock", slot, Map.of(
                "commitment", commitment,
                "encoding", "jsonParsed",
                "transactionDetails", "full",
                "rewards", false,
                "maxSupportedTransactionVersion", 0));
        if (value == null || value.isNull() || !value.isObject()) {
            throw new IllegalArgumentException("solana adapter: block unavailable at slot " + slot);
        }
        return value;
    }

    private ChainPoint blockPoint(JsonNode block, long slot) {
        String hash = requiredText(block, "blockhash");
        String previous = requiredText(block, "previousBlockhash");
        long parentSlot = requiredLong(block, "parentSlot");
        long blockTime = block.path("blockTime").isNumber() ? block.path("blockTime").longValue() : 0;
        List<PointRef> parents = previous.isEmpty() || previous.equals(hash)
                ? List.of()
                : List.of(new PointRef("", Long.toString(parentSlot), previous));
        return new ChainPoint("", Long.toString(slot), hash, parents, Instant.ofEpochSecond(blockTime));
    }

    private List<DepositObservation> observations(
            JsonNode block,
            ChainPoint point,
            WatchSnapshot snapshot) {
        Map<String, WatchTarget> watched = new HashMap<>();
        snapshot.targets().forEach(target -> watched.put(target.normalizedTarget(), target));
        JsonNode transactions = block.get("transactions");
        if (transactions == null || !transactions.isArray()) {
            throw new IllegalArgumentException("solana adapter: block transactions are required");
        }
        List<DepositObservation> result = new ArrayList<>();
        for (JsonNode raw : transactions) {
            JsonNode meta = raw.path("meta");
            if (!meta.has("err") || !meta.get("err").isNull()) {
                continue;
            }
            JsonNode transaction = raw.path("transaction");
            JsonNode signatures = transaction.path("signatures");
            if (!signatures.isArray() || signatures.isEmpty() || signatures.get(0).asText().isBlank()) {
                throw new IllegalArgumentException("solana adapter: transaction signature is required");
            }
            String signature = signatures.get(0).asText();
            List<String> keys = accountKeys(transaction.path("message").path("accountKeys"), meta);
            List<BigInteger> preBalances = balances(meta.path("preBalances"));
            List<BigInteger> postBalances = balances(meta.path("postBalances"));
            if (keys.size() != preBalances.size() || keys.size() != postBalances.size()) {
                throw new IllegalArgumentException("solana adapter: inconsistent transaction balances");
            }
            List<Instruction> instructions = instructions(transaction.path("message"), meta, keys);
            result.addAll(nativeObservations(
                    signature, instructions, keys, preBalances, postBalances, watched, point));
            result.addAll(tokenObservations(signature, instructions, keys, meta, watched, point));
        }
        return List.copyOf(result);
    }

    private List<DepositObservation> nativeObservations(
            String signature,
            List<Instruction> instructions,
            List<String> keys,
            List<BigInteger> pre,
            List<BigInteger> post,
            Map<String, WatchTarget> watched,
            ChainPoint point) {
        List<DepositObservation> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (Instruction instruction : instructions) {
            if (!instruction.programId().equals(SYSTEM_PROGRAM)
                    || !instruction.type().equals("transfer")) {
                continue;
            }
            String destination = instruction.info().path("destination").asText();
            WatchTarget target = watched.get(destination);
            int index = keys.indexOf(destination);
            if (target == null || index < 0 || post.get(index).compareTo(pre.get(index)) <= 0
                    || !emitted.add(destination)) {
                continue;
            }
            result.add(observation(
                    signature,
                    instruction.path(),
                    new AssetRef(chain, "native", "sol"),
                    instruction.info().path("source").asText(),
                    destination,
                    post.get(index).subtract(pre.get(index)),
                    9,
                    target,
                    point,
                    Map.of()));
        }
        return result;
    }

    private List<DepositObservation> tokenObservations(
            String signature,
            List<Instruction> instructions,
            List<String> keys,
            JsonNode meta,
            Map<String, WatchTarget> watched,
            ChainPoint point) {
        Map<String, TokenBalance> pre = tokenBalances(meta.path("preTokenBalances"), keys);
        Map<String, TokenBalance> post = tokenBalances(meta.path("postTokenBalances"), keys);
        Set<String> emitted = new HashSet<>();
        List<DepositObservation> result = new ArrayList<>();
        for (Instruction instruction : instructions) {
            if (!supportedTokenProgram(instruction.programId())
                    || !instruction.type().equals("transfer") && !instruction.type().equals("transferChecked")) {
                continue;
            }
            String destination = instruction.info().path("destination").asText();
            String mint = instruction.info().path("mint").asText();
            if (mint.isEmpty()) {
                mint = resolveMint(destination, instruction.programId(), pre, post);
            }
            AssetConfig asset = assets.get(assetKey(instruction.programId(), mint));
            WatchTarget target = watched.get(destination);
            String key = balanceKey(destination, instruction.programId(), mint);
            TokenBalance before = pre.get(key);
            TokenBalance after = post.get(key);
            String expectedOwner = tokenAccounts.get(destination);
            if (asset == null || target == null || before == null || after == null || expectedOwner == null
                    || !before.owner().equals(expectedOwner) || !after.owner().equals(expectedOwner)
                    || after.decimals() != asset.decimals() || !emitted.add(key)) {
                continue;
            }
            BigInteger delta = after.amount().subtract(before.amount());
            if (delta.signum() <= 0) {
                continue;
            }
            result.add(observation(
                    signature,
                    instruction.path(),
                    new AssetRef(chain, tokenStandard(instruction.programId()), mint),
                    instruction.info().path("source").asText(),
                    destination,
                    delta,
                    asset.decimals(),
                    target,
                    point,
                    Map.of(
                            "authority", instruction.info().path("authority").asText(),
                            "token_program", instruction.programId(),
                            "mint", mint)));
        }
        return result;
    }

    private DepositObservation observation(
            String signature,
            String path,
            AssetRef asset,
            String from,
            String to,
            BigInteger amount,
            int decimals,
            WatchTarget target,
            ChainPoint point,
            Map<String, String> evidence) {
        Map<String, String> metadata = new HashMap<>(evidence);
        metadata.put("owner_id", target.ownerId());
        metadata.put("account_id", target.accountId());
        return new DepositObservation(
                new ObservationId(chain, new TransactionRef("signature", signature), "instruction:" + path),
                asset,
                from,
                to,
                new Amount(amount.toString(), decimals),
                point,
                metadata);
    }

    private static List<String> accountKeys(JsonNode rawKeys, JsonNode meta) {
        if (!rawKeys.isArray()) {
            throw new IllegalArgumentException("solana adapter: account keys are required");
        }
        List<String> keys = new ArrayList<>();
        rawKeys.forEach(key -> keys.add(key.isTextual() ? key.asText() : key.path("pubkey").asText()));
        int expected = meta.path("preBalances").size();
        if (keys.size() < expected) {
            meta.path("loadedAddresses").path("writable").forEach(value -> keys.add(value.asText()));
            meta.path("loadedAddresses").path("readonly").forEach(value -> keys.add(value.asText()));
        }
        if (keys.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("solana adapter: invalid account key");
        }
        return keys;
    }

    private static List<BigInteger> balances(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("solana adapter: balances are required");
        }
        List<BigInteger> result = new ArrayList<>();
        values.forEach(value -> result.add(unsignedInteger(value, "lamport balance")));
        return result;
    }

    private static Map<String, TokenBalance> tokenBalances(JsonNode values, List<String> keys) {
        Map<String, TokenBalance> result = new HashMap<>();
        if (values.isMissingNode() || values.isNull()) {
            return result;
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException("solana adapter: token balances must be an array");
        }
        for (JsonNode value : values) {
            long index = requiredLong(value, "accountIndex");
            String program = requiredText(value, "programId");
            if (index >= keys.size() || !supportedTokenProgram(program)) {
                throw new IllegalArgumentException("solana adapter: invalid token balance evidence");
            }
            String mint = requiredText(value, "mint");
            JsonNode tokenAmount = value.path("uiTokenAmount");
            BigInteger amount = decimalInteger(tokenAmount.path("amount").asText(), "token amount");
            int decimals = Math.toIntExact(requiredLong(tokenAmount, "decimals"));
            result.put(
                    balanceKey(keys.get((int) index), program, mint),
                    new TokenBalance(amount, requiredText(value, "owner"), decimals));
        }
        return result;
    }

    private record Instruction(String path, String programId, String type, JsonNode info) {
    }

    private static List<Instruction> instructions(JsonNode message, JsonNode meta, List<String> keys) {
        List<Instruction> result = new ArrayList<>();
        JsonNode topLevel = message.path("instructions");
        for (int index = 0; index < topLevel.size(); index++) {
            addInstruction(result, Integer.toString(index), topLevel.get(index), keys, meta);
        }
        for (JsonNode group : meta.path("innerInstructions")) {
            long parent = requiredLong(group, "index");
            JsonNode nested = group.path("instructions");
            for (int index = 0; index < nested.size(); index++) {
                addInstruction(result, parent + "." + index, nested.get(index), keys, meta);
            }
        }
        return result;
    }

    private static void addInstruction(
            List<Instruction> result,
            String path,
            JsonNode raw,
            List<String> keys,
            JsonNode meta) {
        JsonNode parsed = raw.get("parsed");
        if (parsed == null || parsed.isNull() || !parsed.isObject()) {
            return;
        }
        String program = raw.path("programId").asText();
        String type = parsed.path("type").asText();
        JsonNode info = parsed.path("info");
        if (program.equals(SYSTEM_PROGRAM) && type.equals("transfer")) {
            result.add(new Instruction(path, program, type, info));
        } else if (supportedTokenProgram(program)
                && (type.equals("transfer") || type.equals("transferChecked"))) {
            result.add(new Instruction(path, program, type, info));
        }
    }

    private static String resolveMint(
            String destination,
            String program,
            Map<String, TokenBalance> pre,
            Map<String, TokenBalance> post) {
        String prefix = destination + "\0" + program + "\0";
        return java.util.stream.Stream.concat(post.keySet().stream(), pre.keySet().stream())
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .findFirst()
                .orElse("");
    }

    private static long pointSlot(ChainPoint point) {
        try {
            long slot = Long.parseLong(point.position());
            if (slot < 0) {
                throw new NumberFormatException("negative");
            }
            return slot;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("solana adapter: invalid point position", exception);
        }
    }

    private void requireCursorChain(ScanCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        if (!cursor.chain().equals(chain)) {
            throw new IllegalArgumentException("solana adapter: cursor chain differs");
        }
    }

    private static long requiredLong(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        if (fieldValue == null || !fieldValue.canConvertToLong() || fieldValue.longValue() < 0) {
            throw new IllegalArgumentException("solana adapter: invalid " + field);
        }
        return fieldValue.longValue();
    }

    private static long nonNegativeLong(JsonNode value, String name) {
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("solana adapter: invalid " + name);
        }
        return value.longValue();
    }

    private static BigInteger unsignedInteger(JsonNode value, String name) {
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("solana adapter: invalid " + name);
        }
        return decimalInteger(value.asText(), name);
    }

    private static BigInteger decimalInteger(String value, String name) {
        try {
            BigInteger result = new BigInteger(value);
            if (result.signum() < 0) {
                throw new NumberFormatException("negative");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("solana adapter: invalid " + name, exception);
        }
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        if (fieldValue == null || !fieldValue.isTextual() || fieldValue.textValue().isBlank()) {
            throw new IllegalArgumentException("solana adapter: missing " + field);
        }
        return fieldValue.textValue();
    }

    private static boolean supportedTokenProgram(String program) {
        return TOKEN_PROGRAM.equals(program) || TOKEN_2022_PROGRAM.equals(program);
    }

    private static String tokenStandard(String program) {
        return TOKEN_2022_PROGRAM.equals(program) ? "spl-2022" : "spl";
    }

    private static String assetKey(String program, String mint) {
        return program + "\0" + mint;
    }

    private static String balanceKey(String address, String program, String mint) {
        return address + "\0" + program + "\0" + mint;
    }

    private static IllegalStateException deepReorg() {
        return new IllegalStateException("solana adapter: reorg exceeds retained window");
    }
}
