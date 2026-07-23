package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTLinearDistance;
import org.lwjgl.BufferUtils;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.material.AcousticBands;
import org.macaroon.acousticsystem.client.simulation.AcousticResult;
import org.macaroon.acousticsystem.client.simulation.DiffuseFieldDynamics;
import org.macaroon.acousticsystem.client.simulation.DirectionalArrivalField;
import org.macaroon.acousticsystem.client.simulation.EarlyReflection;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;
import org.macaroon.acousticsystem.client.simulation.RoomImpulseResponse;
import org.macaroon.acousticsystem.client.simulation.RoomProbe;
import org.macaroon.acousticsystem.physics.AtmosphericAbsorption;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.nio.FloatBuffer;

public final class OpenALAcousticEffects {
    private static final Map<Integer, SourceState> SOURCES = new HashMap<>();
    private static final Map<Integer, Float> SOURCE_MAX_DISTANCES = new HashMap<>();
    private static final Map<ReverbKey, ReverbBus> REVERB_BUSES = new HashMap<>();
    private static final List<ReverbBus> REVERB_POOL = new ArrayList<>();
    private static final Map<EarlyReflectionKey, EarlyReflectionBus> EARLY_REFLECTION_BUSES = new HashMap<>();
    private static final List<EarlyReflectionBus> EARLY_REFLECTION_POOL = new ArrayList<>();
    private static final ThreadLocal<FloatBuffer> LISTENER_ORIENTATION = ThreadLocal.withInitial(
            () -> BufferUtils.createFloatBuffer(6)
    );
    // These are warm-start reserves, not runtime limits. Additional physically distinct
    // fields are created as needed and reclaimed only after their calculated energy has
    // decayed; no active response is displaced to satisfy an arbitrary pool size.
    private static final int PREWARMED_REVERB_BUSES = 4;
    private static final int PREWARMED_EARLY_REFLECTION_BUSES = 6;
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
    private static RoomAcoustics targetListenerRoom;
    private static long lastListenerRoomUpdateNanoseconds;
    private static long lastListenerRoomSequence = Long.MIN_VALUE;
    private static Vec3 lastDynamicListenerPosition;
    private static ListenerFieldSnapshot softwareListenerField;
    private static long nextFieldToken;
    private static volatile List<TailFieldRequest> tailFieldRequests = List.of();
    private static DistanceAttenuationSettings appliedDistanceAttenuationSettings;
    private static AtmosphereHighFrequencyCache atmosphereHighFrequencyCache;
    private static boolean vanillaProcessing;

    private OpenALAcousticEffects() {
    }

    /**
     * Captures the sound asset's authored reach. The physical solver owns distance
     * spreading; OpenAL retains only positioning/HRTF so dry and wet paths cannot use
     * different distance laws.
     */
    public static void configureDistanceAttenuation(int source, float maximumDistance) {
        SOURCE_MAX_DISTANCES.put(source, Math.max(0.1F, maximumDistance));
        if (!AcousticQualityConfig.settings().enabled()) {
            return;
        }
        vanillaProcessing = false;
        applyDistanceAttenuation(source, distanceAttenuationSettings());
    }

    /** Removes every mod-owned OpenAL object and restores live voices to vanilla. */
    public static void useVanillaProcessing() {
        if (vanillaProcessing) {
            return;
        }
        Map<Integer, Float> authoredRanges = new HashMap<>(SOURCE_MAX_DISTANCES);
        if (supported) {
            DistanceAttenuationSettings vanilla = new DistanceAttenuationSettings(
                    false, 0.0F, 1.0F
            );
            for (int source : authoredRanges.keySet()) {
                applyDistanceAttenuation(source, vanilla);
            }
            for (int source : SOURCES.keySet()) {
                if (!authoredRanges.containsKey(source)) {
                    applyDistanceAttenuation(source, vanilla);
                }
            }
        }
        shutdown();
        SoftwareAcousticMixer.clearVoices();
        SOURCE_MAX_DISTANCES.putAll(authoredRanges);
        vanillaProcessing = true;
    }

