package io.quieti.wallet.bootstrap.config;

import io.quieti.wallet.application.scan.ScannerSupervisor;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

public final class ScannerSupervisorLifecycle implements SmartLifecycle {

    private final ScannerSupervisor supervisor;
    private volatile boolean running;

    public ScannerSupervisorLifecycle(ScannerSupervisor supervisor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
    }

    @Override
    public void start() {
        supervisor.start();
        running = true;
    }

    @Override
    public void stop() {
        try {
            supervisor.close();
        } finally {
            running = false;
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}