package org.macaroon.acousticsystem.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.material.AcousticMaterialReloadListener;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;

public final class AcousticSystemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AcousticQualityConfig.load();
        registerMaterialReloadListener(new AcousticMaterialReloadListener());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AcousticRuntime.shutdown());
        AcousticSystem.LOGGER.info(
                "Continuous client-side acoustic simulation enabled with {} background workers",
                AcousticRuntime.workerCount()
        );
    }

    private static void registerMaterialReloadListener(PreparableReloadListener listener) {
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        try {
            // Fabric renamed this API after 1.21.11. Reflection keeps the shared
            // acoustic implementation identical while selecting the matching public
            // registration entry point for the installed Fabric API.
            try {
                ResourceLoader.class
                        .getMethod("registerReloadListener", net.minecraft.resources.Identifier.class,
                                PreparableReloadListener.class)
                        .invoke(loader, AcousticSystem.id("acoustic_materials"), listener);
            } catch (NoSuchMethodException ignored) {
                ResourceLoader.class
                        .getMethod("registerReloader", net.minecraft.resources.Identifier.class,
                                PreparableReloadListener.class)
                        .invoke(loader, AcousticSystem.id("acoustic_materials"), listener);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to register acoustic material reload listener", exception);
        }
    }
}
