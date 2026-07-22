package org.macaroon.acousticsystem.client;

import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;

import java.nio.file.Path;

public final class AcousticSystemClient {
    private AcousticSystemClient() {
    }

    public static void initialize(Path configDirectory) {
        AcousticQualityConfig.load(configDirectory);
        AcousticSystem.LOGGER.info(
                "Continuous client-side acoustic simulation enabled with {} background workers",
                AcousticRuntime.workerCount()
        );
    }

    public static void shutdown() {
        AcousticRuntime.shutdown();
    }
}
