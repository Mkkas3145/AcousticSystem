package org.macaroon.acousticsystem.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.material.AcousticMaterialReloadListener;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;

public final class AcousticSystemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                AcousticSystem.id("acoustic_materials"),
                new AcousticMaterialReloadListener()
        );
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AcousticRuntime.shutdown());
        AcousticSystem.LOGGER.info(
                "Continuous client-side acoustic simulation enabled with {} background workers",
                AcousticRuntime.workerCount()
        );
    }
}
