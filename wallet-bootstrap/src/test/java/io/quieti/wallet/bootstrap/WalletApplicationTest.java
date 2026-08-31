package io.quieti.wallet.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.quieti.wallet.domain.chain.ChainBlock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void startsWalletApplication() {
        assertThat(context).isNotNull();
    }

    @Test
    void scansConsecutiveBlocksOverHttp() {
        ResponseEntity<ChainBlock> first = rest.postForEntity(
                "/api/v1/scans/evm/integration-test/next", null, ChainBlock.class);
        ResponseEntity<ChainBlock> second = rest.postForEntity(
                "/api/v1/scans/evm/integration-test/next", null, ChainBlock.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().height()).isEqualTo(1);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().height()).isEqualTo(2);
        assertThat(second.getBody().parentHash()).isEqualTo(first.getBody().hash());
    }
}