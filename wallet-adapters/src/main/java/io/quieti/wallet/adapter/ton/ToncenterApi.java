package io.quieti.wallet.adapter.ton;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface ToncenterApi {

    JsonNode masterchainInfo();

    JsonNode block(long workchain, String shard, long seqno);

    List<JsonNode> transactions(long masterchainSeqno);

    List<JsonNode> jettonTransfers(long blockTime);
}