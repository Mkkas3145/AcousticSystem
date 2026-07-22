package org.macaroon.acousticsystem.fabric;

import net.fabricmc.api.ModInitializer;
import org.macaroon.acousticsystem.AcousticSystem;

public final class FabricAcousticSystem implements ModInitializer {
    @Override
    public void onInitialize() {
        AcousticSystem.initialize();
    }
}
