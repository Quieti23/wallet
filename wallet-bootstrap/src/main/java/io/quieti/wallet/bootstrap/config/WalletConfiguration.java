package io.quieti.wallet.bootstrap.config;

import io.quieti.wallet.adapter.node.DeterministicNodeAdapter;
import io.quieti.wallet.adapter.persistence.JdbcScanPartitionRepository;
import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.application.scan.ScanBlockUseCase;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WalletConfiguration {

    @Bean
    NodePort nodePort() {
        return new DeterministicNodeAdapter();
    }

    @Bean
    ScanPartitionRepository scanPartitionRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        return new JdbcScanPartitionRepository(
                jdbcTemplate,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    ScanBlockUseCase scanBlockUseCase(
            NodePort nodePort,
            ScanPartitionRepository repository,
            WalletProperties properties) {
        return new ScanBlockUseCase(
                nodePort,
                repository,
                Clock.systemUTC(),
                properties.scan().leaseDuration());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService scanExecutor(WalletProperties properties) {
        WalletProperties.Scan scan = properties.scan();
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                scan.threads(),
                scan.threads(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(scan.queueCapacity()),
                task -> Thread.ofPlatform().name("scan-fetch-" + sequence.incrementAndGet()).unstarted(task),
                new ThreadPoolExecutor.AbortPolicy());
    }
}