    /** Builds the reusable EFX buses while Minecraft is initializing its audio library. */
    public static void initialize() {
        if (!ensureSupport()) {
            throw new IllegalStateException(
                    "Required OpenAL direct-filter support is unavailable"
            );
        }
        if (!SoftwareAcousticMixer.available()) {
            throw new IllegalStateException(
                    "Software acoustic mixer did not initialize; EFX fallback is disabled"
            );
        }
        if (SoftwareAcousticMixer.available()) {
            AcousticSystem.LOGGER.info(
                    "OpenAL is retaining only positional direct filtering; reflected fields use the required software mixer"
            );
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
            while (REVERB_POOL.size() < PREWARMED_REVERB_BUSES) {
                createReverbBus(null, RoomAcoustics.OUTDOORS);
            }
            if (advancedEaxReverb) {
                while (EARLY_REFLECTION_POOL.size() < PREWARMED_EARLY_REFLECTION_BUSES) {
                    EarlyReflectionBus prewarmed = createEarlyReflectionBus(
                            null, EarlyReflection.SILENT, Vec3.ZERO,
                            EarlyFieldMix.SILENT
                    );
                    if (prewarmed == null) {
                        break;
                    }
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
            targetListenerRoom = null;
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
    public static boolean applySequenced(int source, AcousticResult target, long sequence) {
        // Source callbacks own only source transport. The listener probe is delivered
        // independently once per geometry batch and is the sole owner of the global
        // room field. Letting every voice also commit that field made callback order,
        // rather than listener geometry, decide which reverb bus was active.
        return applyInternal(source, target, false, sequence, true);
    }

    public static void applyBeforePlay(int source, AcousticResult target) {
        applyInternal(source, target, true);
    }

    /**
     * Introduces a completed result for a source which had already received a valid
     * onset result. Sources whose first trace is pending do not enter playback at all;
     * AcousticRuntime starts those from sample zero after applying the result.
     */
    public static void applyOnsetCorrection(int source, AcousticResult target) {
        applyInternal(source, target, false);
    }

    public static void applyOnsetCorrection(int source, AcousticResult target, long sequence) {
        applyInternal(source, target, false, sequence, true);
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
        updateListenerRoom(probe, listener, sequence, Long.MIN_VALUE);
    }

    public static void updateListenerRoom(
            RoomProbe probe,
            Vec3 listener,
            long sequence,
            long sceneRevision
    ) {
        applyListenerRoomSequenced(probe, listener, sequence, sceneRevision);
    }

    /**
     * Commits an ordered listener field and reports whether it was accepted. Runtime
     * transport uses the return value to distinguish an actual DSP publication from
     * an obsolete completion without inspecting OpenAL-owned state off-thread.
     */
    public static boolean applyListenerRoomSequenced(
            RoomProbe probe,
            Vec3 listener,
            long sequence,
            long sceneRevision
    ) {
        return updateListenerRoomInternal(
                probe.acoustics(),
                true,
                sequence,
                true,
                ListenerFieldSnapshot.from(probe, listener),
                sceneRevision
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
     * next geometric probe. This only evaluates finite apertures and updates EFX slot
     * gains and send filters, so it is safe to run for every observed listener position.
     */
    public static void updateListenerPosition(Vec3 listener) {
        if (!ensureSupport()) {
            return;
        }
        Vec3 previousListenerPosition = lastDynamicListenerPosition;
        lastDynamicListenerPosition = listener;
        try {
            long now = System.nanoTime();
            ListenerBasis listenerBasis = ListenerBasis.capture();
            boolean softwareMixer = SoftwareAcousticMixer.available();
            PortalRadiation softwareRadiation = softwareMixer
                    && softwareListenerField != null
                    ? softwareListenerField.apertureRadiation(listener, true)
                    : PortalRadiation.INSIDE;
            for (Map.Entry<Integer, SourceState> entry : SOURCES.entrySet()) {
                int source = entry.getKey();
                SourceState state = entry.getValue();
                state.reprojectSpatial(listener, now);
                AL10.alSource3f(
                        source,
                        AL10.AL_POSITION,
                        (float) state.apparentPosition.x,
                        (float) state.apparentPosition.y,
                        (float) state.apparentPosition.z
                );
                Vec3 earlyDirection = listenerBasis.project(state.dynamicEarlyDirection);
                Vec3 propagationDirection = listenerBasis.project(
                        state.apparentPosition.subtract(listener)
                );
                if (softwareMixer) {
                    float transportedGain = state.propagationGain
                            * state.audibilityGain;
                    SoftwareAcousticMixer.updateSpatial(
                            source, earlyDirection, propagationDirection,
                            softwareRadiation.outside()
                                    ? softwareRadiation.transmission()
                                    : 1.0F,
                            state.reverbSend * transportedGain,
                            state.reverbHighFrequency
                                    * softwareRadiation.highFrequencyGain()
                    );
                } else if (advancedEaxReverb
                        && (state.earlyReflection.gain() > 1.0E-6F
                        || state.earlyReflectionBus != null)) {
                    updateEarlyReflectionSend(source, state, earlyDirection);
                }
            }
            if (softwareMixer) {
                return;
            }
            reapExpiredEarlyReflectionBuses(now);
            reapExpiredReverbBuses(now);
            if (activeReverbBus == null || smoothedListenerRoom == null) {
                return;
            }
            // Boundary energy keeps evolving even while the player is stationary. This
            // is important immediately after a block edit: the room must settle according
            // to elapsed physical time, not wait for another movement event.
            advanceListenerField(now);
            if (previousListenerPosition != null
                    && listener.distanceToSqr(previousListenerPosition) <= 1.0E-10) {
                return;
            }
            PortalRadiation activeRadiation = activeReverbBus.field == null
                    ? PortalRadiation.INSIDE
                    : activeReverbBus.field.apertureRadiation(listener, true);
            float targetGain = clamp(smoothedListenerRoom.gain(), 0.0F, 1.0F);
            if (activeRadiation.outside()) {
                targetGain *= activeRadiation.transmission();
            }
            // smoothedListenerRoom already is the exact elapsed-time evolution of the
            // diffuse energy. Filtering its slot gain a second time adds an unrelated
            // lag and makes the audible field trail the player's actual space.
            activeReverbBus.outputGain = targetGain;
            EXTEfx.alAuxiliaryEffectSlotf(
                    activeReverbBus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(activeReverbBus.outputGain, 0.0F, 1.0F)
            );
            activeReverbBus.lastOutputGainUpdateNanoseconds = now;
            activeReverbBus.transportHighFrequencyGain =
                    activeRadiation.highFrequencyGain();
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
        if (!SOURCES.isEmpty() || smoothedListenerRoom != null) {
            return;
        }
        updateListenerRoomInternal(room, false);
    }

    private static void updateListenerRoomInternal(RoomAcoustics room, boolean interpolate) {
        updateListenerRoomInternal(
                room, interpolate, Long.MIN_VALUE, false, null, Long.MIN_VALUE
        );
    }

    private static boolean updateListenerRoomInternal(
            RoomAcoustics room,
            boolean interpolate,
            long sequence,
            boolean ordered
    ) {
        return updateListenerRoomInternal(
                room, interpolate, sequence, ordered, null, Long.MIN_VALUE
        );
    }

    private static boolean updateListenerRoomInternal(
            RoomAcoustics room,
            boolean interpolate,
            long sequence,
            boolean ordered,
            ListenerFieldSnapshot measuredField,
            long sceneRevision
    ) {
        if (!ensureSupport()) {
            return false;
        }
        syncDistanceAttenuationSettings();
        if (SoftwareAcousticMixer.available()) {
            if (ordered && sequence < lastListenerRoomSequence) {
                return false;
            }
            if (ordered) {
                lastListenerRoomSequence = sequence;
            }
            smoothedListenerRoom = room;
            targetListenerRoom = room;
            if (measuredField != null) {
                softwareListenerField = measuredField;
            }
            lastListenerRoomUpdateNanoseconds = System.nanoTime();
            tailFieldRequests = List.of();
            return true;
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
            boolean initializesField = !interpolate || smoothedListenerRoom == null;
            if (initializesField) {
                smoothedListenerRoom = room;
                targetListenerRoom = room;
            } else {
                // First advance the existing energy to the exact event time, then
                // replace only its boundary conditions. This keeps all accumulated
                // energy continuous across motion and arbitrarily frequent block edits.
                advanceListenerField(now);
                targetListenerRoom = room;
            }
            lastListenerRoomUpdateNanoseconds = now;
            ReverbBus listenerBus = currentReverbBus();
            reindexListenerReverbBus(listenerBus, ReverbKey.from(room));
            if (initializesField) {
                // Startup has no stored acoustic energy to evolve. Publish the measured
                // boundary to the already prewarmed network without allocating another
                // field or waiting for a later movement callback.
                morphReverbBus(listenerBus, room);
            }
            // Space identity is not a DSP lifetime boundary. Crossing an aperture and
            // editing blocks only replace the measured boundary of the same energy
            // state, which DiffuseFieldDynamics advances using real elapsed time.
            if (measuredField != null) {
                listenerBus.field = measuredField;
                listenerBus.fieldToken = ++nextFieldToken;
                if (sceneRevision != Long.MIN_VALUE) {
                    listenerBus.sceneRevision = sceneRevision;
                }
            }
            Vec3 observation = lastDynamicListenerPosition != null
                    ? lastDynamicListenerPosition
                    : measuredField == null ? null : measuredField.listener();
            PortalRadiation activeRadiation = observation == null
                    || listenerBus.field == null
                    ? PortalRadiation.INSIDE
                    : listenerBus.field.apertureRadiation(observation, true);
            listenerBus.transportHighFrequencyGain =
                    activeRadiation.highFrequencyGain();
            refreshBusSendFilters(listenerBus);
            float activeTargetGain = clamp(
                    smoothedListenerRoom.gain()
                            * (activeRadiation.outside()
                            ? activeRadiation.transmission() : 1.0F),
                    0.0F, 1.0F
            );
            listenerBus.outputGain = activeTargetGain;
            EXTEfx.alAuxiliaryEffectSlotf(
                    listenerBus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(listenerBus.outputGain, 0.0F, 1.0F)
            );
            listenerBus.lastOutputGainUpdateNanoseconds = now;
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

    private static boolean applyInternal(
            int source,
            AcousticResult target,
            boolean snapToTarget,
            long sequence,
            boolean ordered
    ) {
        if (!ensureSupport()) {
            return false;
        }
        syncDistanceAttenuationSettings();

        try {
            // OpenAL keeps one error flag per context. Consume any stale flag before
            // this transaction and always consume ours below, so one failed voice can
            // never poison every later vanilla source allocation.
            AL10.alGetError();
            SourceState state = SOURCES.computeIfAbsent(source, OpenALAcousticEffects::createSourceState);
            if (!state.update(target, snapToTarget, sequence, ordered)) {
                return false;
            }
            if (SoftwareAcousticMixer.available()) {
                RoomAcoustics sourceRoom = state.sourceRoomProbe == null
                        ? RoomAcoustics.OUTDOORS
                        : state.sourceRoomProbe.acoustics();
                Vec3 earlyDirection = listenerRelativeDirection(
                        state.dynamicEarlyDirection
                );
                Vec3 propagationDirection = lastDynamicListenerPosition == null
                        ? Vec3.ZERO
                        : listenerRelativeDirection(
                                state.apparentPosition.subtract(lastDynamicListenerPosition)
                        );
                float transportedGain = state.propagationGain
                        * state.audibilityGain;
                SoftwareAcousticMixer.apply(
                        source,
                        state.earlyReflection,
                        transportedGain,
                        earlyDirection,
                        propagationDirection,
                        target.reverbRoom(),
                        state.reverbSend * transportedGain,
                        state.reverbHighFrequency,
                        sourceRoom,
                        state.sourceRoomSend * transportedGain,
                        state.sourceRoomHighFrequency,
                        sequence
                );
                applyPositionalDirectPath(source, state);
                AL10.alSource3f(source, AL10.AL_POSITION,
                        (float) state.apparentPosition.x,
                        (float) state.apparentPosition.y,
                        (float) state.apparentPosition.z);
                throwOnOpenAlError(source, "software acoustic update");
                return true;
            }
            assignReverbBus(state);
            assignSourceRoomBus(state);

            applyPositionalDirectPath(source, state);
            AL10.alSource3f(source, AL10.AL_POSITION,
                    (float) state.apparentPosition.x,
                    (float) state.apparentPosition.y,
                    (float) state.apparentPosition.z);
            throwOnOpenAlError(source, "EFX acoustic update");
            return true;
        } catch (RuntimeException exception) {
            recoverSourceAfterFailure(source, exception);
            return false;
        }
    }

    public static void releaseSource(int source) {
        SOURCE_MAX_DISTANCES.remove(source);
        SourceState state = SOURCES.remove(source);
        if (state == null || !supported) {
            return;
        }
        try {
            detachSource(source);
            deleteSourceState(state);
        } finally {
            // Channel.destroy continues with vanilla OpenAL cleanup immediately after
            // this injection. Never hand it an error produced by our detached source.
            AL10.alGetError();
        }
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
        EXTEfx.alFilterf(
                state.directFilter,
                EXTEfx.AL_BANDPASS_GAIN,
                clamp(
                        state.directGain * state.propagationGain
                                * state.audibilityGain,
                        0.0F,
                        1.0F
                )
        );
        EXTEfx.alFilterf(
                state.directFilter,
                EXTEfx.AL_BANDPASS_GAINLF,
                clamp(state.lowFrequencyGain, 0.0F, 1.0F)
        );
        EXTEfx.alFilterf(
                state.directFilter,
                EXTEfx.AL_BANDPASS_GAINHF,
                clamp(state.highFrequencyGain, 0.0F, 1.0F)
        );
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, state.directFilter);
        if (SoftwareAcousticMixer.available()) {
            return;
        }
        updateReverbFilter(state);
        if (advancedEaxReverb) {
            updateEarlyReflectionSend(source, state);
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
                clamp(state.sourceRoomSend * state.audibilityGain, 0.0F, 1.0F)
        );
        EXTEfx.alFilterf(
                state.sourceRoomFilter,
                EXTEfx.AL_LOWPASS_GAINHF,
                clamp(
                        state.sourceRoomHighFrequency
                                * state.sourceRoomBus.transportHighFrequencyGain,
                        0.0F,
                        1.0F
                )
        );
    }

    private static void updateReverbFilter(SourceState state) {
        EXTEfx.alFilterf(
                state.reverbFilter,
                EXTEfx.AL_LOWPASS_GAIN,
                clamp(
                        state.reverbSend * state.propagationGain
                                * state.audibilityGain,
                        0.0F,
                        1.0F
                )
        );
        EXTEfx.alFilterf(
                state.reverbFilter,
                EXTEfx.AL_LOWPASS_GAINHF,
                clamp(
                        state.reverbHighFrequency
                                * state.reverbBus.transportHighFrequencyGain,
                        0.0F,
                        1.0F
                )
        );
    }

    /** Updates transport filters without replacing an EFX feedback network mid-tail. */
    private static void refreshBusSendFilters(ReverbBus bus) {
        for (SourceState state : SOURCES.values()) {
            if (state.reverbBus == bus) {
                updateReverbFilter(state);
            }
            if (state.sourceRoomBus == bus) {
                updateSourceRoomFilter(state);
            }
        }
    }

    private static void updateEarlyReflectionSend(int source, SourceState state) {
        updateEarlyReflectionSend(
                source,
                state,
                listenerRelativeDirection(state.dynamicEarlyDirection)
        );
    }

    private static void updateEarlyReflectionSend(
            int source,
            SourceState state,
            Vec3 listenerPan
    ) {
        EarlyReflection reflection = state.earlyReflection;
        EarlyFieldMix mix = EarlyFieldMix.from(state);
        if (mix.inputGain() <= 0.0F) {
            if (state.earlyReflectionBus == null) {
                AL11.alSource3i(
                        source,
                        EXTEfx.AL_AUXILIARY_SEND_FILTER,
                        EXTEfx.AL_EFFECTSLOT_NULL,
                        0,
                        EXTEfx.AL_FILTER_NULL
                );
                return;
            }
            // Stop injecting new energy without detaching the delay line. Samples
            // already travelling toward the reflected arrival must still reach it.
            EXTEfx.alFilterf(
                    state.earlyReflectionFilter,
                    EXTEfx.AL_BANDPASS_GAIN,
                    0.0F
            );
            AL11.alSource3i(
                    source,
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    state.earlyReflectionBus.slot,
                    0,
                    state.earlyReflectionFilter
            );
            return;
        }

        EarlyReflectionKey key = EarlyReflectionKey.from(reflection, listenerPan);
        assignEarlyReflectionBus(state, key, reflection, listenerPan, mix);
        if (state.earlyReflectionBus == null) {
            AL11.alSource3i(
                    source,
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL,
                    0,
                    EXTEfx.AL_FILTER_NULL
            );
            return;
        }
        EXTEfx.alFilterf(
                state.earlyReflectionFilter,
                EXTEfx.AL_BANDPASS_GAIN,
                mix.inputGain()
        );
        EXTEfx.alFilterf(
                state.earlyReflectionFilter,
                EXTEfx.AL_BANDPASS_GAINLF,
                mix.lowFrequencyGain()
        );
        EXTEfx.alFilterf(
                state.earlyReflectionFilter,
                EXTEfx.AL_BANDPASS_GAINHF,
                mix.highFrequencyGain()
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
        return ListenerBasis.capture().project(worldDirection);
    }

    private record ListenerBasis(Vec3 right, Vec3 up, Vec3 forward) {
        private static ListenerBasis capture() {
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
                return new ListenerBasis(
                        new Vec3(1.0, 0.0, 0.0),
                        new Vec3(0.0, 1.0, 0.0),
                        new Vec3(0.0, 0.0, -1.0)
                );
            }
            forward = forward.normalize();
            up = up.normalize();
            Vec3 right = forward.cross(up);
            if (right.lengthSqr() <= 1.0E-10) {
                return new ListenerBasis(new Vec3(1.0, 0.0, 0.0), up, forward);
            }
            return new ListenerBasis(right.normalize(), up, forward);
        }

        private Vec3 project(Vec3 worldDirection) {
            if (worldDirection == null || worldDirection.lengthSqr() <= 1.0E-12) {
                return Vec3.ZERO;
            }
            Vec3 direction = worldDirection.normalize();
            Vec3 local = new Vec3(
                    direction.dot(right),
                    direction.dot(up),
                    -direction.dot(forward)
            );
            return local.lengthSqr() <= 1.0E-12 ? Vec3.ZERO : local.normalize();
        }
    }

    private static boolean ensureSupport() {
        if (!AcousticQualityConfig.settings().enabled()) {
            return false;
        }
        vanillaProcessing = false;
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
                AcousticSystem.LOGGER.warn("The audio context exposed only two auxiliary sends; source-room decay is unavailable, while source reflections and listener reverb remain independent");
            }
        } else {
            AcousticSystem.LOGGER.info("Unified OpenAL EFX acoustic pipeline enabled in compatible two-parameter mode ({} auxiliary send)", maximumSends);
        }
        AcousticSystem.LOGGER.info("Real-time calculated EAX reverb enabled");
        return true;
    }

    private static SourceState createSourceState(int source) {
        applyDistanceAttenuation(source, distanceAttenuationSettings());
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
        // All paths now receive the solver's explicit absolute propagation gain.
        // Automatic EFX distance gain would apply a second, implementation-dependent
        // attenuation and split the dry/wet energy model again.
        AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO, AL10.AL_FALSE);
        AL10.alSourcei(source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAINHF_AUTO, AL10.AL_FALSE);
        int directFilter = createBandPass(1.0F, 1.0F, 1.0F);
        int reverbFilter = SoftwareAcousticMixer.available()
                ? 0
                : createLowPass(0.04F, 1.0F);
        int sourceRoomFilter = advancedEaxReverb && !SoftwareAcousticMixer.available()
                ? createLowPass(0.04F, 1.0F)
                : 0;
        int earlyReflectionFilter = advancedEaxReverb && !SoftwareAcousticMixer.available()
                ? createBandPass(0.0F, 1.0F, 1.0F)
                : 0;
        return new SourceState(
                directFilter, reverbFilter, sourceRoomFilter,
                earlyReflectionFilter,
                SOURCE_MAX_DISTANCES.getOrDefault(source, Float.POSITIVE_INFINITY)
        );
    }

    private static int createLowPass(float gain, float highFrequencyGain) {
        AL10.alGetError();
        int filter = EXTEfx.alGenFilters();
        int allocationError = AL10.alGetError();
        if (filter == 0 || allocationError != AL10.AL_NO_ERROR) {
            if (filter != 0) {
                EXTEfx.alDeleteFilters(filter);
                AL10.alGetError();
            }
            throw new IllegalStateException(
                    "OpenAL could not allocate a direct-path filter: "
                            + allocationError
            );
        }
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, highFrequencyGain);
        int configurationError = AL10.alGetError();
        if (configurationError != AL10.AL_NO_ERROR) {
            EXTEfx.alDeleteFilters(filter);
            AL10.alGetError();
            throw new IllegalStateException(
                    "OpenAL could not configure a direct-path filter: "
                            + configurationError
            );
        }
        return filter;
    }

    private static int createBandPass(
            float gain,
            float lowFrequencyGain,
            float highFrequencyGain
    ) {
        AL10.alGetError();
        int filter = EXTEfx.alGenFilters();
        int allocationError = AL10.alGetError();
        if (filter == 0 || allocationError != AL10.AL_NO_ERROR) {
            if (filter != 0) {
                EXTEfx.alDeleteFilters(filter);
                AL10.alGetError();
            }
            throw new IllegalStateException(
                    "OpenAL could not allocate a direct-path filter: " + allocationError
            );
        }
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_BANDPASS);
        EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAIN, gain);
        EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAINLF, lowFrequencyGain);
        EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAINHF, highFrequencyGain);
        int configurationError = AL10.alGetError();
        if (configurationError != AL10.AL_NO_ERROR) {
            EXTEfx.alDeleteFilters(filter);
            AL10.alGetError();
            throw new IllegalStateException(
                    "OpenAL could not configure a direct-path filter: " + configurationError
            );
        }
        return filter;
    }

