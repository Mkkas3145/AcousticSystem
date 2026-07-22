package org.macaroon.acousticsystem.forge;

import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.AcousticSystemClient;
import org.macaroon.acousticsystem.client.material.AcousticMaterialReloadListener;

@Mod(AcousticSystem.MOD_ID)
public final class ForgeAcousticSystem {
    public ForgeAcousticSystem() {
        AcousticSystem.initialize();
        AcousticSystemClient.initialize(FMLPaths.CONFIGDIR.get());
        RegisterClientReloadListenersEvent.BUS.addListener(event ->
                event.registerReloadListener(new AcousticMaterialReloadListener())
        );
    }
}
