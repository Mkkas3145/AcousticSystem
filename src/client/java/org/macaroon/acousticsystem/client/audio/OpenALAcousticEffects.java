package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.BufferUtils;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.simulation.AcousticResult;
import org.macaroon.acousticsystem.client.simulation.EarlyReflection;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;
import org.macaroon.acousticsystem.client.simulation.RoomImpulseResponse;
import org.macaroon.acousticsystem.client.simulation.RoomProbe;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.nio.FloatBuffer;

public final class OpenALAcousticEffects {
    private static final Map<Integer, SourceState> SOURCES = new HashMap<>();
    private static final Map<ReverbKey, ReverbBus> REVERB_BUSES = new HashMap<>();
    private static final List<ReverbBus> REVERB_POOL = new ArrayList<>();
    private static final Map<EarlyReflectionKey, EarlyReflectionBus> EARLY_REFLECTION_BUSES = new HashMap<>();
    private static final List<EarlyReflectionBus> EARLY_REFLECTION_POOL = new ArrayList<>();
    private static final ThreadLocal<FloatBuffer> LISTENER_ORIENTATION = ThreadLocal.withInitial(
            () -> BufferUtils.createFloatBuffer(6)
    );
    // One network receives the current listener field while recently retired networks
    // finish their own RT60. Four bounded slots cover rapid adjacent-space crossings
    // without running a convolution per source or retaining an unbounded room cache.
    private static final int MAX_REVERB_BUSES = 4;
    private static final int MAX_EARLY_REFLECTION_BUSES = 6;
    private static final RoomAcoustics DSP_PREWARM_ROOM = new RoomAcoustics(
            0.75F, 0.70F, 0.32F, 0.72F, 0.95F,
            1.8F, 0.65F, 1.15F,
            0.28F, 0.018F, Vec3.ZERO,
            1.2F, 0.032F, Vec3.ZERO,
            0.6F, 0.08F, 0.94F
    );
    private static boolean supportChecked;
    private static boolean supported;
    private static boolean advancedEaxReverb;
    private static boolean dedicatedSourceRoomSendSupported;
    private static ReverbBus activeReverbBus;
    private static RoomAcoustics smoothedListenerRoom;
    private static long lastListenerRoomUpdateNanoseconds;
    private static long lastListenerRoomSequence = Long.MIN_VALUE;
    private static Vec3 lastDynamicListenerPosition;
    private static long nextFieldToken;
    private static volatile List<TailFieldRequest> tailFieldRequests = List.of();

    private OpenALAcousticEffects() {
    }

