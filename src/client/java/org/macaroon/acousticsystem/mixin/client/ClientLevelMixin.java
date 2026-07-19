package org.macaroon.acousticsystem.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.macaroon.acousticsystem.client.scene.AcousticSceneManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void acousticsystem$invalidateSection(
            BlockPos pos,
            BlockState state,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValue()) {
            AcousticSceneManager.markDirty((ClientLevel) (Object) this, pos);
        }
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void acousticsystem$invalidateLoadedChunk(ChunkPos pos, CallbackInfo ci) {
        AcousticSceneManager.markChunkDirty((ClientLevel) (Object) this, pos.x(), pos.z());
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void acousticsystem$invalidateUnloadedChunk(LevelChunk chunk, CallbackInfo ci) {
        AcousticSceneManager.markChunkDirty(
                (ClientLevel) (Object) this,
                chunk.getPos().x(),
                chunk.getPos().z()
        );
    }

}
