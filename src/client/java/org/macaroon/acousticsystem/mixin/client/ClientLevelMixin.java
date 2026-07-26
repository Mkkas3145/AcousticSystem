package org.macaroon.acousticsystem.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.macaroon.acousticsystem.client.scene.AcousticSceneManager;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "playPlayerSound", at = @At("HEAD"))
    private void acousticsystem$beginPlayerSound(
            SoundEvent sound, SoundSource source, float volume, float pitch,
            CallbackInfo ci
    ) {
        AcousticRuntime.beginEntitySound(Minecraft.getInstance().player);
    }

    @Inject(method = "playPlayerSound", at = @At("RETURN"))
    private void acousticsystem$endPlayerSound(
            SoundEvent sound, SoundSource source, float volume, float pitch,
            CallbackInfo ci
    ) {
        AcousticRuntime.endEntitySound(Minecraft.getInstance().player);
    }

    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"))
    private void acousticsystem$beginLocalEntitySound(
            Entity entity, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci
    ) {
        AcousticRuntime.beginEntitySound(entity);
    }

    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("RETURN"))
    private void acousticsystem$endLocalEntitySound(
            Entity entity, SoundEvent sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci
    ) {
        AcousticRuntime.endEntitySound(entity);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void acousticsystem$beginBoundEntitySound(
            Entity except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci
    ) {
        AcousticRuntime.beginEntitySound(entity);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("RETURN"))
    private void acousticsystem$endBoundEntitySound(
            Entity except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci
    ) {
        AcousticRuntime.endEntitySound(entity);
    }
    /**
     * Level#setBlock invokes this virtual renderer hook after every real state change.
     * Server-verified command packets deliberately call Level#setBlock directly and
     * therefore bypass ClientLevel#setBlock, which was the old injection point.
     */
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void acousticsystem$invalidateSection(
            BlockPos pos,
            BlockState oldState,
            BlockState newState,
            CallbackInfo ci
    ) {
        if (oldState != newState) {
            AcousticSceneManager.markDirty(
                    (ClientLevel) (Object) this,
                    pos,
                    oldState,
                    newState
            );
        }
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void acousticsystem$invalidateLoadedChunk(ChunkPos pos, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        AcousticSceneManager.registerChunk(
                level,
                level.getChunk(
                        Math.floorDiv(pos.getMinBlockX(), 16),
                        Math.floorDiv(pos.getMinBlockZ(), 16)
                )
        );
        AcousticSceneManager.markChunkDirty(
                level,
                Math.floorDiv(pos.getMinBlockX(), 16),
                Math.floorDiv(pos.getMinBlockZ(), 16)
        );
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void acousticsystem$invalidateUnloadedChunk(LevelChunk chunk, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        AcousticSceneManager.unregisterChunk(level, chunk);
        AcousticSceneManager.markChunkDirty(
                level,
                Math.floorDiv(chunk.getPos().getMinBlockX(), 16),
                Math.floorDiv(chunk.getPos().getMinBlockZ(), 16)
        );
    }

}