    /** Builds the reusable EFX buses while Minecraft is initializing its audio library. */
    public static void initialize() {
        if (!ensureSupport()) {
            return;
        }
        int source = AL10.alGenSources();
        if (source == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
            return;
        }
        int buffer = 0;
        try {
            buffer = AL10.alGenBuffers();
            AL10.alBufferData(
                    buffer,
                    AL10.AL_FORMAT_MONO16,
                    BufferUtils.createShortBuffer(2048),
                    48_000
            );
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            applyBeforePlay(source, new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F,
                    EarlyReflection.SILENT, 0.0F,
                    0.0,
                    Vec3.ZERO,
                    RoomAcoustics.OUTDOORS,
                    RoomImpulseResponse.SILENT
            ));
            AL10.alSourcePlay(source);
            while (REVERB_POOL.size() < MAX_REVERB_BUSES) {
                createReverbBus(null, RoomAcoustics.OUTDOORS);
            }
            if (advancedEaxReverb) {
                while (EARLY_REFLECTION_POOL.size() < MAX_EARLY_REFLECTION_BUSES) {
                    createEarlyReflectionBus(null, EarlyReflection.SILENT, Vec3.ZERO);
                }
            }
            // OpenAL Soft may lazily instantiate the actual reverb network the first
            // time an audible room is committed to the slot. Exercise that transition
            // during library startup. The listener probe is the only runtime owner of
            // this shared room; individual voices only attach to it.
            updateListenerRoomInternal(RoomAcoustics.OUTDOORS, false);
            apply(source, new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F,
                    EarlyReflection.SILENT, 0.0F,
                    0.0, Vec3.ZERO, RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT
            ));
            updateListenerRoomInternal(DSP_PREWARM_ROOM, false);
            apply(source, new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F,
                    EarlyReflection.SILENT, 0.0F,
                    0.0, Vec3.ZERO, DSP_PREWARM_ROOM, RoomImpulseResponse.SILENT
            ));
            smoothedListenerRoom = null;
            lastListenerRoomUpdateNanoseconds = 0L;
            lastListenerRoomSequence = Long.MIN_VALUE;
        } finally {
            AL10.alSourceStop(source);
            releaseSource(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            if (buffer != 0) {
                AL10.alDeleteBuffers(buffer);
            }
            AL10.alDeleteSources(source);
        }
    }

    public static void apply(int source, AcousticResult target) {
        applyInternal(source, target, false);
    }

    /**
     * Atomically advances the shared listener field and this voice to one simulation
     * generation. Channel handles are independent command producers, so their OpenAL
     * callbacks are not assumed to arrive in submission order.
     */
    public static void applySequenced(int source, AcousticResult target, long sequence) {
        // Source callbacks own only source transport. The listener probe is delivered
        // independently once per geometry batch and is the sole owner of the global
        // room field. Letting every voice also commit that field made callback order,
        // rather than listener geometry, decide which reverb bus was active.
        applyInternal(source, target, false, sequence, true);
    }

    public static void applyBeforePlay(int source, AcousticResult target) {
        applyInternal(source, target, true);
    }

    /**
     * Introduces the first completed physical trace as the next response target. The
     * path-based onset predictor is already active, so the correction uses the same
     * real-dt dezippering as continuous movement instead of discontinuously replacing
     * all OpenAL coefficients.
     */
    public static void applyOnsetCorrection(int source, AcousticResult target) {
        applyInternal(source, target, false);
    }

    /**
     * Commits the listener-centred acoustic field. This must run on Minecraft's sound
     * thread. Keeping room ownership separate from voice updates prevents a stale or
     * failed source trace from replacing the shared late field for every playing sound.
     */
    public static void updateListenerRoom(RoomAcoustics room) {
        updateListenerRoomInternal(room, true);
    }

    public static void updateListenerRoom(RoomAcoustics room, long sequence) {
        updateListenerRoomInternal(room, true, sequence, true);
    }

    public static void updateListenerRoom(
            RoomProbe probe,
            Vec3 listener,
            long sequence
    ) {
        updateListenerRoomInternal(
                probe.acoustics(),
                true,
                sequence,
                true,
                ListenerFieldSnapshot.from(probe, listener)
        );
    }

    /**
     * Immutable cross-thread work list for diffuse fields whose source has stopped but
     * whose EFX network still contains audible energy. Geometry sampling stays on the
     * acoustic workers; this snapshot contains no OpenAL object references.
     */
    public static List<TailFieldRequest> tailFieldRequests() {
        return tailFieldRequests;
    }

    /** Applies a newly sampled aperture topology to one still-living reverb tail. */
    public static void updateTailField(
            long fieldToken,
            RoomProbe probe,
            Vec3 fieldPosition
    ) {
        if (!ensureSupport()) {
            return;
        }
        long now = System.nanoTime();
        for (ReverbBus bus : REVERB_POOL) {
            if (bus.fieldToken != fieldToken
                    || bus == activeReverbBus
                    || bus.references != 0
                    || bus.tailExpiresAtNanoseconds == 0L
                    || now >= bus.tailExpiresAtNanoseconds) {
                continue;
            }
            bus.field = ListenerFieldSnapshot.from(probe, fieldPosition);
            updateRetiredReverbOutputs(
                    now,
                    smoothedListenerRoom == null
                            ? RoomAcoustics.OUTDOORS
                            : smoothedListenerRoom,
                    lastDynamicListenerPosition
            );
            publishTailFieldRequests(now);
            return;
        }
    }

    /**
     * Applies listener motion to already measured room fields without waiting for the
     * next geometric probe. This only evaluates finite apertures and updates EFX gains
     * and pan, so it is safe to run for every observed listener position.
     */
    public static void updateListenerPosition(Vec3 listener) {
        if (!ensureSupport() || activeReverbBus == null || smoothedListenerRoom == null) {
            return;
        }
        if (lastDynamicListenerPosition != null
                && listener.distanceToSqr(lastDynamicListenerPosition) <= 1.0E-10) {
            return;
        }
        lastDynamicListenerPosition = listener;
        try {
            long now = System.nanoTime();
            PortalRadiation activeRadiation = activeReverbBus.field == null
                    ? PortalRadiation.INSIDE
                    : activeReverbBus.field.apertureRadiation(listener, true);
            float targetGain = clamp(smoothedListenerRoom.gain(), 0.0F, 1.0F);
            Vec3 pan = Vec3.ZERO;
            if (activeRadiation.outside()) {
                targetGain *= activeRadiation.transmission();
                pan = listenerRelativeVector(activeRadiation.arrivalDirection());
            }
            float amount = elapsedResponseAmount(
                    activeReverbBus.lastOutputGainUpdateNanoseconds, now
            );
            activeReverbBus.outputGain = lerp(
                    activeReverbBus.outputGain, targetGain, amount
            );
            EXTEfx.alAuxiliaryEffectSlotf(
                    activeReverbBus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(activeReverbBus.outputGain, 0.0F, 1.0F)
            );
            activeReverbBus.lastOutputGainUpdateNanoseconds = now;
            updateFastReverbProperties(
                    activeReverbBus, activeReverbBus.room, pan
            );
            updateRetiredReverbOutputs(now, smoothedListenerRoom, listener);
        } catch (RuntimeException exception) {
            AcousticSystem.LOGGER.warn(
                    "Could not apply the latest listener position to acoustic fields",
                    exception
            );
        }
    }

    /**
     * Establishes the listener field when the first positional voice starts. Once any
     * voice is active, onset predictions are not allowed to replace the shared field;
     * only the continuously sampled listener probe owns subsequent room changes.
     */
    public static void prepareListenerRoomForOnset(RoomAcoustics room) {
        if (!SOURCES.isEmpty()) {
            return;
        }
        updateListenerRoomInternal(room, false);
    }

    private static void updateListenerRoomInternal(RoomAcoustics room, boolean interpolate) {
        updateListenerRoomInternal(room, interpolate, Long.MIN_VALUE, false, null);
    }

    private static boolean updateListenerRoomInternal(
            RoomAcoustics room,
            boolean interpolate,
            long sequence,
            boolean ordered
    ) {
        return updateListenerRoomInternal(room, interpolate, sequence, ordered, null);
    }

    private static boolean updateListenerRoomInternal(
            RoomAcoustics room,
            boolean interpolate,
            long sequence,
            boolean ordered,
            ListenerFieldSnapshot measuredField
    ) {
        if (!ensureSupport()) {
            return false;
        }
        if (ordered) {
            if (sequence < lastListenerRoomSequence) {
                return false;
            }
            if (sequence == lastListenerRoomSequence) {
                return true;
            }
            lastListenerRoomSequence = sequence;
        }
        try {
            long now = System.nanoTime();
            if (!interpolate || smoothedListenerRoom == null) {
                smoothedListenerRoom = room;
            } else {
                float amount = elapsedResponseAmount(
                        lastListenerRoomUpdateNanoseconds,
                        now
                );
                smoothedListenerRoom = interpolateRoom(smoothedListenerRoom, room, amount);
            }
            lastListenerRoomUpdateNanoseconds = now;
            boolean crossedMeasuredAperture = measuredField == null
                    || activeReverbBus == null
                    || activeReverbBus.field == null
                    || activeReverbBus.field.apertureRadiation(
                            measuredField.listener(), true
                    ).outside();
            if (crossedMeasuredAperture) {
                updatePersistentReverbBus(
                        ReverbKey.from(room),
                        room
                );
            }
            if (measuredField != null) {
                activeReverbBus.field = measuredField;
                activeReverbBus.fieldToken = ++nextFieldToken;
            }
            activeReverbBus.room = room;
            Vec3 observation = lastDynamicListenerPosition != null
                    ? lastDynamicListenerPosition
                    : measuredField == null ? null : measuredField.listener();
            PortalRadiation activeRadiation = observation == null
                    || activeReverbBus.field == null
                    ? PortalRadiation.INSIDE
                    : activeReverbBus.field.apertureRadiation(observation, true);
            Vec3 activePan = activeRadiation.outside()
                    ? listenerRelativeVector(activeRadiation.arrivalDirection())
                    : Vec3.ZERO;
            updateFastReverbProperties(
                    activeReverbBus, room, activePan
            );
            float activeTargetGain = clamp(
                    smoothedListenerRoom.gain()
                            * (activeRadiation.outside()
                            ? activeRadiation.transmission() : 1.0F),
                    0.0F, 1.0F
            );
            float activeAmount = elapsedResponseAmount(
                    activeReverbBus.lastOutputGainUpdateNanoseconds, now
            );
            activeReverbBus.outputGain = lerp(
                    activeReverbBus.outputGain, activeTargetGain, activeAmount
            );
            EXTEfx.alAuxiliaryEffectSlotf(
                    activeReverbBus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(activeReverbBus.outputGain, 0.0F, 1.0F)
            );
            activeReverbBus.lastOutputGainUpdateNanoseconds = now;
            updateRetiredReverbOutputs(
                    now,
                    smoothedListenerRoom,
                    observation
            );
            return true;
        } catch (RuntimeException exception) {
            AcousticSystem.LOGGER.warn(
                    "Could not update the listener acoustic field; retaining the previous room",
                    exception
            );
            return false;
        }
    }

    private static void applyInternal(
            int source,
            AcousticResult target,
            boolean snapToTarget
    ) {
        applyInternal(source, target, snapToTarget, Long.MIN_VALUE, false);
    }

    private static void applyInternal(
            int source,
            AcousticResult target,
            boolean snapToTarget,
            long sequence,
            boolean ordered
    ) {
        if (!ensureSupport()) {
            return;
        }

        try {
            SourceState state = SOURCES.computeIfAbsent(source, OpenALAcousticEffects::createSourceState);
            if (!state.update(target, snapToTarget, sequence, ordered)) {
                return;
            }
            assignReverbBus(state);
            assignSourceRoomBus(state);

            applyPositionalDirectPath(source, state);
            AL10.alSource3f(source, AL10.AL_POSITION,
                    (float) state.apparentPosition.x,
                    (float) state.apparentPosition.y,
                    (float) state.apparentPosition.z);
        } catch (RuntimeException exception) {
            recoverSourceAfterFailure(source, exception);
        }
    }

    public static void releaseSource(int source) {
        SourceState state = SOURCES.remove(source);
        if (state == null || !supported) {
            return;
        }
        detachSource(source);
        deleteSourceState(state);
    }

    public static void shutdown() {
        if (supported) {
            for (Map.Entry<Integer, SourceState> entry : SOURCES.entrySet()) {
                detachSource(entry.getKey());
                deleteSourceState(entry.getValue());
            }
            for (ReverbBus bus : REVERB_POOL) {
                deleteReverbBus(bus);
            }
            for (EarlyReflectionBus bus : EARLY_REFLECTION_POOL) {
                deleteEarlyReflectionBus(bus);
            }
        }
        resetState();
    }

    private static void applyPositionalDirectPath(int source, SourceState state) {
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAIN, clamp(state.directGain, 0.0F, 1.0F));
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAINHF, clamp(state.highFrequencyGain, 0.0F, 1.0F));
        updateReverbFilter(state);
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, state.directFilter);
        if (advancedEaxReverb) {
            boolean remoteFieldUsesFirstSend = state.sourceRoomBus != null
                    && !dedicatedSourceRoomSendSupported;
            if (remoteFieldUsesFirstSend) {
                attachSourceRoomSend(source, state, 0);
            } else {
                updateEarlyReflectionSend(source, state);
            }
            attachLateReverbSends(source, state);
        } else {
            attachLateReverbSends(source, state);
        }
    }

    /**
     * Owns every late-send routing decision. Listener-room commits and per-source
     * results both call this method, so asynchronous callbacks cannot alternately
     * overwrite send 1 with two different fields on two-send OpenAL devices.
     */
    private static void attachLateReverbSends(int source, SourceState state) {
        if (!advancedEaxReverb) {
            state.primaryLateBus = state.reverbBus;
            AL11.alSource3i(
                    source,
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    state.reverbBus.slot,
                    0,
                    state.reverbFilter
            );
            return;
        }

        // Send 1 has one permanent meaning on every advanced device: the room around
        // the listener. Replacing it with a remote source room made the perceived
        // reverb algorithm change whenever a source crossed a portal or stopped.
        state.primaryLateBus = state.reverbBus;
        AL11.alSource3i(
                source,
                EXTEfx.AL_AUXILIARY_SEND_FILTER,
                state.reverbBus.slot,
                1,
                state.reverbFilter
        );

        if (!dedicatedSourceRoomSendSupported) {
            return;
        }
        if (state.sourceRoomBus != null) {
            attachSourceRoomSend(source, state, 2);
        } else {
            AL11.alSource3i(
                    source,
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL,
                    2,
                    EXTEfx.AL_FILTER_NULL
            );
        }
    }

    private static void attachSourceRoomSend(
            int source,
            SourceState state,
            int sendIndex
    ) {
        updateSourceRoomFilter(state);
        AL11.alSource3i(
                source,
                EXTEfx.AL_AUXILIARY_SEND_FILTER,
                state.sourceRoomBus.slot,
                sendIndex,
                state.sourceRoomFilter
        );
    }

    private static void updateSourceRoomFilter(SourceState state) {
        EXTEfx.alFilterf(
                state.sourceRoomFilter,
                EXTEfx.AL_LOWPASS_GAIN,
                clamp(state.sourceRoomSend, 0.0F, 1.0F)
        );
        EXTEfx.alFilterf(
                state.sourceRoomFilter,
                EXTEfx.AL_LOWPASS_GAINHF,
                clamp(state.sourceRoomHighFrequency, 0.0F, 1.0F)
        );
    }

    private static void updateReverbFilter(SourceState state) {
        EXTEfx.alFilterf(state.reverbFilter, EXTEfx.AL_LOWPASS_GAIN, clamp(state.reverbSend, 0.0F, 1.0F));
        float roomHighFrequency = smoothedListenerRoom == null
                ? 1.0F
                : smoothedListenerRoom.gainHighFrequency();
        EXTEfx.alFilterf(
                state.reverbFilter,
                EXTEfx.AL_LOWPASS_GAINHF,
                clamp(state.reverbHighFrequency * roomHighFrequency, 0.0F, 1.0F)
        );
    }

    private static void updateEarlyReflectionSend(int source, SourceState state) {
        EarlyReflection reflection = state.earlyReflection;
        if (reflection.gain() <= 1.0E-5F) {
            releaseEarlyReflectionBus(state);
            AL11.alSource3i(
                    source,
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL,
                    0,
                    EXTEfx.AL_FILTER_NULL
            );
            return;
        }

        Vec3 listenerPan = listenerRelativeDirection(reflection.arrivalDirection());
        EarlyReflectionKey key = EarlyReflectionKey.from(reflection, listenerPan);
        assignEarlyReflectionBus(state, key, reflection, listenerPan);
        EXTEfx.alFilterf(
                state.earlyReflectionFilter,
                EXTEfx.AL_LOWPASS_GAIN,
                reflection.gain()
        );
        EXTEfx.alFilterf(
                state.earlyReflectionFilter,
                EXTEfx.AL_LOWPASS_GAINHF,
                reflection.highFrequencyGain()
        );
        AL11.alSource3i(
                source,
                EXTEfx.AL_AUXILIARY_SEND_FILTER,
                state.earlyReflectionBus.slot,
                0,
                state.earlyReflectionFilter
        );
    }

    private static Vec3 listenerRelativeDirection(Vec3 worldDirection) {
        if (worldDirection.lengthSqr() <= 1.0E-12) {
            return Vec3.ZERO;
        }
        FloatBuffer orientation = LISTENER_ORIENTATION.get();
        orientation.clear();
        AL10.alGetListenerfv(AL10.AL_ORIENTATION, orientation);
        Vec3 forward = new Vec3(
                orientation.get(0), orientation.get(1), orientation.get(2)
        );
        Vec3 up = new Vec3(
                orientation.get(3), orientation.get(4), orientation.get(5)
        );
        if (forward.lengthSqr() <= 1.0E-10 || up.lengthSqr() <= 1.0E-10) {
            return worldDirection.normalize();
        }
        forward = forward.normalize();
        up = up.normalize();
        Vec3 right = forward.cross(up);
        if (right.lengthSqr() <= 1.0E-10) {
            return worldDirection.normalize();
        }
        right = right.normalize();
        Vec3 direction = worldDirection.normalize();
        Vec3 local = new Vec3(
                direction.dot(right),
                direction.dot(up),
                -direction.dot(forward)
        );
        return local.lengthSqr() <= 1.0E-12 ? Vec3.ZERO : local.normalize();
    }

    private static Vec3 listenerRelativeVector(Vec3 worldVector) {
        double magnitude = worldVector.length();
        if (magnitude <= 1.0E-12) {
            return Vec3.ZERO;
        }
        return listenerRelativeDirection(worldVector).scale(Math.min(1.0, magnitude));
    }

    private static boolean ensureSupport() {
        if (supportChecked) {
            return supported;
        }
        supportChecked = true;
        long context = ALC10.alcGetCurrentContext();
        long device = context == 0L ? 0L : ALC10.alcGetContextsDevice(context);
        supported = device != 0L && ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
        if (!supported) {
            AcousticSystem.LOGGER.warn("OpenAL EFX is unavailable; acoustic DSP is disabled for this audio device");
            return false;
        }

        int maximumSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
        advancedEaxReverb = maximumSends >= 2;
        dedicatedSourceRoomSendSupported = maximumSends >= 3;
        if (advancedEaxReverb) {
            AcousticSystem.LOGGER.info("Unified OpenAL EFX acoustic pipeline enabled with positional direct filtering, EAX reverb and {} auxiliary sends", maximumSends);
            if (!dedicatedSourceRoomSendSupported) {
                AcousticSystem.LOGGER.info("Two-send routing enabled: listener late field remains persistent while remote fields use the first send");
            }
        } else {
            AcousticSystem.LOGGER.info("Unified OpenAL EFX acoustic pipeline enabled in compatible two-parameter mode ({} auxiliary send)", maximumSends);
        }
        AcousticSystem.LOGGER.info("Real-time calculated EAX reverb enabled");
        return true;
    }

    private static SourceState createSourceState(int source) {
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
        AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO, AL10.AL_FALSE);
        AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
        int directFilter = createLowPass(1.0F, 1.0F);
        int reverbFilter = createLowPass(0.04F, 1.0F);
        int sourceRoomFilter = advancedEaxReverb
                ? createLowPass(0.04F, 1.0F)
                : 0;
        int earlyReflectionFilter = advancedEaxReverb
                ? createLowPass(0.0F, 1.0F)
                : 0;
        return new SourceState(
                directFilter, reverbFilter, sourceRoomFilter,
                earlyReflectionFilter
        );
    }

    private static int createLowPass(float gain, float highFrequencyGain) {
        int filter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, highFrequencyGain);
        return filter;
    }

    private static void assignReverbBus(SourceState state) {
        ReverbBus shared = currentReverbBus();
        if (state.reverbBus == shared) {
            return;
        }
        if (state.reverbBus != null) {
            state.reverbBus.references--;
            // Stop feeding the previous field but do not mute its output. The EAX
            // network already contains the energy emitted before this handover and must
            // release it according to its calculated RT60. Muting here cut every short
            // sound's reverb tail at the next (~0.1 s) asynchronous room update.
        }
        state.reverbBus = shared;
        shared.references++;
        shared.lastUsed = System.nanoTime();
    }

    private static void assignSourceRoomBus(SourceState state) {
        if (!advancedEaxReverb
                || state.sourceRoomProbe == null
                || state.sourcePosition == null
                || state.sourceRoomProbe.acoustics().gain() <= 1.0E-6F) {
            releaseSourceRoomBus(state);
            return;
        }
        ReverbKey key = ReverbKey.from(state.sourceRoomProbe.acoustics());
        if (activeReverbBus != null && key.equals(activeReverbBus.key)) {
            // The listener and emitter share the same diffuse field. Feeding both sends
            // would count the same room energy twice.
            releaseSourceRoomBus(state);
            return;
        }

        ReverbBus next = acquireSourceRoomBus(key, state.sourceRoomProbe.acoustics());
        if (next == null) {
            // Keep the current assignment until a finished tail becomes recyclable;
            // losing a whole field is more audible than one delayed topology update.
            return;
        }
        if (state.sourceRoomBus != next) {
            releaseSourceRoomBus(state);
            state.sourceRoomBus = next;
            next.references++;
            next.sourceReferences++;
        }
        next.activate(System.nanoTime());
        next.sourceField = true;
        next.room = state.sourceRoomProbe.acoustics();
        next.field = ListenerFieldSnapshot.from(
                state.sourceRoomProbe, state.sourcePosition
        );
        next.fieldToken = ++nextFieldToken;
    }

    private static ReverbBus acquireSourceRoomBus(
            ReverbKey key,
            RoomAcoustics room
    ) {
        ReverbBus existing = REVERB_BUSES.get(key);
        if (existing != null) {
            if (existing != activeReverbBus
                    && existing.references == 0
                    && existing.canRecycle(System.nanoTime())) {
                configureReverbBus(existing, key, room);
            }
            return existing;
        }
        long now = System.nanoTime();
        ReverbBus available = REVERB_POOL.stream()
                .filter(candidate -> candidate != activeReverbBus && candidate.canRecycle(now))
                .min(Comparator.comparingLong(candidate -> candidate.lastUsed))
                .orElse(null);
        if (available == null && REVERB_POOL.size() < MAX_REVERB_BUSES) {
            available = createReverbBus(null, RoomAcoustics.OUTDOORS);
        }
        if (available == null) {
            return null;
        }
        if (available.key != null) {
            REVERB_BUSES.remove(available.key, available);
        }
        configureReverbBus(available, key, room);
        REVERB_BUSES.put(key, available);
        return available;
    }

    private static void releaseSourceRoomBus(SourceState state) {
        if (state.sourceRoomBus == null) {
            return;
        }
        ReverbBus previous = state.sourceRoomBus;
        state.sourceRoomBus = null;
        previous.references--;
        previous.sourceReferences--;
        if (previous.references == 0 && previous != activeReverbBus) {
            previous.retire(System.nanoTime());
            publishTailFieldRequests(System.nanoTime());
        }
    }

    private static void assignEarlyReflectionBus(
            SourceState state,
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan
    ) {
        if (state.earlyReflectionBus != null
                && state.earlyReflectionBus.key != null
                && state.earlyReflectionBus.key.equals(key)) {
            return;
        }
        EarlyReflectionBus next = acquireEarlyReflectionBus(
                key, reflection, listenerPan
        );
        if (state.earlyReflectionBus == next) {
            return;
        }
        releaseEarlyReflectionBus(state);
        state.earlyReflectionBus = next;
        next.references++;
        next.lastUsed = System.nanoTime();
    }

    private static void releaseEarlyReflectionBus(SourceState state) {
        if (state.earlyReflectionBus == null) {
            return;
        }
        state.earlyReflectionBus.references--;
        state.earlyReflectionBus.lastUsed = System.nanoTime();
        state.earlyReflectionBus = null;
    }

    private static EarlyReflectionBus acquireEarlyReflectionBus(
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan
    ) {
        EarlyReflectionBus exact = EARLY_REFLECTION_BUSES.get(key);
        if (exact != null) {
            return exact;
        }
        EarlyReflectionBus replaceable = EARLY_REFLECTION_POOL.stream()
                .filter(bus -> bus.references == 0)
                .min(Comparator.comparingLong(bus -> bus.lastUsed))
                .orElse(null);
        if (replaceable != null) {
            if (replaceable.key != null) {
                EARLY_REFLECTION_BUSES.remove(replaceable.key, replaceable);
            }
            configureEarlyReflectionBus(
                    replaceable, key, reflection, listenerPan
            );
            EARLY_REFLECTION_BUSES.put(key, replaceable);
            return replaceable;
        }
        return EARLY_REFLECTION_POOL.stream()
                .min(Comparator.comparingDouble(bus ->
                        bus.key == null
                                ? Double.POSITIVE_INFINITY
                                : bus.key.distanceTo(key)
                ))
                .orElseThrow();
    }

    private static ReverbBus currentReverbBus() {
        if (activeReverbBus != null) {
            return activeReverbBus;
        }
        ReverbKey key = ReverbKey.from(DSP_PREWARM_ROOM);
        ReverbBus existing = REVERB_BUSES.get(key);
        if (existing == null) {
            existing = createReverbBus(key, DSP_PREWARM_ROOM);
            REVERB_BUSES.put(key, existing);
        }
        activeReverbBus = existing;
        return existing;
    }

    private static void updatePersistentReverbBus(
            ReverbKey key,
            RoomAcoustics room
    ) {
        ReverbBus previous = currentReverbBus();
        if (key.equals(previous.key)) {
            return;
        }

        long now = System.nanoTime();
        ReverbBus next = REVERB_BUSES.get(key);
        if (next != null
                && next != previous
                && next.tailExpiresAtNanoseconds != 0L
                && !next.canRecycle(now)) {
            // Do not turn a previously attenuated tail back into the active room merely
            // because a boundary probe revisited the same quantized key. That exposes
            // stored energy at full slot gain and sounds like reverb disappearing and
            // returning. A genuinely free bus starts a new field; otherwise the current
            // continuous field remains in charge until one is available.
            next = null;
        } else if (next != null
                && next != previous
                && next.tailExpiresAtNanoseconds != 0L) {
            // The previous contents are inaudible or expired. Reinitialize the effect
            // and its output gain before making it active; otherwise a recycled 0-gain
            // slot remains silent until some later movement happens to advance it.
            configureReverbBus(next, key, room);
        }
        if (next == null) {
            next = REVERB_POOL.stream()
                    .filter(candidate -> candidate != previous && candidate.canRecycle(now))
                    .min(Comparator.comparingLong(candidate -> candidate.lastUsed))
                    .orElse(null);
            if (next == null && REVERB_POOL.size() < MAX_REVERB_BUSES) {
                next = createReverbBus(null, RoomAcoustics.OUTDOORS);
            }
            if (next == null) {
                // This can only occur while a just-destroyed OpenAL voice has not yet
                // released its state. Retain the current, valid field for this callback;
                // the continuously running next result will retry the handover.
                return;
            }
            if (next.key != null) {
                REVERB_BUSES.remove(next.key, next);
            }
            configureReverbBus(next, key, room);
            REVERB_BUSES.put(key, next);
        }

        activeReverbBus = next;
        next.activate(now);
        // Move every live voice in the same sound-thread transaction. The old field is
        // no longer fed but its slot stays audible, so its stored energy decays with the
        // RT60 measured in the previous space instead of being cut or frozen.
        for (Map.Entry<Integer, SourceState> entry : SOURCES.entrySet()) {
            SourceState state = entry.getValue();
            assignReverbBus(state);
            attachLateReverbSends(entry.getKey(), state);
        }
        previous.retire(now);
        publishTailFieldRequests(now);
    }

    /**
     * A retired EFX network still contains the diffuse energy generated in its former
     * room. Its output, however, is listener-relative: leaving that room must therefore
     * change how much of the stored field reaches the listener or the old room appears
     * to travel outdoors with them. The overlap below is the normalized inner product
     * of two exponentially decaying, three-band diffuse fields. It stays one for small
     * coefficient updates inside one space and tends continuously to zero as the new
     * listener field becomes open or acoustically unrelated.
     */
    private static void updateRetiredReverbOutputs(
            long now,
            RoomAcoustics listenerRoom,
            Vec3 listener
    ) {
        for (ReverbBus bus : REVERB_POOL) {
            if (bus == activeReverbBus
                    || (bus.references == 0 && bus.tailExpiresAtNanoseconds == 0L)) {
                continue;
            }
            // Once input has left this network, its stored acoustic energy can only
            // decay. A noisy doorway probe must not fade an old tail out and then make
            // that same stored response swell back in on the next result.
            PortalRadiation radiation = bus.field == null || listener == null
                    ? PortalRadiation.NONE
                    : bus.field.apertureRadiation(listener, false);
            float transportedGain = !radiation.outside() && bus.sourceField
                    ? clamp(bus.room.gain(), 0.0F, 1.0F)
                    : transportedDiffuseFieldGain(
                            bus.room,
                            listenerRoom,
                            radiation.transmission(),
                            radiation.outside()
                    );
            float targetGain;
            if (bus.references > 0) {
                // A live source is still injecting energy into this remote room field,
                // so approaching its aperture may physically increase the received level.
                targetGain = transportedGain;
            } else if (now >= bus.tailExpiresAtNanoseconds) {
                targetGain = 0.0F;
            } else {
                targetGain = Math.min(bus.outputGain, transportedGain);
            }
            float amount = elapsedResponseAmount(
                    bus.lastOutputGainUpdateNanoseconds,
                    now
            );
            bus.outputGain = lerp(bus.outputGain, targetGain, amount);
            if (targetGain == 0.0F && bus.outputGain < 1.0E-4F) {
                bus.outputGain = 0.0F;
            }
            EXTEfx.alAuxiliaryEffectSlotf(
                    bus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(bus.outputGain, 0.0F, 1.0F)
            );
            bus.lastOutputGainUpdateNanoseconds = now;
            if (advancedEaxReverb) {
                updateFastReverbProperties(
                        bus,
                        bus.room,
                        listenerRelativeVector(radiation.arrivalDirection())
                );
            }
        }
        publishTailFieldRequests(now);
    }

    private static void publishTailFieldRequests(long now) {
        List<TailFieldRequest> requests = new ArrayList<>(MAX_REVERB_BUSES);
        for (ReverbBus bus : REVERB_POOL) {
            if (bus == activeReverbBus
                    || bus.references != 0
                    || bus.tailExpiresAtNanoseconds == 0L
                    || now >= bus.tailExpiresAtNanoseconds
                    || bus.field == null) {
                continue;
            }
            requests.add(new TailFieldRequest(
                    bus.fieldToken,
                    bus.field.listener(),
                    bus.sourceField
            ));
        }
        tailFieldRequests = List.copyOf(requests);
    }

    static float diffuseFieldOverlapGain(RoomAcoustics stored, RoomAcoustics observed) {
        float storedGain = clamp(stored.gain(), 0.0F, 1.0F);
        float observedGain = clamp(observed.gain(), 0.0F, 1.0F);
        if (storedGain <= 1.0E-6F || observedGain <= 1.0E-6F) {
            return 0.0F;
        }

        double[] storedDecay = diffuseDecayRates(stored);
        double[] observedDecay = diffuseDecayRates(observed);
        double[] storedSpectrum = diffuseSpectrum(stored);
        double[] observedSpectrum = diffuseSpectrum(observed);
        double dot = 0.0;
        double storedEnergy = 0.0;
        double observedEnergy = 0.0;
        for (int band = 0; band < storedDecay.length; band++) {
            double cross = storedSpectrum[band] * observedSpectrum[band]
                    / (storedDecay[band] + observedDecay[band]);
            dot += cross;
            storedEnergy += storedSpectrum[band] * storedSpectrum[band]
                    / (2.0 * storedDecay[band]);
            observedEnergy += observedSpectrum[band] * observedSpectrum[band]
                    / (2.0 * observedDecay[band]);
        }
        double normalizedOverlap = dot / Math.sqrt(storedEnergy * observedEnergy);
        double levelCoupling = Math.sqrt(observedGain / storedGain);
        return clamp(
                (float) (storedGain * normalizedOverlap * levelCoupling),
                0.0F,
                storedGain
        );
    }

    static float retiredDiffuseFieldGain(
            float currentGain,
            RoomAcoustics stored,
            RoomAcoustics observed
    ) {
        return retiredDiffuseFieldGain(
                currentGain, stored, observed, 0.0F
        );
    }

    private static float retiredDiffuseFieldGain(
            float currentGain,
            RoomAcoustics stored,
            RoomAcoustics observed,
            float apertureTransmission
    ) {
        return Math.min(
                clamp(currentGain, 0.0F, 1.0F),
                transportedDiffuseFieldGain(
                        stored, observed, apertureTransmission, false
                )
        );
    }

    static float transportedDiffuseFieldGain(
            RoomAcoustics stored,
            RoomAcoustics observed,
            float apertureTransmission,
            boolean outsideAperture
    ) {
        if (outsideAperture) {
            // Once the listener crosses the measured aperture plane, transport is set
            // by that finite aperture alone. The listener-room probe can remain
            // numerically similar to the old room for another background completion;
            // taking max(overlap, aperture) held the tail at full level until that
            // categorical probe change and then cut it in one audible step.
            return clamp(
                    stored.gain()
                            * clamp(apertureTransmission, 0.0F, 1.0F),
                    0.0F,
                    1.0F
            );
        }
        return diffuseFieldOverlapGain(stored, observed);
    }

    private static double[] diffuseDecayRates(RoomAcoustics room) {
        double rt60 = Math.max(0.1, room.decayTime());
        return new double[]{
                3.0 * Math.log(10.0) / Math.max(0.1, rt60 * room.decayLowFrequencyRatio()),
                3.0 * Math.log(10.0) / rt60,
                3.0 * Math.log(10.0) / Math.max(0.1, rt60 * room.decayHighFrequencyRatio())
        };
    }

    private static double[] diffuseSpectrum(RoomAcoustics room) {
        return new double[]{
                Math.max(1.0E-4, room.gainLowFrequency()),
                1.0,
                Math.max(1.0E-4, room.gainHighFrequency())
        };
    }

    private static ReverbBus createReverbBus(
            ReverbKey key,
            RoomAcoustics room
    ) {
        int effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE,
                advancedEaxReverb ? EXTEfx.AL_EFFECT_EAXREVERB : EXTEfx.AL_EFFECT_REVERB);
        if (advancedEaxReverb) {
            updateEaxReverb(effect, room);
        } else {
            updateStandardReverb(effect, room);
        }
        int slot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(
                slot,
                EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO,
                AL10.AL_FALSE
        );
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
        ReverbBus bus = new ReverbBus(key, room, effect, slot);
        REVERB_POOL.add(bus);
        return bus;
    }

    private static void configureReverbBus(
            ReverbBus bus,
            ReverbKey key,
            RoomAcoustics room
    ) {
        bus.key = key;
        bus.room = room;
        bus.sourceField = false;
        bus.field = null;
        bus.fieldToken = ++nextFieldToken;
        if (advancedEaxReverb) {
            updateEaxReverb(bus.effect, room);
        } else {
            updateStandardReverb(bus.effect, room);
        }
        // EFX effect objects are templates. Parameter changes are not propagated to
        // the DSP instance already copied into an auxiliary slot until it is assigned
        // again. Without this refresh the prewarmed shared bus stayed OUTDOORS.
        EXTEfx.alAuxiliaryEffectSloti(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_EFFECT,
                bus.effect
        );
        bus.lastUsed = System.nanoTime();
        bus.tailExpiresAtNanoseconds = 0L;
        bus.fastKey = null;
        bus.outputGain = clamp(room.gain(), 0.0F, 1.0F);
        bus.lastOutputGainUpdateNanoseconds = bus.lastUsed;
        EXTEfx.alAuxiliaryEffectSlotf(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_GAIN,
                bus.outputGain
        );
    }

    private static EarlyReflectionBus createEarlyReflectionBus(
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan
    ) {
        int effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(
                effect,
                EXTEfx.AL_EFFECT_TYPE,
                EXTEfx.AL_EFFECT_EAXREVERB
        );
        int slot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(
                slot,
                EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO,
                AL10.AL_FALSE
        );
        EarlyReflectionBus bus = new EarlyReflectionBus(key, effect, slot);
        configureEarlyReflectionBus(bus, key, reflection, listenerPan);
        EARLY_REFLECTION_POOL.add(bus);
        return bus;
    }

    private static void configureEarlyReflectionBus(
            EarlyReflectionBus bus,
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan
    ) {
        bus.key = key;
        int effect = bus.effect;
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, 1.0F);
        EXTEfx.alEffectf(
                effect,
                EXTEfx.AL_EAXREVERB_GAINHF,
                reflection.highFrequencyGain()
        );
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINLF, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, 0.1F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 1.0F);
        EXTEfx.alEffectf(
                effect,
                EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY,
                reflection.delay()
        );
        EXTEfx.alEffectfv(
                effect,
                EXTEfx.AL_EAXREVERB_REFLECTIONS_PAN,
                vector(listenerPan)
        );
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, 0.0F);
        EXTEfx.alEffectfv(
                effect,
                EXTEfx.AL_EAXREVERB_LATE_REVERB_PAN,
                vector(Vec3.ZERO)
        );
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_MODULATION_DEPTH, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0.0F);
        EXTEfx.alEffecti(effect, EXTEfx.AL_EAXREVERB_DECAY_HFLIMIT, AL10.AL_FALSE);
        EXTEfx.alAuxiliaryEffectSloti(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_EFFECT,
                effect
        );
        EXTEfx.alAuxiliaryEffectSlotf(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_GAIN,
                1.0F
        );
        bus.lastUsed = System.nanoTime();
    }

    private static void updateEaxReverb(int reverbEffect, RoomAcoustics room) {
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DENSITY, clamp(room.density(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DIFFUSION, clamp(room.diffusion(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_GAIN, 1.0F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_GAINHF, clamp(room.gainHighFrequency(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_GAINLF, clamp(room.gainLowFrequency(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_TIME, clamp(room.decayTime(), 0.1F, 20.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, clamp(room.decayHighFrequencyRatio(), 0.1F, 2.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, clamp(room.decayLowFrequencyRatio(), 0.1F, 2.0F));
        // First-order arrivals are source-owned and use send 0. Keeping them in this
        // shared field would pan every voice toward the last source that updated it.
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 0.0F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, clamp(room.reflectionsDelay(), 0.0F, 0.3F));
        EXTEfx.alEffectfv(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_PAN, vector(Vec3.ZERO));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, clamp(room.lateReverbGain(), 0.0F, 10.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, clamp(room.lateReverbDelay(), 0.0F, 0.1F));
        EXTEfx.alEffectfv(reverbEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_PAN, vector(room.lateReverbPan()));
        // The fallback cannot represent traced, non-periodic arrivals. Its single
        // periodic echo tap stays off instead of inventing a fixed duplicate.
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_ECHO_TIME, 0.25F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, 0.0F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_MODULATION_TIME, clamp(room.modulationTime(), 0.04F, 4.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_MODULATION_DEPTH, clamp(room.modulationDepth(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF,
                clamp(room.airAbsorptionGainHighFrequency(), 0.892F, 1.0F));
        EXTEfx.alEffectf(
                reverbEffect,
                EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR,
                0.0F
        );
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_HFREFERENCE, 5000.0F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_LFREFERENCE, 250.0F);
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EAXREVERB_DECAY_HFLIMIT, AL10.AL_TRUE);
    }

    private static void updateFastReverbProperties(
            ReverbBus bus,
            RoomAcoustics room,
            Vec3 latePan
    ) {
        FastReverbKey nextKey = FastReverbKey.from(room, latePan);
        if (nextKey.equals(bus.fastKey)) {
            return;
        }
        if (advancedEaxReverb) {
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_GAINHF,
                    clamp(room.gainHighFrequency(), 0.0F, 1.0F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_GAINLF,
                    clamp(room.gainLowFrequency(), 0.0F, 1.0F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY,
                    clamp(room.reflectionsDelay(), 0.0F, 0.3F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN,
                    clamp(room.lateReverbGain(), 0.0F, 10.0F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY,
                    clamp(room.lateReverbDelay(), 0.0F, 0.1F)
            );
            EXTEfx.alEffectfv(
                    bus.effect,
                    EXTEfx.AL_EAXREVERB_LATE_REVERB_PAN,
                    vector(latePan)
            );
        } else {
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_REVERB_GAINHF,
                    clamp(room.gainHighFrequency(), 0.0F, 1.0F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_REVERB_REFLECTIONS_GAIN,
                    clamp(room.reflectionsGain(), 0.0F, 3.16F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_REVERB_REFLECTIONS_DELAY,
                    clamp(room.reflectionsDelay(), 0.0F, 0.3F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_REVERB_LATE_REVERB_GAIN,
                    clamp(room.lateReverbGain(), 0.0F, 10.0F)
            );
            EXTEfx.alEffectf(
                    bus.effect,
                    EXTEfx.AL_REVERB_LATE_REVERB_DELAY,
                    clamp(room.lateReverbDelay(), 0.0F, 0.1F)
            );
        }
        EXTEfx.alAuxiliaryEffectSloti(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_EFFECT,
                bus.effect
        );
        bus.fastKey = nextKey;
    }

    private static void updateStandardReverb(int reverbEffect, RoomAcoustics room) {
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DENSITY, clamp(room.density(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, clamp(room.diffusion(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 1.0F);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAINHF, clamp(room.gainHighFrequency(), 0.0F, 1.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, clamp(room.decayTime(), 0.1F, 20.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_HFRATIO, clamp(room.decayHighFrequencyRatio(), 0.1F, 2.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, clamp(room.reflectionsGain(), 0.0F, 3.16F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, clamp(room.reflectionsDelay(), 0.0F, 0.3F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, clamp(room.lateReverbGain(), 0.0F, 10.0F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, clamp(room.lateReverbDelay(), 0.0F, 0.1F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF,
                clamp(room.airAbsorptionGainHighFrequency(), 0.892F, 1.0F));
        EXTEfx.alEffectf(
                reverbEffect,
                EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR,
                0.0F
        );
    }

    private static void detachSource(int source) {
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
        if (advancedEaxReverb) {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 1, EXTEfx.AL_FILTER_NULL);
        }
        if (dedicatedSourceRoomSendSupported) {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 2, EXTEfx.AL_FILTER_NULL);
        }
    }

    private static void deleteSourceState(SourceState state) {
        EXTEfx.alDeleteFilters(state.directFilter);
        EXTEfx.alDeleteFilters(state.reverbFilter);
        if (state.sourceRoomFilter != 0) {
            EXTEfx.alDeleteFilters(state.sourceRoomFilter);
        }
        if (state.earlyReflectionFilter != 0) {
            EXTEfx.alDeleteFilters(state.earlyReflectionFilter);
        }
        if (state.reverbBus != null) {
            state.reverbBus.references--;
            // A stopped one-shot no longer feeds this slot, while its already generated
            // acoustic energy remains audible until the room's own decay completes.
        }
        releaseSourceRoomBus(state);
        releaseEarlyReflectionBus(state);
    }

    private static void deleteReverbBus(ReverbBus bus) {
        EXTEfx.alDeleteAuxiliaryEffectSlots(bus.slot);
        EXTEfx.alDeleteEffects(bus.effect);
    }

    private static void deleteEarlyReflectionBus(EarlyReflectionBus bus) {
        EXTEfx.alDeleteAuxiliaryEffectSlots(bus.slot);
        EXTEfx.alDeleteEffects(bus.effect);
    }

    private static float[] vector(Vec3 vector) {
        return new float[]{(float) vector.x, (float) vector.y, (float) vector.z};
    }

    private static int quantize(float value, float scale) {
        return Math.round(clamp(value, 0.0F, 20.0F) * scale);
    }

    private static void recoverSourceAfterFailure(int source, RuntimeException exception) {
        AcousticSystem.LOGGER.warn(
                "OpenAL EFX update failed for source {}; isolating that source and retaining the shared DSP",
                source,
                exception
        );
        SourceState failed = SOURCES.remove(source);
        if (failed == null) {
            return;
        }
        try {
            detachSource(source);
        } catch (RuntimeException cleanupFailure) {
            AcousticSystem.LOGGER.debug("Could not detach failed OpenAL source {}", source, cleanupFailure);
        }
        try {
            deleteSourceState(failed);
        } catch (RuntimeException cleanupFailure) {
            AcousticSystem.LOGGER.debug("Could not delete failed OpenAL filters for source {}", source, cleanupFailure);
        }
    }

    private static void resetState() {
        SOURCES.clear();
        REVERB_BUSES.clear();
        REVERB_POOL.clear();
        EARLY_REFLECTION_BUSES.clear();
        EARLY_REFLECTION_POOL.clear();
        supportChecked = false;
        supported = false;
        advancedEaxReverb = false;
        dedicatedSourceRoomSendSupported = false;
        activeReverbBus = null;
        smoothedListenerRoom = null;
        lastListenerRoomUpdateNanoseconds = 0L;
        lastListenerRoomSequence = Long.MIN_VALUE;
        lastDynamicListenerPosition = null;
        nextFieldToken = 0L;
        tailFieldRequests = List.of();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float elapsedResponseAmount(long previousNanoseconds, long nowNanoseconds) {
        if (previousNanoseconds == 0L || nowNanoseconds <= previousNanoseconds) {
            return 1.0F;
        }
        double elapsedMilliseconds = (nowNanoseconds - previousNanoseconds) / 1_000_000.0;
        double responseTimeMilliseconds = AcousticMaterialRegistry.tuning()
                .acousticResponseTimeMilliseconds();
        return (float) (1.0 - Math.exp(-elapsedMilliseconds / responseTimeMilliseconds));
    }

    private static RoomAcoustics interpolateRoom(
            RoomAcoustics from,
            RoomAcoustics to,
            float amount
    ) {
        return new RoomAcoustics(
                lerp(from.density(), to.density(), amount),
                lerp(from.diffusion(), to.diffusion(), amount),
                lerp(from.gain(), to.gain(), amount),
                lerp(from.gainHighFrequency(), to.gainHighFrequency(), amount),
                lerp(from.gainLowFrequency(), to.gainLowFrequency(), amount),
                lerp(from.decayTime(), to.decayTime(), amount),
                lerp(from.decayHighFrequencyRatio(), to.decayHighFrequencyRatio(), amount),
                lerp(from.decayLowFrequencyRatio(), to.decayLowFrequencyRatio(), amount),
                lerp(from.reflectionsGain(), to.reflectionsGain(), amount),
                lerp(from.reflectionsDelay(), to.reflectionsDelay(), amount),
                from.reflectionsPan().lerp(to.reflectionsPan(), amount),
                lerp(from.lateReverbGain(), to.lateReverbGain(), amount),
                lerp(from.lateReverbDelay(), to.lateReverbDelay(), amount),
                from.lateReverbPan().lerp(to.lateReverbPan(), amount),
                lerp(from.modulationTime(), to.modulationTime(), amount),
                lerp(from.modulationDepth(), to.modulationDepth(), amount),
                lerp(
                        from.airAbsorptionGainHighFrequency(),
                        to.airAbsorptionGainHighFrequency(),
                        amount
                )
        );
    }

    // Only coefficients that alter the feedback network belong to its identity.
    // Spectral gain, delays and portal pan are safe real-time properties and are
    // updated on the existing effect. Treating every interpolated gain as a new room
    // repeatedly restarted OpenAL Soft's environment transition before it became
    // audible, which presented as the reverb cutting out and returning.
    private record RoomKey(
            int density,
            int diffusion,
            int decay,
            int decayHigh,
            int decayLow,
            int modulationTime,
            int modulationDepth,
            int airAbsorption
    ) {
        private static RoomKey from(RoomAcoustics room) {
            return new RoomKey(
                    quantize(room.density(), 10.0F),
                    quantize(room.diffusion(), 10.0F),
                    quantize(room.decayTime(), 5.0F),
                    quantize(room.decayHighFrequencyRatio(), 5.0F),
                    quantize(room.decayLowFrequencyRatio(), 5.0F),
                    quantize(room.modulationTime(), 10.0F),
                    quantize(room.modulationDepth(), 20.0F),
                    quantize(room.airAbsorptionGainHighFrequency(), 50.0F)
            );
        }

        private double distanceTo(RoomKey other) {
            return square(density - other.density)
                    + square(diffusion - other.diffusion)
                    + square(decay - other.decay)
                    + square(decayHigh - other.decayHigh)
                    + square(decayLow - other.decayLow)
                    + square(modulationTime - other.modulationTime)
                    + square(modulationDepth - other.modulationDepth)
                    + square(airAbsorption - other.airAbsorption);
        }
    }

    private record ReverbKey(RoomKey room) {
        private static ReverbKey from(RoomAcoustics room) {
            return new ReverbKey(RoomKey.from(room));
        }

        private double distanceTo(ReverbKey other) {
            return room.distanceTo(other.room);
        }
    }

    private record FastReverbKey(
            int highFrequency,
            int lowFrequency,
            int reflectionsGain,
            int reflectionDelay,
            int lateGain,
            int lateDelay,
            int panX,
            int panY,
            int panZ
    ) {
        private static FastReverbKey from(RoomAcoustics room, Vec3 pan) {
            return new FastReverbKey(
                    quantize(room.gainHighFrequency(), 1000.0F),
                    quantize(room.gainLowFrequency(), 1000.0F),
                    quantize(room.reflectionsGain(), 1000.0F),
                    quantize(room.reflectionsDelay(), 1000.0F),
                    quantize(room.lateReverbGain(), 1000.0F),
                    quantize(room.lateReverbDelay(), 1000.0F),
                    Math.round((float) pan.x * 1000.0F),
                    Math.round((float) pan.y * 1000.0F),
                    Math.round((float) pan.z * 1000.0F)
            );
        }
    }

    private record EarlyReflectionKey(
            int panX,
            int panY,
            int panZ,
            int delay,
            int highFrequency
    ) {
        private static EarlyReflectionKey from(
                EarlyReflection reflection,
                Vec3 listenerPan
        ) {
            return new EarlyReflectionKey(
                    Math.round((float) listenerPan.x * 4.0F),
                    Math.round((float) listenerPan.y * 4.0F),
                    Math.round((float) listenerPan.z * 4.0F),
                    quantize(reflection.delay(), 200.0F),
                    quantize(reflection.highFrequencyGain(), 10.0F)
            );
        }

        private double distanceTo(EarlyReflectionKey other) {
            return square(panX - other.panX)
                    + square(panY - other.panY)
                    + square(panZ - other.panZ)
                    + square(delay - other.delay)
                    + square(highFrequency - other.highFrequency);
        }
    }

    private static double square(int value) {
        return (double) value * value;
    }

    private static final class ReverbBus {
        private ReverbKey key;
        private RoomAcoustics room;
        private ListenerFieldSnapshot field;
        private FastReverbKey fastKey;
        private final int effect;
        private final int slot;
        private int references;
        private int sourceReferences;
        private boolean sourceField;
        private long fieldToken;
        private long lastUsed;
        private long tailExpiresAtNanoseconds;
        private float outputGain;
        private long lastOutputGainUpdateNanoseconds;

        private ReverbBus(ReverbKey key, RoomAcoustics room, int effect, int slot) {
            this.key = key;
            this.room = room;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
            this.outputGain = clamp(room.gain(), 0.0F, 1.0F);
            this.lastOutputGainUpdateNanoseconds = lastUsed;
        }

        private boolean canRecycle(long now) {
            return references == 0
                    && (now >= tailExpiresAtNanoseconds || outputGain == 0.0F);
        }

        private void activate(long now) {
            boolean wasRetired = tailExpiresAtNanoseconds != 0L;
            tailExpiresAtNanoseconds = 0L;
            lastUsed = now;
            if (wasRetired) {
                lastOutputGainUpdateNanoseconds = now;
            }
        }

        private void retire(long now) {
            lastUsed = now;
            lastOutputGainUpdateNanoseconds = now;
            // EAX decayTime is RT60: the stored field is around -60 dB after one
            // interval, not mathematically finished. Muting the slot exactly there made
            // every stopped sound end with a time-locked hard cut. Protect it for two
            // RT60 intervals (-120 dB) before recycling; by then setting the slot to
            // zero is below audibility and does not add another audible envelope.
            double tailSeconds = Math.max(0.1, room.decayTime()) * 2.0
                    + Math.max(0.0, room.lateReverbDelay())
                    + Math.max(0.0, room.reflectionsDelay());
            long tailNanoseconds = (long) Math.ceil(tailSeconds * 1_000_000_000.0);
            tailExpiresAtNanoseconds = now > Long.MAX_VALUE - tailNanoseconds
                    ? Long.MAX_VALUE
                    : now + tailNanoseconds;
        }
    }

    public record TailFieldRequest(
            long fieldToken,
            Vec3 position,
            boolean sourceField
    ) {
    }

    private record ListenerFieldSnapshot(
            Vec3 listener,
            List<Aperture> apertures
    ) {
        private static ListenerFieldSnapshot from(RoomProbe probe, Vec3 listener) {
            double raySolidAngle = Math.PI * 4.0
                    / AcousticMaterialRegistry.tuning().roomRayCount();
            List<Aperture> apertures = new ArrayList<>(probe.openings().size());
            for (RoomProbe.OpeningSample opening : probe.openings()) {
                Vec3 direction = opening.direction();
                if (direction.lengthSqr() <= 1.0E-12) {
                    continue;
                }
                double distance = listener.distanceTo(opening.point());
                double area = raySolidAngle * distance * distance
                        * Math.max(0.0F, opening.weight());
                if (area <= 1.0E-8) {
                    continue;
                }
                apertures.add(new Aperture(
                        opening.point(), direction.normalize(), area
                ));
            }
            return new ListenerFieldSnapshot(listener, List.copyOf(apertures));
        }

        private PortalRadiation apertureRadiation(
                Vec3 observation,
                boolean listenerOwnedField
        ) {
            if (apertures.isEmpty()) {
                // No opening has two different physical meanings. For the field just
                // measured at the listener, it means a sealed room whose diffuse energy
                // reaches that listener fully. For a remote or retired room, it means
                // there is no aperture through which its stored energy can escape.
                // Treating both as NONE muted a correctly detected sealed room on the
                // very next per-frame position update.
                return listenerOwnedField
                        ? PortalRadiation.INSIDE
                        : PortalRadiation.NONE;
            }
            double receivedPowerFraction = 0.0;
            Vec3 directionMoment = Vec3.ZERO;
            boolean beyondOpeningPlane = false;
            for (Aperture aperture : apertures) {
                Vec3 delta = observation.subtract(aperture.point());
                double axialDistance = delta.dot(aperture.outward());
                if (axialDistance <= 0.0) {
                    continue;
                }
                beyondOpeningPlane = true;
                double distanceSquared = Math.max(delta.lengthSqr(), 1.0E-10);
                // The finite-area term is the on-axis solid angle limit of an aperture;
                // it removes the point-source singularity while retaining inverse-square
                // spreading in the far field.
                double powerFraction = aperturePowerFraction(
                        aperture.area(), axialDistance, distanceSquared
                );
                receivedPowerFraction += powerFraction;
                Vec3 arrival = aperture.point().subtract(observation);
                if (arrival.lengthSqr() > 1.0E-12) {
                    directionMoment = directionMoment.add(
                            arrival.normalize().scale(powerFraction)
                    );
                }
            }
            if (!beyondOpeningPlane) {
                return PortalRadiation.INSIDE;
            }
            float transmission = clamp(
                    (float) Math.sqrt(Math.min(1.0, receivedPowerFraction)),
                    0.0F, 1.0F
            );
            Vec3 arrivalDirection = receivedPowerFraction <= 1.0E-12
                    ? Vec3.ZERO
                    : directionMoment.scale(1.0 / receivedPowerFraction);
            return new PortalRadiation(transmission, arrivalDirection, true);
        }
    }

    private record Aperture(Vec3 point, Vec3 outward, double area) {
    }

    private record PortalRadiation(
            float transmission,
            Vec3 arrivalDirection,
            boolean outside
    ) {
        private static final PortalRadiation NONE = new PortalRadiation(
                0.0F, Vec3.ZERO, true
        );
        private static final PortalRadiation INSIDE = new PortalRadiation(
                1.0F, Vec3.ZERO, false
        );
    }

    static double aperturePowerFraction(
            double area,
            double axialDistance,
            double distanceSquared
    ) {
        if (area <= 0.0 || axialDistance <= 0.0 || distanceSquared <= 0.0) {
            return 0.0;
        }
        double projectedArea = area * axialDistance / Math.sqrt(distanceSquared);
        double solidAngle = projectedArea / (
                distanceSquared + area / (Math.PI * 2.0)
        );
        return Math.max(0.0, Math.min(1.0, solidAngle / (Math.PI * 2.0)));
    }

    private static final class EarlyReflectionBus {
        private EarlyReflectionKey key;
        private final int effect;
        private final int slot;
        private int references;
        private long lastUsed;

        private EarlyReflectionBus(EarlyReflectionKey key, int effect, int slot) {
            this.key = key;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
        }
    }

    private static final class SourceState {
        private final int directFilter;
        private final int reverbFilter;
        private final int sourceRoomFilter;
        private final int earlyReflectionFilter;
        private ReverbBus reverbBus;
        private ReverbBus sourceRoomBus;
        // The bus currently bound to the primary late send. Retaining this explicit
        // ownership also makes accidental callback-driven routing changes observable.
        private ReverbBus primaryLateBus;
        private EarlyReflectionBus earlyReflectionBus;
        private float directGain = 1.0F;
        private float highFrequencyGain = 1.0F;
        private float reverbSend = 0.04F;
        private float reverbHighFrequency = 1.0F;
        private float sourceRoomSend;
        private float sourceRoomHighFrequency = 1.0F;
        private RoomProbe sourceRoomProbe;
        private Vec3 sourcePosition;
        private EarlyReflection earlyReflection = EarlyReflection.SILENT;
        private Vec3 apparentPosition = Vec3.ZERO;
        private boolean initialized;
        private long lastUpdateNanoseconds;
        private long lastResultSequence = Long.MIN_VALUE;

        private SourceState(
                int directFilter,
                int reverbFilter,
                int sourceRoomFilter,
                int earlyReflectionFilter
        ) {
            this.directFilter = directFilter;
            this.reverbFilter = reverbFilter;
            this.sourceRoomFilter = sourceRoomFilter;
            this.earlyReflectionFilter = earlyReflectionFilter;
        }

        private boolean update(
                AcousticResult target,
                boolean snapToTarget,
                long sequence,
                boolean ordered
        ) {
            if (ordered && sequence < lastResultSequence) {
                return false;
            }
            if (ordered) {
                lastResultSequence = sequence;
            }
            long now = System.nanoTime();
            if (!initialized || snapToTarget) {
                directGain = target.directGain();
                highFrequencyGain = target.highFrequencyGain();
                reverbSend = target.reverbSend();
                reverbHighFrequency = target.reverbHighFrequencyGain();
                updateSourceRoomTarget(target);
                earlyReflection = target.earlyReflection();
                apparentPosition = target.apparentPosition();
                initialized = true;
                lastUpdateNanoseconds = now;
                return true;
            }
            float amount = elapsedResponseAmount(lastUpdateNanoseconds, now);
            directGain = lerp(directGain, target.directGain(), amount);
            highFrequencyGain = lerp(highFrequencyGain, target.highFrequencyGain(), amount);
            reverbSend = lerp(reverbSend, target.reverbSend(), amount);
            reverbHighFrequency = lerp(
                    reverbHighFrequency,
                    target.reverbHighFrequencyGain(),
                    amount
            );
            updateSourceRoomTarget(target);
            EarlyReflection targetReflection = target.earlyReflection();
            earlyReflection = new EarlyReflection(
                    lerp(earlyReflection.gain(), targetReflection.gain(), amount),
                    lerp(
                            earlyReflection.highFrequencyGain(),
                            targetReflection.highFrequencyGain(),
                            amount
                    ),
                    lerp(earlyReflection.delay(), targetReflection.delay(), amount),
                    earlyReflection.arrivalDirection().lerp(
                            targetReflection.arrivalDirection(), amount
                    )
            );
            apparentPosition = apparentPosition.lerp(target.apparentPosition(), amount);
            lastUpdateNanoseconds = now;
            return true;
        }

        private void updateSourceRoomTarget(AcousticResult target) {
            sourceRoomProbe = target.sourceRoomProbe();
            sourcePosition = target.sourcePosition();
            if (sourceRoomProbe == null) {
                sourceRoomSend = 0.0F;
                sourceRoomHighFrequency = 1.0F;
                return;
            }
            sourceRoomSend = clamp(
                    AcousticMaterialRegistry.tuning().reverbSendScale(),
                    0.0F,
                    0.95F
            );
            sourceRoomHighFrequency = sourceRoomProbe.acoustics()
                    .gainHighFrequency();
        }
    }
}
