package org.macaroon.acousticsystem.mixin.client;

import com.mojang.blaze3d.audio.Listener;
import com.mojang.blaze3d.audio.ListenerTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.simulation.AcousticRuntime;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Shadow
    @Final
    private ChannelAccess channelAccess;

    @Shadow
    @Final
    private SoundEngineExecutor executor;

    @Shadow
    @Final
    private Listener listener;

    @Shadow
    private boolean loaded;

    @Unique
    private final AtomicBoolean acousticsystem$listenerUpdateQueued = new AtomicBoolean();

    @Unique
    private volatile ListenerTransform acousticsystem$latestListenerTransform;

    @Inject(method = "play", at = @At("HEAD"))
    private void acousticsystem$prepareFirstFrame(
            SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir
    ) {
        AcousticRuntime.prepareSound(sound);
    }

    @Inject(method = "play", at = @At("RETURN"))
    private void acousticsystem$bindCompletedOnset(
            SoundInstance sound,
            CallbackInfoReturnable<SoundEngine.PlayResult> cir
    ) {
        AcousticRuntime.bindPreparedSound(sound, instanceToChannel.get(sound));
    }

    @Inject(method = "stopAll", at = @At("TAIL"))
    private void acousticsystem$resetCoalescedListenerQueue(CallbackInfo ci) {
        // stopAll drops every queued executor task before starting a new sound thread.
        // Release our ownership flag as well or a dropped task would suppress listener
        // updates permanently after a device/resource reload.
        acousticsystem$latestListenerTransform = null;
        acousticsystem$listenerUpdateQueued.set(false);
        AcousticRuntime.resetSoundThreadTransport();
    }

    @Inject(method = "updateSource", at = @At("HEAD"), cancellable = true)
    private void acousticsystem$coalesceListenerAndAcousticUpdate(
            Camera camera,
            CallbackInfo ci
    ) {
        // Replace vanilla's one-executor-task-per-render-frame submission. At uncapped
        // frame rates that queue can grow for seconds once acoustic reprojection is
        // added to every task. One pending task now owns both the vanilla OpenAL
        // transform and the acoustic field, while newer frames replace its payload.
        if (!loaded || !camera.isInitialized()) {
            ci.cancel();
            return;
        }
        ListenerTransform transform = new ListenerTransform(
                camera.position(),
                new Vec3(camera.forwardVector()),
                new Vec3(camera.upVector())
        );
        acousticsystem$latestListenerTransform = transform;
        AcousticRuntime.attachSoundExecutor(executor);
        acousticsystem$scheduleLatestListenerTransform();

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != null) {
            AcousticRuntime.tick(
                    level,
                    transform.position(),
                    instanceToChannel,
                    channelAccess
            );
        }
        ci.cancel();
    }

    @Unique
    private void acousticsystem$scheduleLatestListenerTransform() {
        if (!acousticsystem$listenerUpdateQueued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            ListenerTransform applied = acousticsystem$latestListenerTransform;
            try {
                if (applied != null) {
                    listener.setTransform(applied);
                    AcousticRuntime.applyListenerPosition(applied.position());
                }
                AcousticRuntime.drainCompletedApplicationsOnSoundThread();
            } finally {
                acousticsystem$listenerUpdateQueued.set(false);
            }
            if (applied != acousticsystem$latestListenerTransform) {
                acousticsystem$scheduleLatestListenerTransform();
            }
        });
    }
}
