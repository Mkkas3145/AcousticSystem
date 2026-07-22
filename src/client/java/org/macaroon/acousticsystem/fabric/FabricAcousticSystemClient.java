package org.macaroon.acousticsystem.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.AcousticSystemClient;
import org.macaroon.acousticsystem.client.material.AcousticMaterialReloadListener;

public final class FabricAcousticSystemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AcousticSystemClient.initialize(FabricLoader.getInstance().getConfigDir());
        registerMaterialReloadListener(new AcousticMaterialReloadListener());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AcousticSystemClient.shutdown());
    }

    private static void registerMaterialReloadListener(PreparableReloadListener listener) {
        ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        try {
            try {
                ResourceLoader.class
                        .getMethod("registerReloadListener", Identifier.class,
                                PreparableReloadListener.class)
                        .invoke(loader, Identifier.fromNamespaceAndPath(
                                AcousticSystem.MOD_ID, "acoustic_materials"
                        ), listener);
            } catch (NoSuchMethodException ignored) {
                ResourceLoader.class
                        .getMethod("registerReloader", Identifier.class,
                                PreparableReloadListener.class)
                        .invoke(loader, Identifier.fromNamespaceAndPath(
                                AcousticSystem.MOD_ID, "acoustic_materials"
                        ), listener);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to register acoustic material reload listener", exception);
        }
    }
}
