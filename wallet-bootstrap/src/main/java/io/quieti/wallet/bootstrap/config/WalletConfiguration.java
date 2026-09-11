package io.quieti.wallet.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.quieti.wallet.adapter.node.BitcoinNodeAdapter;
import io.quieti.wallet.adapter.node.DeterministicNodeAdapter;
import io.quieti.wallet.adapter.node.EvmNodeAdapter;
import io.quieti.wallet.adapter.node.RoutingNodeAdapter;
import io.quieti.wallet.adapter.node.SolanaNodeAdapter;
import io.quieti.wallet.adapter.node.SolanaScanAdapter;
import io.quieti.wallet.adapter.node.TonNodeAdapter;
import io.quieti.wallet.adapter.node.TonScanAdapter;
import io.quieti.wallet.adapter.persistence.JdbcScanPartitionRepository;
import io.quieti.wallet.adapter.persistence.JdbcScannerRepository;
import io.quieti.wallet.adapter.rpc.FailoverRpcTransport;
import io.quieti.wallet.adapter.rpc.HttpJsonRpcTransport;
import io.quieti.wallet.adapter.rpc.RpcTransport;
import io.quieti.wallet.adapter.rpc.SolanaRpcPool;
import io.quieti.wallet.adapter.ton.ToncenterClient;
import io.quieti.wallet.adapter.wallet.BitcoinWalletAdapter;
import io.quieti.wallet.application.port.NodePort;
import io.quieti.wallet.application.port.ScanPartitionRepository;
import io.quieti.wallet.application.scan.FinalityPolicy;
import io.quieti.wallet.application.scan.FinalityTarget;
import io.quieti.wallet.application.scan.ScanBlockUseCase;
import io.quieti.wallet.application.scan.ScanCoordinator;
import io.quieti.wallet.application.scan.ScanCoordinatorConfig;
import io.quieti.wallet.application.scan.ScanCycleRunner;
import io.quieti.wallet.application.scan.ScannerSupervisor;
import io.quieti.wallet.application.scan.ShadowScanCoordinator;
import io.quieti.wallet.application.scan.ShadowScanReporter;
import io.quieti.wallet.application.scan.SupervisedScanner;
import io.quieti.wallet.application.transfer.BuildTransferUseCase;
import io.quieti.wallet.application.transfer.QueryBalanceUseCase;
import java.util.List;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class WalletConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletConfiguration.class);

    @Bean
    @ConditionalOnProperty(
            name = "wallet.rpc.enabled",
            havingValue = "false",
            matchIfMissing = true)
    NodePort nodePort() {
        return new DeterministicNodeAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.rpc.enabled", havingValue = "true")
    NodePort rpcNodePort(WalletRpcProperties properties, ObjectMapper mapper) {
        Map<String, NodePort> adapters = new HashMap<>();
        WalletRpcProperties.Endpoint bitcoin = requiredEndpoint(properties, "BTC_TESTNET");
        WalletRpcProperties.Endpoint evm = requiredEndpoint(properties, "EVM");
        WalletRpcProperties.Endpoint solana = requiredEndpoint(properties, "SOL");
        WalletRpcProperties.Endpoint ton = requiredEndpoint(properties, "TON");
        adapters.put("BTC_TESTNET", new BitcoinNodeAdapter(
                "BTC_TESTNET", checkpoint(bitcoin), transport(properties, bitcoin, mapper)));
        adapters.put("EVM", new EvmNodeAdapter(checkpoint(evm), transport(properties, evm, mapper)));
        adapters.put("SOL", new SolanaNodeAdapter(
                checkpoint(solana),
                transport(properties, solana, mapper),
                solana.maxSkippedPositions()));
        adapters.put("TON", new TonNodeAdapter(checkpoint(ton), transport(properties, ton, mapper)));
        return new RoutingNodeAdapter(adapters);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.rpc.enabled", havingValue = "true")
    BitcoinWalletAdapter bitcoinWalletAdapter(
            WalletRpcProperties rpcProperties,
            WalletScannerProperties scannerProperties,
            ObjectMapper mapper) {
        String network = scannerProperties.btc().network();
        WalletRpcProperties.Endpoint endpoint = requiredEndpoint(rpcProperties, network);
        return new BitcoinWalletAdapter(network, transport(rpcProperties, endpoint, mapper));
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.rpc.enabled", havingValue = "true")
    QueryBalanceUseCase queryBalanceUseCase(BitcoinWalletAdapter adapter) {
        return new QueryBalanceUseCase(adapter);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.rpc.enabled", havingValue = "true")
    BuildTransferUseCase buildTransferUseCase(BitcoinWalletAdapter adapter) {
        return new BuildTransferUseCase(adapter);
    }

    private static WalletRpcProperties.Endpoint requiredEndpoint(
            WalletRpcProperties properties, String chain) {
        WalletRpcProperties.Endpoint endpoint = properties.chains().get(chain);
        if (endpoint == null || endpoint.startHash().isBlank()) {
            throw new IllegalStateException("Missing trusted RPC checkpoint for " + chain);
        }
        return endpoint;
    }

    private static io.quieti.wallet.domain.chain.ChainCheckpoint checkpoint(
            WalletRpcProperties.Endpoint endpoint) {
        return new io.quieti.wallet.domain.chain.ChainCheckpoint(
                endpoint.startHeight(), endpoint.startHash());
    }

    private static RpcTransport transport(
            WalletRpcProperties properties,
            WalletRpcProperties.Endpoint endpoint,
            ObjectMapper mapper) {
        return new HttpJsonRpcTransport(
                mapper,
                endpoint.endpoint(),
                properties.connectTimeout(),
                properties.requestTimeout(),
                endpoint.username(),
                endpoint.password(),
                endpoint.apiKey(),
                properties.maxResponseBytes());
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
    JdbcScannerRepository scannerRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper mapper) {
        return new JdbcScannerRepository(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                mapper);
    }

    @Bean
    ShadowScanReporter shadowScanReporter(MeterRegistry meterRegistry) {
        return new MicrometerShadowScanReporter(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.scanners.btc.enabled", havingValue = "true")
    SupervisedScanner bitcoinScanner(
            WalletScannerProperties scannerProperties,
            WalletRpcProperties rpcProperties,
            ObjectMapper mapper,
            JdbcScannerRepository repository,
            ShadowScanReporter shadowReporter) {
        WalletScannerProperties.Bitcoin scanner = scannerProperties.btc();
        WalletRpcProperties.Endpoint endpoint = requiredEndpoint(rpcProperties, scanner.network());
        BitcoinNodeAdapter adapter = new BitcoinNodeAdapter(
                scanner.network(),
                checkpoint(endpoint),
                transport(rpcProperties, endpoint, mapper),
                repository,
                scanner.confirmations());
        FinalityPolicy finalityPolicy = new FinalityPolicy() {
            @Override
            public FinalityTarget observationTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("tag", "latest");
            }

            @Override
            public FinalityTarget finalityTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("confirmations", Long.toString(scanner.confirmations()));
            }
        };
        ScanCycleRunner coordinator = coordinator(
            scannerProperties.shadowMode(),
            scanner.scannerId(),
            scanner.maxReorgDepth(),
            scanner.maxCycleRetries(),
            adapter,
            repository,
            finalityPolicy,
            shadowReporter);
        return new SupervisedScanner(
                adapter.chain(),
                scanner.scannerId(),
                scanner.pollInterval(),
                coordinator,
                adapter);
    }

            @Bean
            @ConditionalOnProperty(name = "wallet.scanners.evm.enabled", havingValue = "true")
            SupervisedScanner evmScanner(
                WalletScannerProperties scannerProperties,
                WalletRpcProperties rpcProperties,
                ObjectMapper mapper,
                JdbcScannerRepository repository,
                ShadowScanReporter shadowReporter) {
            WalletScannerProperties.Evm scanner = scannerProperties.evm();
            List<WalletRpcProperties.Endpoint> endpoints = scanner.providerKeys().stream()
                .map(key -> requiredEndpoint(rpcProperties, key))
                .toList();
            RpcTransport rpc = new FailoverRpcTransport(endpoints.stream()
                .map(endpoint -> transport(rpcProperties, endpoint, mapper))
                .toList());
            EvmNodeAdapter adapter = new EvmNodeAdapter(
                scanner.chainId(),
                checkpoint(endpoints.getFirst()),
                rpc,
                repository,
                scanner.fallbackConfirmations(),
                scanner.includeInternal(),
                scanner.tokenContracts());
            FinalityPolicy finalityPolicy = new FinalityPolicy() {
                @Override
                public FinalityTarget observationTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("tag", scanner.observationHead());
                }

                @Override
                public FinalityTarget finalityTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("tag", scanner.finalityHead());
                }
            };
            ScanCycleRunner coordinator = coordinator(
                scannerProperties.shadowMode(),
                scanner.scannerId(),
                scanner.maxReorgDepth(),
                scanner.maxCycleRetries(),
                adapter,
                repository,
                finalityPolicy,
                shadowReporter);
            return new SupervisedScanner(
                adapter.chain(),
                scanner.scannerId(),
                scanner.pollInterval(),
                coordinator,
                adapter);
            }

            @Bean
            @ConditionalOnProperty(name = "wallet.scanners.solana.enabled", havingValue = "true")
            SupervisedScanner solanaScanner(
                WalletScannerProperties scannerProperties,
                WalletRpcProperties rpcProperties,
                ObjectMapper mapper,
                JdbcScannerRepository repository,
                ShadowScanReporter shadowReporter) {
            WalletScannerProperties.Solana scanner = scannerProperties.solana();
            List<WalletRpcProperties.Endpoint> endpoints = scanner.providerKeys().stream()
                .map(key -> requiredEndpoint(rpcProperties, key))
                .toList();
            RpcTransport rpc = new SolanaRpcPool(
                java.util.stream.IntStream.range(0, endpoints.size())
                    .mapToObj(index -> new SolanaRpcPool.Node(
                        scanner.providerKeys().get(index),
                        transport(rpcProperties, endpoints.get(index), mapper)))
                    .toList(),
                scanner.maxSlotLag(),
                scanner.baseBackoff(),
                scanner.maxBackoff());
            SolanaScanAdapter adapter = new SolanaScanAdapter(
                scanner.genesisHash(),
                Math.addExact(endpoints.get(0).startHeight(), 1),
                rpc,
                repository,
                scanner.assets().stream()
                    .map(asset -> new SolanaScanAdapter.AssetConfig(
                        asset.programId(), asset.mint(), asset.decimals()))
                    .toList(),
                scanner.tokenAccounts());
            FinalityPolicy finalityPolicy = new FinalityPolicy() {
                @Override
                public FinalityTarget observationTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("commitment", "confirmed");
                }

                @Override
                public FinalityTarget finalityTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("commitment", "finalized");
                }
            };
            ScanCycleRunner coordinator = coordinator(
                scannerProperties.shadowMode(),
                scanner.scannerId(),
                scanner.maxReorgDepth(),
                scanner.maxCycleRetries(),
                adapter,
                repository,
                finalityPolicy,
                shadowReporter);
            return new SupervisedScanner(
                adapter.chain(),
                scanner.scannerId(),
                scanner.pollInterval(),
                coordinator,
                adapter);
            }

            @Bean
            @ConditionalOnProperty(name = "wallet.scanners.ton.enabled", havingValue = "true")
            SupervisedScanner tonScanner(
                WalletScannerProperties scannerProperties,
                WalletRpcProperties rpcProperties,
                ObjectMapper mapper,
                JdbcScannerRepository repository,
                ShadowScanReporter shadowReporter) {
            WalletScannerProperties.Ton scanner = scannerProperties.ton();
            WalletRpcProperties.Endpoint endpoint = requiredEndpoint(rpcProperties, scanner.providerKey());
            ToncenterClient client = new ToncenterClient(
                mapper,
                endpoint.endpoint(),
                rpcProperties.connectTimeout(),
                rpcProperties.requestTimeout(),
                endpoint.apiKey(),
                rpcProperties.maxResponseBytes());
            TonScanAdapter adapter = new TonScanAdapter(
                scanner.network(),
                Math.addExact(endpoint.startHeight(), 1),
                client,
                repository,
                scanner.jettons().stream()
                    .map(jetton -> new TonScanAdapter.JettonConfig(jetton.master(), jetton.decimals()))
                    .toList());
            FinalityPolicy finalityPolicy = new FinalityPolicy() {
                @Override
                public FinalityTarget observationTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("masterchain-depth", Long.toString(scanner.observationDepth()));
                }

                @Override
                public FinalityTarget finalityTarget(io.quieti.wallet.domain.chain.ChainRef chain) {
                return new FinalityTarget("masterchain-depth", Long.toString(scanner.finalityDepth()));
                }
            };
            ScanCycleRunner coordinator = coordinator(
                scannerProperties.shadowMode(),
                scanner.scannerId(),
                scanner.maxReorgDepth(),
                scanner.maxCycleRetries(),
                adapter,
                repository,
                finalityPolicy,
                shadowReporter);
            return new SupervisedScanner(
                adapter.chain(),
                scanner.scannerId(),
                scanner.pollInterval(),
                coordinator,
                adapter);
            }

    private static ScanCycleRunner coordinator(
            boolean shadowMode,
            String scannerId,
            long maxReorgDepth,
            int maxCycleRetries,
            io.quieti.wallet.application.scan.ChainScanAdapter adapter,
            JdbcScannerRepository repository,
            FinalityPolicy finalityPolicy,
            ShadowScanReporter shadowReporter) {
        if (shadowMode) {
            return new ShadowScanCoordinator(
                    scannerId,
                    maxReorgDepth,
                    adapter,
                    repository,
                    repository,
                    finalityPolicy,
                    shadowReporter);
        }
        return new ScanCoordinator(
                new ScanCoordinatorConfig(scannerId, maxReorgDepth, maxCycleRetries),
                adapter,
                repository,
                finalityPolicy);
    }

    @Bean(destroyMethod = "")
    ScannerSupervisor scannerSupervisor(List<SupervisedScanner> scanners) {
        ScannerSupervisor supervisor = new ScannerSupervisor((chain, scannerId, failure) ->
            LOGGER.error("Scanner cycle failed for {}/{}", chain, scannerId, failure));
        scanners.forEach(supervisor::add);
        return supervisor;
    }

    @Bean
    ScannerSupervisorLifecycle scannerSupervisorLifecycle(ScannerSupervisor supervisor) {
        return new ScannerSupervisorLifecycle(supervisor);
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