package io.quieti.wallet.bootstrap;

import io.quieti.wallet.bootstrap.config.WalletProperties;
import io.quieti.wallet.bootstrap.config.WalletRpcProperties;
import io.quieti.wallet.bootstrap.config.WalletScannerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    WalletProperties.class,
    WalletRpcProperties.class,
    WalletScannerProperties.class
})
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}