    private static void throwOnOpenAlError(int source, String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException(
                    operation + " failed for source " + source
                            + " with OpenAL error " + error
            );
        }
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
                || !dedicatedSourceRoomSendSupported
                || state.sourceRoomProbe == null
                || state.sourcePosition == null
                || state.sourceRoomProbe.acoustics().gain() <= 1.0E-6F) {
            releaseSourceRoomBus(state);
            return;
        }
        ReverbKey key = ReverbKey.from(state.sourceRoomProbe.acoustics());
        if (targetListenerRoom != null
                && key.equals(ReverbKey.from(targetListenerRoom))) {
            // The listener and emitter share the same diffuse field. Feeding both sends
            // would count the same room energy twice.
            releaseSourceRoomBus(state);
            return;
        }

        if (state.sourceRoomBus != null
                && !key.equals(state.sourceRoomBus.key)
                && state.sourceRoomBus.sourceReferences > 1) {
            ReverbBus replacement = acquireSourceRoomBus(
                    key, state.sourceRoomProbe.acoustics()
            );
            if (replacement != null && replacement != state.sourceRoomBus) {
                ReverbBus previous = state.sourceRoomBus;
                previous.references--;
                previous.sourceReferences--;
                if (previous.references == 0 && previous != activeReverbBus) {
                    previous.retire(System.nanoTime());
                }
                state.sourceRoomBus = replacement;
                replacement.references++;
                replacement.sourceReferences++;
            }
        }
        if (state.sourceRoomBus == null) {
            ReverbBus next = acquireSourceRoomBus(
                    key, state.sourceRoomProbe.acoustics()
            );
            if (next == null) {
                return;
            }
            state.sourceRoomBus = next;
            next.references++;
            next.sourceReferences++;
        }
        ReverbBus next = state.sourceRoomBus;
        long now = System.nanoTime();
        next.activate(now);
        next.sourceField = true;
        if (next.sourceReferences == 1) {
            if (!key.equals(next.key)) {
                if (next.key != null) {
                    REVERB_BUSES.remove(next.key, next);
                }
                next.key = key;
                REVERB_BUSES.putIfAbsent(key, next);
            }
            evolveReverbBusBoundary(
                    next, state.sourceRoomProbe.acoustics(), now
            );
        }
        // A moving source and edited blocks alter this field's boundary, not the audio
        // already circulating in its feedback delays. Keep the assigned network and
        // replace only the current geometric observation and send spectrum.
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
        if (available == null) {
            available = createReverbBus(null, RoomAcoustics.OUTDOORS);
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
            Vec3 listenerPan,
            EarlyFieldMix mix
    ) {
        EarlyReflectionBus current = state.earlyReflectionBus;
        if (current != null && key.equals(current.key)) {
            return;
        }
        EarlyReflectionBus exact = EARLY_REFLECTION_BUSES.get(key);
        if (current != null && current.references == 1
                && (exact == null || exact == current)) {
            boolean keyChanged = current.key == null || !current.key.equals(key);
            if (keyChanged && current.key != null) {
                EARLY_REFLECTION_BUSES.remove(current.key, current);
            }
            configureEarlyReflectionBus(
                    current, key, reflection, listenerPan, mix
            );
            if (keyChanged) {
                EARLY_REFLECTION_BUSES.put(key, current);
            }
            return;
        }
        EarlyReflectionBus next = acquireEarlyReflectionBus(
                key, reflection, listenerPan, mix
        );
        if (next == null) {
            return;
        }
        if (state.earlyReflectionBus == next) {
            return;
        }
        releaseEarlyReflectionBus(state);
        state.earlyReflectionBus = next;
        next.references++;
        next.activate(System.nanoTime());
    }

    private static void releaseEarlyReflectionBus(SourceState state) {
        if (state.earlyReflectionBus == null) {
            return;
        }
        state.earlyReflectionBus.references--;
        long now = System.nanoTime();
        if (state.earlyReflectionBus.references == 0) {
            state.earlyReflectionBus.retire(now);
        } else {
            state.earlyReflectionBus.lastUsed = now;
        }
        state.earlyReflectionBus = null;
    }

    private static EarlyReflectionBus acquireEarlyReflectionBus(
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan,
            EarlyFieldMix mix
    ) {
        EarlyReflectionBus exact = EARLY_REFLECTION_BUSES.get(key);
        if (exact != null) {
            exact.activate(System.nanoTime());
            return exact;
        }
        long now = System.nanoTime();
        reapExpiredEarlyReflectionBuses(now);
        EarlyReflectionBus replaceable = EARLY_REFLECTION_POOL.stream()
                .filter(bus -> bus.canRecycle(now))
                .min(Comparator.comparingLong(bus -> bus.lastUsed))
                .orElse(null);
        if (replaceable != null) {
            if (replaceable.key != null) {
                EARLY_REFLECTION_BUSES.remove(replaceable.key, replaceable);
            }
            configureEarlyReflectionBus(
                    replaceable, key, reflection, listenerPan, mix
            );
            EARLY_REFLECTION_BUSES.put(key, replaceable);
            return replaceable;
        }
        EarlyReflectionBus created = createEarlyReflectionBus(
                key, reflection, listenerPan, mix
        );
        EARLY_REFLECTION_BUSES.put(key, created);
        return created;
    }

    private static void reapExpiredEarlyReflectionBuses(long now) {
        for (int index = EARLY_REFLECTION_POOL.size() - 1;
             index >= 0 && EARLY_REFLECTION_POOL.size() > PREWARMED_EARLY_REFLECTION_BUSES;
             index--) {
            EarlyReflectionBus bus = EARLY_REFLECTION_POOL.get(index);
            if (!bus.canRecycle(now)) {
                continue;
            }
            if (bus.key != null) {
                EARLY_REFLECTION_BUSES.remove(bus.key, bus);
            }
            EARLY_REFLECTION_POOL.remove(index);
            deleteEarlyReflectionBus(bus);
        }
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

    private static void reindexListenerReverbBus(ReverbBus bus, ReverbKey key) {
        if (key.equals(bus.key)) {
            return;
        }
        if (bus.key != null) {
            REVERB_BUSES.remove(bus.key, bus);
        }
        bus.key = key;
        REVERB_BUSES.put(key, bus);
    }

    /**
     * A stopped source-side EFX network can still contain diffuse energy. Its output is
     * transported from that fixed source field while it naturally decays. Listener
     * space changes never enter this path because they stay in one persistent network.
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
            bus.transportHighFrequencyGain = radiation.highFrequencyGain();
        }
        reapExpiredReverbBuses(now);
        publishTailFieldRequests(now);
    }

    private static void reapExpiredReverbBuses(long now) {
        for (int index = REVERB_POOL.size() - 1;
             index >= 0 && REVERB_POOL.size() > PREWARMED_REVERB_BUSES;
             index--) {
            ReverbBus bus = REVERB_POOL.get(index);
            if (bus == activeReverbBus || !bus.canRecycle(now)) {
                continue;
            }
            if (bus.key != null) {
                REVERB_BUSES.remove(bus.key, bus);
            }
            REVERB_POOL.remove(index);
            deleteReverbBus(bus);
        }
    }

    private static void publishTailFieldRequests(long now) {
        List<TailFieldRequest> requests = new ArrayList<>(REVERB_POOL.size());
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
            // Once the listener traverses the measured finite aperture, transport is
            // set by that aperture alone. The listener-room probe can remain
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
        bus.sceneRevision = Long.MIN_VALUE;
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
        bus.lastBoundaryUpdateNanoseconds = bus.lastUsed;
        bus.tailExpiresAtNanoseconds = 0L;
        bus.transportHighFrequencyGain = 1.0F;
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
            Vec3 listenerPan,
            EarlyFieldMix mix
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
        configureEarlyReflectionBus(bus, key, reflection, listenerPan, mix);
        EARLY_REFLECTION_POOL.add(bus);
        return bus;
    }

    private static void configureEarlyReflectionBus(
            EarlyReflectionBus bus,
            EarlyReflectionKey key,
            EarlyReflection reflection,
            Vec3 listenerPan,
            EarlyFieldMix mix
    ) {
        bus.key = key;
        int effect = bus.effect;
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, 0.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINLF, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, 0.1F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, 1.0F);
        EXTEfx.alEffectf(
                effect,
                EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN,
                mix.inputGain() > 0.0F ? 1.0F : 0.0F
        );
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
        EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_MODULATION_TIME, 0.25F);
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
        bus.reflectionDelaySeconds = reflection.delay();
        bus.tailExpiresAtNanoseconds = 0L;
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
        if (SoftwareAcousticMixer.available()) {
            return;
        }
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
        if (state.reverbFilter != 0) {
            EXTEfx.alDeleteFilters(state.reverbFilter);
        }
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
        } finally {
            AL10.alGetError();
        }
    }

    private static void resetState() {
        SOURCES.clear();
        SOURCE_MAX_DISTANCES.clear();
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
        targetListenerRoom = null;
        lastListenerRoomUpdateNanoseconds = 0L;
        lastListenerRoomSequence = Long.MIN_VALUE;
        lastDynamicListenerPosition = null;
        softwareListenerField = null;
        nextFieldToken = 0L;
        tailFieldRequests = List.of();
        appliedDistanceAttenuationSettings = null;
        atmosphereHighFrequencyCache = null;
        vanillaProcessing = false;
    }

    private static DistanceAttenuationSettings distanceAttenuationSettings() {
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        return new DistanceAttenuationSettings(
                tuning.realisticDistanceAttenuation(),
                (float) tuning.blocks(tuning.distanceReferenceMeters()),
                tuning.distanceRolloffFactor()
        );
    }

    private static void syncDistanceAttenuationSettings() {
        DistanceAttenuationSettings settings = distanceAttenuationSettings();
        if (settings.equals(appliedDistanceAttenuationSettings)) {
            return;
        }
        for (int source : SOURCES.keySet()) {
            applyDistanceAttenuation(source, settings);
        }
        appliedDistanceAttenuationSettings = settings;
    }

    private static void applyDistanceAttenuation(
            int source,
            DistanceAttenuationSettings settings
    ) {
        if (settings.realistic()) {
            // Direction and HRTF still use the source position. Only OpenAL's separate
            // gain law is disabled because the solver supplies one conserved transfer
            // gain to direct, reflection and late-field paths.
            AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
            return;
        }

        // Channel.linearAttenuation uses these values. Restoring them makes a live
        // resource reload reversible without recreating currently playing voices.
        AL10.alSourcei(
                source,
                AL10.AL_DISTANCE_MODEL,
                EXTLinearDistance.AL_LINEAR_DISTANCE_CLAMPED
        );
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.0F);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0F);
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

    private static void advanceListenerField(long nowNanoseconds) {
        if (smoothedListenerRoom == null || targetListenerRoom == null) {
            lastListenerRoomUpdateNanoseconds = nowNanoseconds;
            return;
        }
        if (lastListenerRoomUpdateNanoseconds == 0L
                || nowNanoseconds <= lastListenerRoomUpdateNanoseconds) {
            lastListenerRoomUpdateNanoseconds = nowNanoseconds;
            return;
        }
        double elapsedSeconds = (nowNanoseconds - lastListenerRoomUpdateNanoseconds)
                / 1_000_000_000.0;
        smoothedListenerRoom = DiffuseFieldDynamics.advance(
                smoothedListenerRoom,
                targetListenerRoom,
                elapsedSeconds
        );
        lastListenerRoomUpdateNanoseconds = nowNanoseconds;
        if (activeReverbBus != null) {
            morphReverbBus(activeReverbBus, smoothedListenerRoom);
        }
    }

    /**
     * Changes the boundary conditions of a live feedback field without replacing its
     * delay network. EFX effect objects are templates, so the slot assignment publishes
     * the continuously evolved coefficients to OpenAL while the samples already stored
     * in the slot remain the same acoustic energy.
     */
    private static void morphReverbBus(ReverbBus bus, RoomAcoustics room) {
        if (bus.room.equals(room)) {
            return;
        }
        if (advancedEaxReverb) {
            updateEaxReverb(bus.effect, room);
        } else {
            updateStandardReverb(bus.effect, room);
        }
        EXTEfx.alAuxiliaryEffectSloti(
                bus.slot,
                EXTEfx.AL_EFFECTSLOT_EFFECT,
                bus.effect
        );
        bus.room = room;
    }

    private static void evolveReverbBusBoundary(
            ReverbBus bus,
            RoomAcoustics boundary,
            long nowNanoseconds
    ) {
        if (bus.lastBoundaryUpdateNanoseconds == 0L
                || nowNanoseconds <= bus.lastBoundaryUpdateNanoseconds) {
            bus.lastBoundaryUpdateNanoseconds = nowNanoseconds;
            return;
        }
        double elapsedSeconds = (nowNanoseconds - bus.lastBoundaryUpdateNanoseconds)
                / 1_000_000_000.0;
        RoomAcoustics evolved = DiffuseFieldDynamics.advance(
                bus.room, boundary, elapsedSeconds
        );
        bus.lastBoundaryUpdateNanoseconds = nowNanoseconds;
        morphReverbBus(bus, evolved);
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

    // These slow coefficients identify one persistent feedback network. Its complete
    // EFX snapshot stays immutable until a genuine field handover. Continuous path and
    // portal transport is applied through send filters and slot gain instead; repeatedly
    // reassigning the effect makes OpenAL Soft continuously transition environments and
    // can prevent a stable late field from forming.
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

    private record DistanceAttenuationSettings(
            boolean realistic,
            float referenceDistanceBlocks,
            float rolloffFactor
    ) {
    }

    private record AtmosphereHighFrequencyCache(
            float temperatureCelsius,
            float humidityPercent,
            float pressureKilopascals,
            float absorptionScale,
            double fourKilohertzNepersPerMeter,
            double eightKilohertzNepersPerMeter
    ) {
        private static AtmosphereHighFrequencyCache from(AcousticTuning tuning) {
            return new AtmosphereHighFrequencyCache(
                    tuning.airTemperatureCelsius(),
                    tuning.relativeHumidityPercent(),
                    tuning.airPressureKilopascals(),
                    tuning.airAbsorptionScale(),
                    AtmosphericAbsorption.amplitudeNepersPerMeter(
                            AcousticBands.CENTERS_HZ[6],
                            tuning.airTemperatureCelsius(),
                            tuning.relativeHumidityPercent(),
                            tuning.airPressureKilopascals()
                    ) * tuning.airAbsorptionScale(),
                    AtmosphericAbsorption.amplitudeNepersPerMeter(
                            AcousticBands.CENTERS_HZ[7],
                            tuning.airTemperatureCelsius(),
                            tuning.relativeHumidityPercent(),
                            tuning.airPressureKilopascals()
                    ) * tuning.airAbsorptionScale()
            );
        }

        private boolean matches(AcousticTuning tuning) {
            return Float.compare(temperatureCelsius, tuning.airTemperatureCelsius()) == 0
                    && Float.compare(humidityPercent, tuning.relativeHumidityPercent()) == 0
                    && Float.compare(pressureKilopascals, tuning.airPressureKilopascals()) == 0
                    && Float.compare(absorptionScale, tuning.airAbsorptionScale()) == 0;
        }
    }

    private record EarlyFieldMix(
            float inputGain,
            float lowFrequencyGain,
            float highFrequencyGain
    ) {
        private static final EarlyFieldMix SILENT = new EarlyFieldMix(
                0.0F, 1.0F, 1.0F
        );

        private static EarlyFieldMix from(SourceState state) {
            float reflectionInput = state.earlyReflection.gain()
                    * state.propagationGain * state.audibilityGain;
            return reflectionInput <= 0.0F
                    ? SILENT
                    : new EarlyFieldMix(
                            reflectionInput,
                            state.earlyReflection.lowFrequencyGain(),
                            state.earlyReflection.highFrequencyGain()
                    );
        }
    }

    private record EarlyReflectionKey(
            int panX,
            int panY,
            int panZ,
            int delay
    ) {
        private static EarlyReflectionKey from(
                EarlyReflection reflection,
                Vec3 listenerPan
        ) {
            return new EarlyReflectionKey(
                    Float.floatToIntBits((float) listenerPan.x),
                    Float.floatToIntBits((float) listenerPan.y),
                    Float.floatToIntBits((float) listenerPan.z),
                    Float.floatToIntBits(reflection.delay())
            );
        }

    }

    private static double square(int value) {
        return (double) value * value;
    }

    private static final class ReverbBus {
        private ReverbKey key;
        private RoomAcoustics room;
        private ListenerFieldSnapshot field;
        private final int effect;
        private final int slot;
        private int references;
        private int sourceReferences;
        private boolean sourceField;
        private long fieldToken;
        private long sceneRevision = Long.MIN_VALUE;
        private long lastUsed;
        private long tailExpiresAtNanoseconds;
        private float outputGain;
        private float transportHighFrequencyGain = 1.0F;
        private long lastOutputGainUpdateNanoseconds;
        private long lastBoundaryUpdateNanoseconds;

        private ReverbBus(ReverbKey key, RoomAcoustics room, int effect, int slot) {
            this.key = key;
            this.room = room;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
            this.outputGain = clamp(room.gain(), 0.0F, 1.0F);
            this.lastOutputGainUpdateNanoseconds = lastUsed;
            this.lastBoundaryUpdateNanoseconds = lastUsed;
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
            if (listenerOwnedField) {
                boolean crossedOpening = false;
                for (Aperture aperture : apertures) {
                    if (segmentCrossesAperture(
                            listener,
                            observation,
                            aperture.point(),
                            aperture.outward(),
                            aperture.area()
                    )) {
                        crossedOpening = true;
                        break;
                    }
                }
                if (!crossedOpening) {
                    // A listener leaves this field only by traversing the finite opening.
                    // Moving beyond the opening's infinite supporting plane alongside a
                    // wall must not discard the room's feedback network.
                    return PortalRadiation.INSIDE;
                }
            }
            double receivedPowerFraction = 0.0;
            double receivedHighFrequencyPower = 0.0;
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
                float highFrequencyGain = atmosphericHighFrequencyGain(
                        Math.sqrt(distanceSquared)
                );
                receivedHighFrequencyPower += powerFraction
                        * highFrequencyGain * highFrequencyGain;
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
            float highFrequencyGain = receivedPowerFraction <= 1.0E-12
                    ? 1.0F
                    : clamp(
                            (float) Math.sqrt(
                                    receivedHighFrequencyPower / receivedPowerFraction
                            ),
                            0.0F,
                            1.0F
                    );
            return new PortalRadiation(
                    transmission, arrivalDirection, true, highFrequencyGain
            );
        }
    }

    private record Aperture(Vec3 point, Vec3 outward, double area) {
    }

    private record PortalRadiation(
            float transmission,
            Vec3 arrivalDirection,
            boolean outside,
            float highFrequencyGain
    ) {
        private static final PortalRadiation NONE = new PortalRadiation(
                0.0F, Vec3.ZERO, true, 1.0F
        );
        private static final PortalRadiation INSIDE = new PortalRadiation(
                1.0F, Vec3.ZERO, false, 1.0F
        );
    }

    private static float atmosphericHighFrequencyGain(double distanceBlocks) {
        AcousticTuning tuning = AcousticMaterialRegistry.tuning();
        double distanceMeters = tuning.meters(distanceBlocks);
        AtmosphereHighFrequencyCache cache = atmosphereHighFrequencyCache;
        if (cache == null || !cache.matches(tuning)) {
            cache = AtmosphereHighFrequencyCache.from(tuning);
            atmosphereHighFrequencyCache = cache;
        }
        float fourGain = AtmosphericAbsorption.amplitudeGain(
                cache.fourKilohertzNepersPerMeter(), distanceMeters
        );
        float eightGain = AtmosphericAbsorption.amplitudeGain(
                cache.eightKilohertzNepersPerMeter(), distanceMeters
        );
        return (float) Math.sqrt(fourGain * eightGain);
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

    static boolean segmentCrossesAperture(
            Vec3 start,
            Vec3 end,
            Vec3 aperturePoint,
            Vec3 apertureNormal,
            double area
    ) {
        if (area <= 0.0 || apertureNormal.lengthSqr() <= 1.0E-12) {
            return false;
        }
        Vec3 normal = apertureNormal.normalize();
        double startSide = start.subtract(aperturePoint).dot(normal);
        double endSide = end.subtract(aperturePoint).dot(normal);
        if (startSide > 0.0 || endSide <= 0.0) {
            return false;
        }
        double denominator = startSide - endSide;
        if (Math.abs(denominator) <= 1.0E-12) {
            return false;
        }
        double fraction = startSide / denominator;
        Vec3 intersection = start.lerp(end, fraction);
        Vec3 fromCenter = intersection.subtract(aperturePoint);
        Vec3 radial = fromCenter.subtract(normal.scale(fromCenter.dot(normal)));
        return radial.lengthSqr() <= area / Math.PI;
    }

    private static final class EarlyReflectionBus {
        private EarlyReflectionKey key;
        private final int effect;
        private final int slot;
        private int references;
        private long lastUsed;
        private long tailExpiresAtNanoseconds;
        private float reflectionDelaySeconds;

        private EarlyReflectionBus(EarlyReflectionKey key, int effect, int slot) {
            this.key = key;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
        }

        private void activate(long now) {
            tailExpiresAtNanoseconds = 0L;
            lastUsed = now;
        }

        private void retire(long now) {
            lastUsed = now;
            double tailSeconds = Math.max(0.0F, reflectionDelaySeconds);
            long tailNanoseconds = (long) Math.ceil(tailSeconds * 1_000_000_000.0);
            tailExpiresAtNanoseconds = now > Long.MAX_VALUE - tailNanoseconds
                    ? Long.MAX_VALUE
                    : now + tailNanoseconds;
        }

        private boolean canRecycle(long now) {
            return references == 0 && now >= tailExpiresAtNanoseconds;
        }
    }

    private static final class SourceState {
        private final int directFilter;
        private final int reverbFilter;
        private final int sourceRoomFilter;
        private final int earlyReflectionFilter;
        private final float maximumDistance;
        private ReverbBus reverbBus;
        private ReverbBus sourceRoomBus;
        // The bus currently bound to the primary late send. Retaining this explicit
        // ownership also makes accidental callback-driven routing changes observable.
        private ReverbBus primaryLateBus;
        private EarlyReflectionBus earlyReflectionBus;
        private float directGain = 1.0F;
        private float lowFrequencyGain = 1.0F;
        private float highFrequencyGain = 1.0F;
        private float reverbSend = 0.04F;
        private float reverbHighFrequency = 1.0F;
        private float sourceRoomSend;
        private float sourceRoomHighFrequency = 1.0F;
        private RoomProbe sourceRoomProbe;
        private Vec3 sourcePosition;
        private EarlyReflection earlyReflection = EarlyReflection.SILENT;
        private DirectionalArrivalField directionalField = DirectionalArrivalField.EMPTY;
        private DirectionalArrivalField previousDirectionalField = DirectionalArrivalField.EMPTY;
        private DirectionalArrivalField earlyDirectionalField = DirectionalArrivalField.EMPTY;
        private DirectionalArrivalField previousEarlyDirectionalField = DirectionalArrivalField.EMPTY;
        private Vec3 earlyFallbackDirection = Vec3.ZERO;
        private Vec3 previousEarlyFallbackDirection = Vec3.ZERO;
        private Vec3 apparentPosition = Vec3.ZERO;
        private Vec3 dynamicEarlyDirection = Vec3.ZERO;
        private double propagationDistance;
        private float propagationGain = 1.0F;
        private float audibilityGain = 1.0F;
        private boolean initialized;
        private long lastUpdateNanoseconds;
        private long directionalTransitionStartedNanoseconds;
        private long earlyDirectionalTransitionStartedNanoseconds;
        private long lastResultSequence = Long.MIN_VALUE;

        private SourceState(
                int directFilter,
                int reverbFilter,
                int sourceRoomFilter,
                int earlyReflectionFilter,
                float maximumDistance
        ) {
            this.directFilter = directFilter;
            this.reverbFilter = reverbFilter;
            this.sourceRoomFilter = sourceRoomFilter;
            this.earlyReflectionFilter = earlyReflectionFilter;
            this.maximumDistance = maximumDistance;
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
                directGain = targetDirectGain(target);
                lowFrequencyGain = targetLowFrequencyGain(target);
                highFrequencyGain = targetHighFrequencyGain(target);
                reverbSend = target.reverbSend();
                reverbHighFrequency = target.reverbHighFrequencyGain();
                updateSourceRoomTarget(target);
                earlyReflection = target.earlyReflection();
                directionalField = target.directionalField();
                previousDirectionalField = directionalField;
                earlyDirectionalField = earlyReflection.directionalField();
                previousEarlyDirectionalField = earlyDirectionalField;
                earlyFallbackDirection = normalizedOrZero(earlyReflection.arrivalDirection());
                previousEarlyFallbackDirection = earlyFallbackDirection;
                apparentPosition = target.apparentPosition();
                dynamicEarlyDirection = earlyFallbackDirection;
                propagationDistance = target.propagationDistance();
                propagationGain = targetPropagationGain(target);
                audibilityGain = targetAudibilityGain(target);
                initialized = true;
                lastUpdateNanoseconds = now;
                directionalTransitionStartedNanoseconds = 0L;
                earlyDirectionalTransitionStartedNanoseconds = 0L;
                return true;
            }
            float amount = elapsedResponseAmount(lastUpdateNanoseconds, now);
            directGain = lerp(directGain, targetDirectGain(target), amount);
            lowFrequencyGain = lerp(
                    lowFrequencyGain,
                    targetLowFrequencyGain(target),
                    amount
            );
            highFrequencyGain = lerp(
                    highFrequencyGain,
                    targetHighFrequencyGain(target),
                    amount
            );
            reverbSend = lerp(reverbSend, target.reverbSend(), amount);
            reverbHighFrequency = lerp(
                    reverbHighFrequency,
                    target.reverbHighFrequencyGain(),
                    amount
            );
            updateSourceRoomTarget(target);
            EarlyReflection targetReflection = target.earlyReflection();
            beginDirectionalTransition(target.directionalField(), now);
            beginEarlyDirectionalTransition(targetReflection, now);
            earlyReflection = new EarlyReflection(
                    lerp(earlyReflection.gain(), targetReflection.gain(), amount),
                    lerp(
                            earlyReflection.lowFrequencyGain(),
                            targetReflection.lowFrequencyGain(),
                            amount
                    ),
                    lerp(
                            earlyReflection.highFrequencyGain(),
                            targetReflection.highFrequencyGain(),
                            amount
                    ),
                    lerp(earlyReflection.delay(), targetReflection.delay(), amount),
                    earlyReflection.arrivalDirection().lerp(
                            targetReflection.arrivalDirection(), amount
                    ),
                    targetReflection.directionalField()
            );
            propagationDistance = propagationDistance
                    + (target.propagationDistance() - propagationDistance) * amount;
            propagationGain = lerp(
                    propagationGain,
                    targetPropagationGain(target),
                    amount
            );
            audibilityGain = lerp(
                    audibilityGain,
                    targetAudibilityGain(target),
                    amount
            );
            lastUpdateNanoseconds = now;
            return true;
        }

        private void reprojectSpatial(Vec3 listener, long now) {
            if (!initialized) {
                return;
            }
            float directionalMix = spatialTransitionProgress(
                    directionalTransitionStartedNanoseconds, now
            );
            Vec3 propagationDirection = powerCrossfadedDirection(
                    directionFrom(previousDirectionalField, Vec3.ZERO, listener),
                    directionFrom(directionalField, Vec3.ZERO, listener),
                    directionalMix
            );
            apparentPosition = listener.add(propagationDirection.scale(
                    Math.max(1.0, propagationDistance)
            ));
            if (directionalMix >= 1.0F) {
                previousDirectionalField = directionalField;
                directionalTransitionStartedNanoseconds = 0L;
            }

            float earlyMix = spatialTransitionProgress(
                    earlyDirectionalTransitionStartedNanoseconds, now
            );
            dynamicEarlyDirection = powerCrossfadedDirection(
                    directionFrom(
                            previousEarlyDirectionalField,
                            previousEarlyFallbackDirection,
                            listener
                    ),
                    directionFrom(
                            earlyDirectionalField,
                            earlyFallbackDirection,
                            listener
                    ),
                    earlyMix
            );
            if (earlyMix >= 1.0F) {
                previousEarlyDirectionalField = earlyDirectionalField;
                previousEarlyFallbackDirection = earlyFallbackDirection;
                earlyDirectionalTransitionStartedNanoseconds = 0L;
            }
        }

        private void beginDirectionalTransition(
                DirectionalArrivalField target,
                long now
        ) {
            if (directionalField.equals(target)) {
                return;
            }
            previousDirectionalField = directionalField;
            directionalField = target;
            directionalTransitionStartedNanoseconds = now;
        }

        private void beginEarlyDirectionalTransition(
                EarlyReflection target,
                long now
        ) {
            DirectionalArrivalField targetField = target.directionalField();
            Vec3 targetFallback = normalizedOrZero(target.arrivalDirection());
            boolean fieldChanged = !earlyDirectionalField.equals(targetField);
            boolean fallbackChanged = !earlyDirectionalField.hasArrivals()
                    && !targetField.hasArrivals()
                    && earlyFallbackDirection.distanceToSqr(targetFallback) > 1.0E-10;
            if (!fieldChanged && !fallbackChanged) {
                return;
            }
            previousEarlyDirectionalField = earlyDirectionalField;
            previousEarlyFallbackDirection = earlyFallbackDirection;
            earlyDirectionalField = targetField;
            earlyFallbackDirection = targetFallback;
            earlyDirectionalTransitionStartedNanoseconds = now;
        }

        private static Vec3 directionFrom(
                DirectionalArrivalField field,
                Vec3 fallback,
                Vec3 listener
        ) {
            if (field.hasArrivals() || field.fallbackSource() != null) {
                return field.apparentDirection(listener);
            }
            return normalizedOrZero(fallback);
        }

        private static Vec3 powerCrossfadedDirection(Vec3 previous, Vec3 target, float amount) {
            float mix = clamp(amount, 0.0F, 1.0F);
            if (mix <= 0.0F) {
                return normalizedOrZero(previous);
            }
            if (mix >= 1.0F) {
                return normalizedOrZero(target);
            }
            Vec3 first = normalizedOrZero(previous);
            Vec3 second = normalizedOrZero(target);
            if (first.lengthSqr() <= 1.0E-12) {
                return second;
            }
            if (second.lengthSqr() <= 1.0E-12) {
                return first;
            }
            // Arrival-field weights are acoustic power. Complementary linear power
            // weights keep the transition energy-neutral; only localization changes.
            Vec3 intensity = first.scale(1.0F - mix).add(second.scale(mix));
            if (intensity.lengthSqr() <= 1.0E-12) {
                return mix < 0.5F ? first : second;
            }
            return intensity.normalize();
        }

        private static float spatialTransitionProgress(long started, long now) {
            if (started == 0L || now <= started) {
                return started == 0L ? 1.0F : 0.0F;
            }
            double durationMilliseconds = AcousticMaterialRegistry.tuning()
                    .acousticResponseTimeMilliseconds();
            double elapsedMilliseconds = (now - started) / 1_000_000.0;
            return (float) Math.min(1.0, elapsedMilliseconds / durationMilliseconds);
        }

        private static Vec3 normalizedOrZero(Vec3 value) {
            return value == null || value.lengthSqr() <= 1.0E-12
                    ? Vec3.ZERO
                    : value.normalize();
        }

        private static float targetDirectGain(AcousticResult target) {
            return clamp(target.directGain(), 0.0F, 1.0F);
        }

        private static float targetLowFrequencyGain(AcousticResult target) {
            float anchor = Math.max(targetDirectGain(target), 0.001F);
            return clamp(target.lowBandGain() / anchor, 0.0F, 1.0F);
        }

        private static float targetHighFrequencyGain(AcousticResult target) {
            float anchor = Math.max(targetDirectGain(target), 0.001F);
            return Math.min(
                    clamp(target.highBandGain() / anchor, 0.0F, 1.0F),
                    clamp(target.highFrequencyGain(), 0.0F, 1.0F)
            );
        }

        private float audibilityGain(double propagationDistance) {
            if (!Float.isFinite(maximumDistance) || maximumDistance <= 0.0F) {
                return 1.0F;
            }
            // Minecraft's authored attenuation distance is the only source-level/SPL
            // metadata available. Treat it as a smooth perceptual knee, not a cutoff:
            // inside it the physical 1/r law is unchanged, while inaudible far-field
            // loops asymptotically fall below the listening floor without a hard edge.
            double ratio = Math.max(0.0, propagationDistance) / maximumDistance;
            double fourthPower = ratio * ratio * ratio * ratio;
            return (float) (1.0 / Math.sqrt(1.0 + fourthPower * fourthPower));
        }

        private float targetPropagationGain(AcousticResult target) {
            return AcousticMaterialRegistry.tuning().realisticDistanceAttenuation()
                    ? target.propagationGain()
                    : 1.0F;
        }

        private float targetAudibilityGain(AcousticResult target) {
            return AcousticMaterialRegistry.tuning().realisticDistanceAttenuation()
                    ? audibilityGain(target.propagationDistance())
                    : 1.0F;
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
