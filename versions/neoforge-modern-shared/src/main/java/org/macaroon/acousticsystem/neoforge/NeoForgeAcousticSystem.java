package org.macaroon.acousticsystem.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.minecraft.resources.Identifier;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.AcousticSystemClient;
import org.macaroon.acousticsystem.client.material.AcousticMaterialReloadListener;

@Mod(AcousticSystem.MOD_ID)
public final class NeoForgeAcousticSystem {
    public NeoForgeAcousticSystem(IEventBus modBus) {
        AcousticSystem.initialize();
        AcousticSystemClient.initialize(FMLPaths.CONFIGDIR.get());
        modBus.addListener(this::registerReloadListeners);
    }

    private void registerReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(
                Identifier.fromNamespaceAndPath(AcousticSystem.MOD_ID, "acoustic_materials"),
                new AcousticMaterialReloadListener()
        );
    }
}
