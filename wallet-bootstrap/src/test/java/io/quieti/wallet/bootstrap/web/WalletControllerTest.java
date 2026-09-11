package io.quieti.wallet.bootstrap.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.quieti.wallet.application.port.BalancePort;
import io.quieti.wallet.application.port.TransactionBuilderPort;
import io.quieti.wallet.application.transfer.BalanceSnapshot;
import io.quieti.wallet.application.transfer.BuildTransferUseCase;
import io.quieti.wallet.application.transfer.QueryBalanceUseCase;
import io.quieti.wallet.application.transfer.UnsignedTransaction;
import java.math.BigInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WalletControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BalancePort balancePort = chain -> new BalanceSnapshot(
                chain,
                "BTC",
                new BigInteger("150000000"),
                BigInteger.TEN,
                BigInteger.ZERO,
                8);
        TransactionBuilderPort builderPort = request -> new UnsignedTransaction(
                request.chain(),
                "PSBT",
                "cHNidP8BAHECAAAAAQ==",
                new BigInteger("321"),
                1);
        WalletController controller = new WalletController(
                new QueryBalanceUseCase(balancePort),
                new BuildTransferUseCase(builderPort));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsWalletBalanceWithAtomicUnitsAsStrings() throws Exception {
        mvc.perform(get("/api/v1/balances/btc_signet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain").value("BTC_SIGNET"))
                .andExpect(jsonPath("$.asset").value("BTC"))
                .andExpect(jsonPath("$.availableAtomicUnits").value("150000000"))
                .andExpect(jsonPath("$.decimals").value(8));
    }

    @Test
    void returnsAnUnsignedPsbt() throws Exception {
        mvc.perform(post("/api/v1/transfers/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chain":"btc_testnet","toAddress":"tb1qdestination",
                                 "amountAtomicUnits":"125000000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain").value("BTC_TESTNET"))
                .andExpect(jsonPath("$.format").value("PSBT"))
                .andExpect(jsonPath("$.payload").value("cHNidP8BAHECAAAAAQ=="))
                .andExpect(jsonPath("$.feeAtomicUnits").value("321"))
                .andExpect(jsonPath("$.changePosition").value(1));
    }

    @Test
    void rejectsInvalidAtomicAmount() throws Exception {
        mvc.perform(post("/api/v1/transfers/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chain":"BTC_TESTNET","toAddress":"tb1qdestination",
                                 "amountAtomicUnits":"1.5"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}