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
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;
import org.macaroon.acousticsystem.client.simulation.RoomImpulseResponse;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

public final class OpenALAcousticEffects {
    private static final Map<Integer, SourceState> SOURCES = new HashMap<>();
    private static final Map<ReverbKey, ReverbBus> REVERB_BUSES = new HashMap<>();
    private static final List<ReverbBus> REVERB_POOL = new ArrayList<>();
    private static final Map<EqualizerKey, EqualizerBus> EQUALIZER_BUSES = new HashMap<>();
    // Listener-space transitions use two EAX networks. New energy is routed into the
    // freshly configured field while the previous network releases its existing tail.
    // This is constant-cost double buffering, not per-source convolution or an
    // unbounded room cache.
    private static final int MAX_REVERB_BUSES = 2;
    private static final int MAX_EQUALIZER_BUSES = 12;
    private static final RoomAcoustics DSP_PREWARM_ROOM = new RoomAcoustics(
            0.75F, 0.70F, 0.32F, 0.72F, 0.95F,
            1.8F, 0.65F, 1.15F,
            0.28F, 0.018F, Vec3.ZERO,
            1.2F, 0.032F, Vec3.ZERO,
            0.6F, 0.08F, 0.94F
    );
    private static boolean supportChecked;
    private static boolean supported;
    private static boolean advancedFourBand;
    private static ReverbBus activeReverbBus;
    private static RoomAcoustics smoothedListenerRoom;
    private static long lastListenerRoomUpdateNanoseconds;
    private static long lastListenerRoomSequence = Long.MIN_VALUE;

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
                    0.0F, 1.0F, 0.0F,
                    0.0,
                    Vec3.ZERO,
                    RoomAcoustics.OUTDOORS,
                    RoomImpulseResponse.SILENT
            ));
            AL10.alSourcePlay(source);
            while (REVERB_POOL.size() < MAX_REVERB_BUSES) {
                createReverbBus(null, RoomAcoustics.OUTDOORS);
            }
            // OpenAL Soft may lazily instantiate the actual reverb network the first
            // time an audible room is committed to the slot. Exercise that transition
            // during library startup. The listener probe is the only runtime owner of
            // this shared room; individual voices only attach to it.
            updateListenerRoomInternal(RoomAcoustics.OUTDOORS, false);
            apply(source, new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F, 0.0F,
                    0.0, Vec3.ZERO, RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT
            ));
            updateListenerRoomInternal(DSP_PREWARM_ROOM, false);
            apply(source, new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.0F, 1.0F, 0.0F,
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
        // The shared listener field and each OpenAL source have independent ordering.
        // A callback can be older than the room already committed by another channel
        // while still being the newest result that this particular source has received.
        // Rejecting the whole callback here starved slower channels indefinitely during
        // initial world entry. The room update ignores its own stale generations; the
        // source update below independently ignores only generations stale for itself.
        updateListenerRoomInternal(target.reverbRoom(), true, sequence, true);
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
        updateListenerRoomInternal(room, interpolate, Long.MIN_VALUE, false);
    }

    private static boolean updateListenerRoomInternal(
            RoomAcoustics room,
            boolean interpolate,
            long sequence,
            boolean ordered
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
            updatePersistentReverbBus(
                    ReverbKey.from(smoothedListenerRoom),
                    smoothedListenerRoom
            );
            EXTEfx.alAuxiliaryEffectSlotf(
                    activeReverbBus.slot,
                    EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp(smoothedListenerRoom.gain(), 0.0F, 1.0F)
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

            if (advancedFourBand) {
                applyFourBand(source, state);
            } else {
                applyCompatible(source, state);
            }
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
            for (EqualizerBus bus : EQUALIZER_BUSES.values()) {
                deleteEqualizerBus(bus);
            }
        }
        resetState();
    }

    private static void applyFourBand(int source, SourceState state) {
        float minimum = Math.min(Math.min(state.lowBand, state.midLowBand), Math.min(state.midHighBand, state.highBand));
        float maximum = Math.max(Math.max(state.lowBand, state.midLowBand), Math.max(state.midHighBand, state.highBand));
        float span = Math.max(0.0F, maximum - minimum);

        // A broadband common component remains on OpenAL's direct path, preserving the
        // game's normal positional/HRTF processing. Only the spectral remainder is EQ'd.
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAIN, clamp(minimum, 0.0F, 1.0F));
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAINHF, 1.0F);
        EXTEfx.alFilterf(
                state.bandFilter,
                EXTEfx.AL_LOWPASS_GAIN,
                clamp(
                        span * directAuxiliaryDistanceGain(source, state.propagationDistance),
                        0.0F,
                        1.0F
                )
        );
        EXTEfx.alFilterf(state.bandFilter, EXTEfx.AL_LOWPASS_GAINHF, 1.0F);

        float divisor = Math.max(span, 1.0E-4F);
        assignEqualizerBus(state, new EqualizerKey(
                quantize((state.lowBand - minimum) / divisor, 8.0F),
                quantize((state.midLowBand - minimum) / divisor, 8.0F),
                quantize((state.midHighBand - minimum) / divisor, 8.0F),
                quantize((state.highBand - minimum) / divisor, 8.0F)
        ));

        updateReverbFilter(state);
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, state.directFilter);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, state.equalizerBus.slot, 0, state.bandFilter);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, state.reverbBus.slot, 1, state.reverbFilter);
    }

    private static void applyCompatible(int source, SourceState state) {
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAIN, clamp(state.directGain, 0.0F, 1.0F));
        EXTEfx.alFilterf(state.directFilter, EXTEfx.AL_LOWPASS_GAINHF, clamp(state.highFrequencyGain, 0.0F, 1.0F));
        updateReverbFilter(state);
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, state.directFilter);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, state.reverbBus.slot, 0, state.reverbFilter);
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
        advancedFourBand = maximumSends >= 2;
        if (advancedFourBand) {
            AcousticSystem.LOGGER.info("Unified OpenAL EFX acoustic pipeline enabled with four-band EQ and {} auxiliary sends", maximumSends);
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
        if (!advancedFourBand) {
            return new SourceState(directFilter, reverbFilter, 0);
        }

        int bandFilter = createLowPass(0.0F, 1.0F);
        return new SourceState(directFilter, reverbFilter, bandFilter);
    }

    private static int createLowPass(float gain, float highFrequencyGain) {
        int filter = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, gain);
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, highFrequencyGain);
        return filter;
    }

    private static float directAuxiliaryDistanceGain(int source, double propagationDistance) {
        int model = AL10.alGetInteger(AL10.AL_DISTANCE_MODEL);
        if (model == AL10.AL_NONE) {
            return 1.0F;
        }

        double referenceDistance = Math.max(1.0E-4, AL10.alGetSourcef(source, AL10.AL_REFERENCE_DISTANCE));
        double maximumDistance = Math.max(referenceDistance, AL10.alGetSourcef(source, AL10.AL_MAX_DISTANCE));
        double rolloff = Math.max(0.0, AL10.alGetSourcef(source, AL10.AL_ROLLOFF_FACTOR));
        double distance = Math.max(0.0, propagationDistance);
        if (model == AL10.AL_INVERSE_DISTANCE_CLAMPED
                || model == AL11.AL_LINEAR_DISTANCE_CLAMPED
                || model == AL11.AL_EXPONENT_DISTANCE_CLAMPED) {
            distance = Math.max(referenceDistance, Math.min(maximumDistance, distance));
        }

        double gain;
        if (model == AL10.AL_INVERSE_DISTANCE || model == AL10.AL_INVERSE_DISTANCE_CLAMPED) {
            gain = referenceDistance
                    / Math.max(1.0E-6, referenceDistance + rolloff * (distance - referenceDistance));
        } else if (model == AL11.AL_LINEAR_DISTANCE || model == AL11.AL_LINEAR_DISTANCE_CLAMPED) {
            double range = maximumDistance - referenceDistance;
            gain = range <= 1.0E-6
                    ? 1.0
                    : 1.0 - rolloff * (distance - referenceDistance) / range;
        } else if (model == AL11.AL_EXPONENT_DISTANCE || model == AL11.AL_EXPONENT_DISTANCE_CLAMPED) {
            gain = Math.pow(Math.max(distance, 1.0E-6) / referenceDistance, -rolloff);
        } else {
            gain = 1.0;
        }
        return clamp((float) gain, 0.0F, 1.0F);
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

        ReverbBus next = REVERB_BUSES.get(key);
        if (next == null) {
            next = REVERB_POOL.stream()
                    .filter(candidate -> candidate != previous && candidate.references == 0)
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
        // Move every live voice in the same sound-thread transaction. The old field is
        // no longer fed but its slot stays audible, so its stored energy decays with the
        // RT60 measured in the previous space instead of being cut or frozen.
        for (Map.Entry<Integer, SourceState> entry : SOURCES.entrySet()) {
            SourceState state = entry.getValue();
            assignReverbBus(state);
            AL11.alSource3i(
                    entry.getKey(),
                    EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    next.slot,
                    advancedFourBand ? 1 : 0,
                    state.reverbFilter
            );
        }
        previous.lastUsed = System.nanoTime();
    }

    private static ReverbBus createReverbBus(
            ReverbKey key,
            RoomAcoustics room
    ) {
        int effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE,
                advancedFourBand ? EXTEfx.AL_EFFECT_EAXREVERB : EXTEfx.AL_EFFECT_REVERB);
        if (advancedFourBand) {
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
        ReverbBus bus = new ReverbBus(key, effect, slot);
        REVERB_POOL.add(bus);
        return bus;
    }

    private static void configureReverbBus(
            ReverbBus bus,
            ReverbKey key,
            RoomAcoustics room
    ) {
        bus.key = key;
        if (advancedFourBand) {
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
    }

    private static void assignEqualizerBus(SourceState state, EqualizerKey key) {
        if (state.equalizerBus != null && state.equalizerBus.key.equals(key)) {
            return;
        }
        EqualizerBus next = acquireEqualizerBus(key);
        if (state.equalizerBus == next) {
            return;
        }
        if (state.equalizerBus != null) {
            state.equalizerBus.references--;
        }
        state.equalizerBus = next;
        next.references++;
        next.lastUsed = System.nanoTime();
    }

    private static EqualizerBus acquireEqualizerBus(EqualizerKey key) {
        EqualizerBus exact = EQUALIZER_BUSES.get(key);
        if (exact != null) {
            return exact;
        }
        if (EQUALIZER_BUSES.size() < MAX_EQUALIZER_BUSES) {
            EqualizerBus created = createEqualizerBus(key);
            EQUALIZER_BUSES.put(key, created);
            return created;
        }
        EqualizerBus replaceable = EQUALIZER_BUSES.values().stream()
                .filter(bus -> bus.references == 0)
                .min(Comparator.comparingLong(bus -> bus.lastUsed))
                .orElse(null);
        if (replaceable != null) {
            EQUALIZER_BUSES.remove(replaceable.key);
            deleteEqualizerBus(replaceable);
            EqualizerBus created = createEqualizerBus(key);
            EQUALIZER_BUSES.put(key, created);
            return created;
        }
        return EQUALIZER_BUSES.values().stream()
                .min(Comparator.comparingDouble(bus -> bus.key.distanceTo(key)))
                .orElseThrow();
    }

    private static EqualizerBus createEqualizerBus(EqualizerKey key) {
        int effect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EQUALIZER);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_LOW_CUTOFF, 200.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID1_CENTER, 650.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID1_WIDTH, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID2_CENTER, 3000.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID2_WIDTH, 1.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_HIGH_CUTOFF, 6000.0F);
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_LOW_GAIN, equalizerGain(key.low / 8.0F));
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID1_GAIN, equalizerGain(key.midLow / 8.0F));
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_MID2_GAIN, equalizerGain(key.midHigh / 8.0F));
        EXTEfx.alEffectf(effect, EXTEfx.AL_EQUALIZER_HIGH_GAIN, equalizerGain(key.high / 8.0F));
        int slot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(
                slot,
                EXTEfx.AL_EFFECTSLOT_AUXILIARY_SEND_AUTO,
                AL10.AL_FALSE
        );
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
        return new EqualizerBus(key, effect, slot);
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
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, clamp(room.reflectionsGain(), 0.0F, 3.16F));
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, clamp(room.reflectionsDelay(), 0.0F, 0.3F));
        EXTEfx.alEffectfv(reverbEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_PAN, vector(room.reflectionsPan()));
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
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
        if (advancedFourBand) {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                    EXTEfx.AL_EFFECTSLOT_NULL, 1, EXTEfx.AL_FILTER_NULL);
        }
    }

    private static void deleteSourceState(SourceState state) {
        EXTEfx.alDeleteFilters(state.directFilter);
        EXTEfx.alDeleteFilters(state.reverbFilter);
        if (state.bandFilter != 0) {
            EXTEfx.alDeleteFilters(state.bandFilter);
        }
        if (state.reverbBus != null) {
            state.reverbBus.references--;
            // A stopped one-shot no longer feeds this slot, while its already generated
            // acoustic energy remains audible until the room's own decay completes.
        }
        if (state.equalizerBus != null) {
            state.equalizerBus.references--;
        }
    }

    private static void deleteReverbBus(ReverbBus bus) {
        EXTEfx.alDeleteAuxiliaryEffectSlots(bus.slot);
        EXTEfx.alDeleteEffects(bus.effect);
    }

    private static void deleteEqualizerBus(EqualizerBus bus) {
        EXTEfx.alDeleteAuxiliaryEffectSlots(bus.slot);
        EXTEfx.alDeleteEffects(bus.effect);
    }

    private static float[] vector(Vec3 vector) {
        return new float[]{(float) vector.x, (float) vector.y, (float) vector.z};
    }

    private static float equalizerGain(float normalized) {
        return clamp(normalized, EXTEfx.AL_EQUALIZER_MIN_LOW_GAIN, 1.0F);
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
        EQUALIZER_BUSES.clear();
        supportChecked = false;
        supported = false;
        advancedFourBand = false;
        activeReverbBus = null;
        smoothedListenerRoom = null;
        lastListenerRoomUpdateNanoseconds = 0L;
        lastListenerRoomSequence = Long.MIN_VALUE;
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

    private record RoomKey(
            int density,
            int diffusion,
            int gain,
            int highFrequency,
            int lowFrequency,
            int decay,
            int decayHigh,
            int decayLow,
            int reflections,
            int reflectionDelay,
            int late,
            int lateDelay
    ) {
        private static RoomKey from(RoomAcoustics room) {
            return new RoomKey(
                    quantize(room.density(), 20.0F),
                    quantize(room.diffusion(), 20.0F),
                    quantize(room.gain(), 40.0F),
                    quantize(room.gainHighFrequency(), 20.0F),
                    quantize(room.gainLowFrequency(), 20.0F),
                    quantize(room.decayTime(), 10.0F),
                    quantize(room.decayHighFrequencyRatio(), 10.0F),
                    quantize(room.decayLowFrequencyRatio(), 10.0F),
                    quantize(room.reflectionsGain(), 40.0F),
                    quantize(room.reflectionsDelay(), 100.0F),
                    quantize(room.lateReverbGain(), 10.0F),
                    quantize(room.lateReverbDelay(), 100.0F)
            );
        }

        private double distanceTo(RoomKey other) {
            return square(density - other.density)
                    + square(diffusion - other.diffusion)
                    + square(gain - other.gain)
                    + square(highFrequency - other.highFrequency)
                    + square(lowFrequency - other.lowFrequency)
                    + square(decay - other.decay)
                    + square(decayHigh - other.decayHigh)
                    + square(decayLow - other.decayLow)
                    + square(reflections - other.reflections)
                    + square(reflectionDelay - other.reflectionDelay)
                    + square(late - other.late)
                    + square(lateDelay - other.lateDelay);
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

    private record EqualizerKey(int low, int midLow, int midHigh, int high) {
        private double distanceTo(EqualizerKey other) {
            return square(low - other.low)
                    + square(midLow - other.midLow)
                    + square(midHigh - other.midHigh)
                    + square(high - other.high);
        }
    }

    private static double square(int value) {
        return (double) value * value;
    }

    private static final class ReverbBus {
        private ReverbKey key;
        private final int effect;
        private final int slot;
        private int references;
        private long lastUsed;

        private ReverbBus(ReverbKey key, int effect, int slot) {
            this.key = key;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
        }
    }

    private static final class EqualizerBus {
        private final EqualizerKey key;
        private final int effect;
        private final int slot;
        private int references;
        private long lastUsed;

        private EqualizerBus(EqualizerKey key, int effect, int slot) {
            this.key = key;
            this.effect = effect;
            this.slot = slot;
            this.lastUsed = System.nanoTime();
        }
    }

    private static final class SourceState {
        private final int directFilter;
        private final int reverbFilter;
        private final int bandFilter;
        private ReverbBus reverbBus;
        private EqualizerBus equalizerBus;
        private float directGain = 1.0F;
        private float highFrequencyGain = 1.0F;
        private float lowBand = 1.0F;
        private float midLowBand = 1.0F;
        private float midHighBand = 1.0F;
        private float highBand = 1.0F;
        private float reverbSend = 0.04F;
        private float reverbHighFrequency = 1.0F;
        private double propagationDistance;
        private Vec3 apparentPosition = Vec3.ZERO;
        private boolean initialized;
        private long lastUpdateNanoseconds;
        private long lastResultSequence = Long.MIN_VALUE;

        private SourceState(int directFilter, int reverbFilter, int bandFilter) {
            this.directFilter = directFilter;
            this.reverbFilter = reverbFilter;
            this.bandFilter = bandFilter;
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
                lowBand = target.lowBandGain();
                midLowBand = target.midLowBandGain();
                midHighBand = target.midHighBandGain();
                highBand = target.highBandGain();
                reverbSend = target.reverbSend();
                reverbHighFrequency = target.reverbHighFrequencyGain();
                propagationDistance = target.propagationDistance();
                apparentPosition = target.apparentPosition();
                initialized = true;
                lastUpdateNanoseconds = now;
                return true;
            }
            float amount = elapsedResponseAmount(lastUpdateNanoseconds, now);
            directGain = lerp(directGain, target.directGain(), amount);
            highFrequencyGain = lerp(highFrequencyGain, target.highFrequencyGain(), amount);
            lowBand = lerp(lowBand, target.lowBandGain(), amount);
            midLowBand = lerp(midLowBand, target.midLowBandGain(), amount);
            midHighBand = lerp(midHighBand, target.midHighBandGain(), amount);
            highBand = lerp(highBand, target.highBandGain(), amount);
            reverbSend = lerp(reverbSend, target.reverbSend(), amount);
            reverbHighFrequency = lerp(
                    reverbHighFrequency,
                    target.reverbHighFrequencyGain(),
                    amount
            );
            propagationDistance += (target.propagationDistance() - propagationDistance) * amount;
            apparentPosition = apparentPosition.lerp(target.apparentPosition(), amount);
            lastUpdateNanoseconds = now;
            return true;
        }
    }
}
