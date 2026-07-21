package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTBFormat;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.SOFTCallbackBuffer;
import org.lwjgl.openal.SOFTCallbackBufferType;
import org.lwjgl.openal.SOFTBformatEx;
import org.lwjgl.system.MemoryUtil;
import org.macaroon.acousticsystem.AcousticSystem;
import org.macaroon.acousticsystem.client.material.AcousticMaterialRegistry;
import org.macaroon.acousticsystem.client.simulation.EarlyReflection;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Renders reflected sound as PCM instead of allocating one OpenAL effect slot per
 * acoustic response. Each voice retains its own delay and feedback state; only the
 * final stereo samples share the callback output source.
 */
public final class SoftwareAcousticMixer {
    static final int OUTPUT_RATE = 48_000;
    private static final int STEREO_CHANNELS = 2;
    private static final int AMBISONIC_CHANNELS = 4;
    private static final float INVERSE_SQRT_TWO = 0.70710677F;
    private static final Object LOCK = new Object();
    private static final Map<Integer, Voice> CURRENT_VOICES = new HashMap<>();
    private static final List<Voice> DETACHED_TAILS = new ArrayList<>();
    private static volatile Voice[] renderVoices = new Voice[0];

    private static boolean initialized;
    private static int outputBuffer;
    private static int outputSource;
    private static boolean ambisonicOutput;
    private static int outputChannels = STEREO_CHANNELS;
    private static int bytesPerFrame = Float.BYTES * STEREO_CHANNELS;
    private static SOFTCallbackBufferType callback;
    private static volatile Throwable callbackFailure;
    private static volatile boolean callbackFailureReported;
    private static volatile long callbackOverruns;
    private static volatile long worstCallbackNanoseconds;
    private static volatile long lastHealthReportNanoseconds;
    private static float[] leftScratch = new float[8_192];
    private static float[] rightScratch = new float[8_192];
    private static float[] thirdScratch = new float[8_192];
    private static float[] fourthScratch = new float[8_192];

    private SoftwareAcousticMixer() {
    }

    public static void initialize() {
        synchronized (LOCK) {
            if (initialized || AL.getCapabilities() == null
                    || !AL.getCapabilities().AL_SOFT_callback_buffer) {
                return;
            }
            // Compile and initialize the complete late-field loop before the native
            // device requests its first real-time buffer. Otherwise the JVM may compile
            // the all-pass/FDN path on the first cave sound and miss that one deadline.
            AcousticFeedbackField.prewarm();
            callback = SOFTCallbackBufferType.create((userptr, sampledata, numbytes) -> {
                long started = System.nanoTime();
                try {
                    int written = render(sampledata, numbytes);
                    recordCallbackDuration(started, numbytes);
                    return written;
                } catch (Throwable failure) {
                    callbackFailure = failure;
                    MemoryUtil.memSet(sampledata, 0, numbytes);
                    return numbytes;
                }
            });
            // Begin a clean transaction: a stale error from another source must not be
            // mistaken for failure of an object which was actually created here.
            AL10.alGetError();
            outputBuffer = AL10.alGenBuffers();
            if (outputBuffer == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                cleanupOutputObjects();
                return;
            }
            outputSource = AL10.alGenSources();
            if (outputSource == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                cleanupOutputObjects();
                return;
            }
            ambisonicOutput = AL.getCapabilities().AL_EXT_BFORMAT;
            outputChannels = ambisonicOutput ? AMBISONIC_CHANNELS : STEREO_CHANNELS;
            bytesPerFrame = Float.BYTES * outputChannels;
            if (ambisonicOutput && AL.getCapabilities().AL_SOFT_bformat_ex) {
                AL11.alBufferi(
                        outputBuffer,
                        SOFTBformatEx.AL_AMBISONIC_LAYOUT_SOFT,
                        SOFTBformatEx.AL_FUMA_SOFT
                );
                AL11.alBufferi(
                        outputBuffer,
                        SOFTBformatEx.AL_AMBISONIC_SCALING_SOFT,
                        SOFTBformatEx.AL_FUMA_SOFT
                );
            }
            SOFTCallbackBuffer.alBufferCallbackSOFT(
                    outputBuffer,
                    ambisonicOutput
                            ? EXTBFormat.AL_FORMAT_BFORMAT3D_FLOAT32
                            : EXTFloat32.AL_FORMAT_STEREO_FLOAT32,
                    OUTPUT_RATE,
                    callback,
                    1L
            );
            AL10.alSourcei(outputSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSourcei(outputSource, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcef(outputSource, AL10.AL_GAIN, 1.0F);
            AL10.alSourcei(outputSource, AL10.AL_BUFFER, outputBuffer);
            AL10.alSourcePlay(outputSource);
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                AcousticSystem.LOGGER.error(
                        "Could not initialize the software acoustic mixer: OpenAL error {}",
                        error
                );
                cleanupOutputObjects();
                return;
            }
            initialized = true;
            callbackFailure = null;
            callbackFailureReported = false;
            callbackOverruns = 0L;
            worstCallbackNanoseconds = 0L;
            lastHealthReportNanoseconds = System.nanoTime();
            AcousticSystem.LOGGER.info(
                    "Software acoustic field mixer enabled without per-sound EFX slots ({})",
                    ambisonicOutput ? "first-order ambisonic" : "stereo fallback"
            );
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            CURRENT_VOICES.clear();
            DETACHED_TAILS.clear();
            renderVoices = new Voice[0];
            cleanupOutputObjects();
            initialized = false;
            callbackFailure = null;
            callbackFailureReported = false;
            callbackOverruns = 0L;
            worstCallbackNanoseconds = 0L;
        }
    }

    public static boolean available() {
        return initialized;
    }

    public static void clearVoices() {
        synchronized (LOCK) {
            CURRENT_VOICES.clear();
            DETACHED_TAILS.clear();
            renderVoices = new Voice[0];
        }
    }

    public static void attachStatic(int source, AcousticPcmAsset asset) {
        if (!initialized || asset == null) {
            return;
        }
        synchronized (LOCK) {
            retireCurrentVoice(source, false);
            CURRENT_VOICES.put(source, Voice.staticVoice(asset));
            publishVoices();
        }
    }

    public static void attachStream(int source, AudioFormat format) {
        if (!initialized || format == null) {
            return;
        }
        synchronized (LOCK) {
            retireCurrentVoice(source, false);
            CURRENT_VOICES.put(source, Voice.streamingVoice(format));
            publishVoices();
        }
    }

    public static void appendStream(int source, ByteBuffer data, AudioFormat format) {
        if (!initialized || data == null || format == null) {
            return;
        }
        Voice voice;
        synchronized (LOCK) {
            voice = CURRENT_VOICES.get(source);
        }
        if (voice != null) {
            voice.append(AcousticPcmAsset.capture(data, format));
        }
    }

    public static void setVolume(int source, float volume) {
        Voice voice = current(source);
        if (voice != null) {
            voice.volume = Math.max(0.0F, volume);
        }
    }

    public static void setPitch(int source, float pitch) {
        Voice voice = current(source);
        if (voice != null) {
            voice.pitch = Math.max(0.01F, pitch);
        }
    }

    public static void setLooping(int source, boolean looping) {
        Voice voice = current(source);
        if (voice != null) {
            voice.looping = looping;
        }
    }

    public static void play(int source) {
        Voice voice = current(source);
        if (voice != null) {
            voice.paused = false;
            voice.playing = true;
        }
    }

    public static void pause(int source) {
        Voice voice = current(source);
        if (voice != null) {
            voice.paused = true;
        }
    }

    public static void stop(int source) {
        Voice voice = current(source);
        if (voice != null) {
            voice.endInput();
        }
    }

    public static void releaseSource(int source) {
        if (!initialized) {
            return;
        }
        synchronized (LOCK) {
            retireCurrentVoice(source, true);
            publishVoices();
        }
    }

    public static void apply(
            int source,
            EarlyReflection earlyReflection,
            float earlySend,
            Vec3 earlyDirection,
            Vec3 propagationDirection,
            RoomAcoustics listenerRoom,
            float listenerSend,
            float listenerHighFrequency,
            RoomAcoustics sourceRoom,
            float sourceSend,
            float sourceHighFrequency
    ) {
        Voice voice = current(source);
        if (voice == null) {
            return;
        }
        voice.updateAcoustics(new VoiceAcoustics(
                earlyReflection,
                clamp(earlySend, 0.0F, 1.0F),
                normalizedOrZero(earlyDirection),
                AmbisonicDirection.from(propagationDirection),
                listenerRoom == null ? RoomAcoustics.OUTDOORS : listenerRoom,
                clamp(listenerSend, 0.0F, 1.0F),
                clamp(listenerHighFrequency, 0.0F, 1.0F),
                sourceRoom == null ? RoomAcoustics.OUTDOORS : sourceRoom,
                clamp(sourceSend, 0.0F, 1.0F),
                clamp(sourceHighFrequency, 0.0F, 1.0F),
                controlCoefficient()
        ));
    }

    /** Updates only listener-relative directions; feedback and delay state are untouched. */
    public static void updateSpatial(
            int source,
            Vec3 earlyDirection,
            Vec3 propagationDirection
    ) {
        Voice voice = current(source);
        if (voice != null) {
            voice.updateSpatial(
                    normalizedOrZero(earlyDirection),
                    AmbisonicDirection.from(propagationDirection)
            );
        }
    }

    static int liveRenderVoiceCount() {
        return renderVoices.length;
    }

    private static Voice current(int source) {
        reportCallbackHealth();
        synchronized (LOCK) {
            return CURRENT_VOICES.get(source);
        }
    }

    private static void reportCallbackHealth() {
        Throwable failure = callbackFailure;
        if (failure != null && !callbackFailureReported) {
            callbackFailureReported = true;
            AcousticSystem.LOGGER.error(
                    "Software acoustic callback failed; emitting silence for the wet path",
                    failure
            );
        }
        long now = System.nanoTime();
        long overruns = callbackOverruns;
        if (overruns == 0L
                || now - lastHealthReportNanoseconds < 1_000_000_000L) {
            return;
        }
        callbackOverruns = 0L;
        long worst = worstCallbackNanoseconds;
        worstCallbackNanoseconds = 0L;
        lastHealthReportNanoseconds = now;
        AcousticSystem.LOGGER.warn(
                "Software acoustic mixer missed {} audio deadlines; worst callback={} ms, voices={}",
                overruns,
                worst / 1_000_000.0,
                renderVoices.length
        );
    }

    private static void recordCallbackDuration(long started, int numbytes) {
        long elapsed = System.nanoTime() - started;
        int frames = Math.max(1, numbytes / bytesPerFrame);
        long deadline = Math.max(
                1L,
                Math.round(frames * 1_000_000_000.0 / OUTPUT_RATE)
        );
        if (elapsed <= deadline) {
            return;
        }
        callbackOverruns++;
        if (elapsed > worstCallbackNanoseconds) {
            worstCallbackNanoseconds = elapsed;
        }
    }

    private static void retireCurrentVoice(int source, boolean preserveTail) {
        Voice previous = CURRENT_VOICES.remove(source);
        if (previous == null) {
            return;
        }
        previous.endInput();
        if (preserveTail && previous.hasPendingEnergy()) {
            DETACHED_TAILS.add(previous);
        }
    }

    private static void publishVoices() {
        DETACHED_TAILS.removeIf(voice -> voice.finished);
        Voice[] next = new Voice[CURRENT_VOICES.size() + DETACHED_TAILS.size()];
        int index = 0;
        for (Voice voice : CURRENT_VOICES.values()) {
            next[index++] = voice;
        }
        for (Voice voice : DETACHED_TAILS) {
            next[index++] = voice;
        }
        renderVoices = next;
    }

    private static int render(long address, int numbytes) {
        int frames = Math.max(0, numbytes / bytesPerFrame);
        if (leftScratch.length < frames) {
            int capacity = Math.max(256, Integer.highestOneBit(frames - 1) << 1);
            leftScratch = new float[capacity];
            rightScratch = new float[leftScratch.length];
            thirdScratch = new float[leftScratch.length];
            fourthScratch = new float[leftScratch.length];
        }
        Arrays.fill(leftScratch, 0, frames, 0.0F);
        Arrays.fill(rightScratch, 0, frames, 0.0F);
        if (ambisonicOutput) {
            Arrays.fill(thirdScratch, 0, frames, 0.0F);
            Arrays.fill(fourthScratch, 0, frames, 0.0F);
        }
        Voice[] voices = renderVoices;
        for (Voice voice : voices) {
            voice.render(
                    leftScratch,
                    rightScratch,
                    thirdScratch,
                    fourthScratch,
                    frames,
                    ambisonicOutput
            );
        }
        long cursor = address;
        for (int frame = 0; frame < frames; frame++) {
            MemoryUtil.memPutFloat(cursor, leftScratch[frame]);
            MemoryUtil.memPutFloat(cursor + Float.BYTES, rightScratch[frame]);
            if (ambisonicOutput) {
                MemoryUtil.memPutFloat(cursor + Float.BYTES * 2L, thirdScratch[frame]);
                MemoryUtil.memPutFloat(cursor + Float.BYTES * 3L, fourthScratch[frame]);
            }
            cursor += bytesPerFrame;
        }
        return frames * bytesPerFrame;
    }

    private static void cleanupOutputObjects() {
        if (outputSource != 0) {
            AL10.alSourceStop(outputSource);
            AL10.alSourcei(outputSource, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(outputSource);
            outputSource = 0;
        }
        if (outputBuffer != 0) {
            AL10.alDeleteBuffers(outputBuffer);
            outputBuffer = 0;
        }
        if (callback != null) {
            callback.free();
            callback = null;
        }
        ambisonicOutput = false;
        outputChannels = STEREO_CHANNELS;
        bytesPerFrame = Float.BYTES * STEREO_CHANNELS;
        AL10.alGetError();
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Vec3 normalizedOrZero(Vec3 direction) {
        return direction == null || direction.lengthSqr() <= 1.0E-12
                ? Vec3.ZERO
                : direction.normalize();
    }

    static float wrapRingPosition(float position, int capacity) {
        while (position < 0.0F) {
            position += capacity;
        }
        while (position >= capacity) {
            position -= capacity;
        }
        return position;
    }

    private static float controlCoefficient() {
        float seconds = Math.max(
                0.001F,
                AcousticMaterialRegistry.tuning()
                        .acousticResponseTimeMilliseconds() / 1_000.0F
        );
        return 1.0F - (float) Math.exp(-1.0F / (OUTPUT_RATE * seconds));
    }

    static void addAmbisonicPoint(
            float sample,
            float localRight,
            float localUp,
            float localBack,
            float[] w,
            float[] front,
            float[] left,
            float[] up,
            int frame
    ) {
        w[frame] += sample * INVERSE_SQRT_TWO;
        front[frame] -= sample * localBack;
        left[frame] -= sample * localRight;
        up[frame] += sample * localUp;
    }

    private static void addAmbisonicDiffuse(
            float stereoLeft,
            float stereoRight,
            float[] w,
            float[] left,
            int frame
    ) {
        // Preserve the old stereo field as two opposed lateral plane waves. Their
        // common component is omnidirectional while their difference remains diffuse.
        w[frame] += (stereoLeft + stereoRight) * INVERSE_SQRT_TWO;
        left[frame] += stereoLeft - stereoRight;
    }

    private static void addAmbisonicDirectionalField(
            float stereoLeft,
            float stereoRight,
            AmbisonicDirection direction,
            float[] w,
            float[] front,
            float[] left,
            float[] up,
            int frame
    ) {
        if (!direction.valid()) {
            addAmbisonicDiffuse(stereoLeft, stereoRight, w, left, frame);
            return;
        }
        float mid = (stereoLeft + stereoRight) * INVERSE_SQRT_TWO;
        float side = (stereoLeft - stereoRight) * INVERSE_SQRT_TWO;
        addAmbisonicPoint(
                mid,
                direction.right(),
                direction.up(),
                direction.back(),
                w, front, left, up, frame
        );

        // The orthogonal mid/side transform preserves the field's decorrelated width.
        // Its side channel is a tangent dipole, not a second arbitrary apparent source.
        front[frame] += side * direction.tangentFront();
        left[frame] += side * direction.tangentLeft();
    }

    private record AmbisonicDirection(
            float right,
            float up,
            float back,
            float tangentFront,
            float tangentLeft,
            boolean valid
    ) {
        private static final AmbisonicDirection ZERO = new AmbisonicDirection(
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, false
        );

        private static AmbisonicDirection from(Vec3 rawDirection) {
            Vec3 direction = normalizedOrZero(rawDirection);
            if (direction.lengthSqr() <= 1.0E-12) {
                return ZERO;
            }
            float right = (float) direction.x;
            float up = (float) direction.y;
            float back = (float) direction.z;
            float horizontalLength = (float) Math.sqrt(right * right + back * back);
            return horizontalLength > 1.0E-6F
                    ? new AmbisonicDirection(
                            right, up, back,
                            right / horizontalLength,
                            -back / horizontalLength,
                            true
                    )
                    : new AmbisonicDirection(
                            right, up, back,
                            0.0F, -1.0F, true
                    );
        }
    }

    private record VoiceAcoustics(
            EarlyReflection early,
            float earlySend,
            Vec3 earlyDirection,
            AmbisonicDirection propagationDirection,
            RoomAcoustics listenerRoom,
            float listenerSend,
            float listenerHighFrequency,
            RoomAcoustics sourceRoom,
            float sourceSend,
            float sourceHighFrequency,
            float controlCoefficient
    ) {
        private static final VoiceAcoustics SILENT = new VoiceAcoustics(
                EarlyReflection.SILENT,
                0.0F,
                Vec3.ZERO,
                AmbisonicDirection.ZERO,
                RoomAcoustics.OUTDOORS,
                0.0F,
                1.0F,
                RoomAcoustics.OUTDOORS,
                0.0F,
                1.0F,
                1.0F
        );
    }

    private static final class Voice {
        private final AcousticPcmAsset staticAsset;
        private final ConcurrentLinkedQueue<AcousticPcmAsset> stream =
                new ConcurrentLinkedQueue<>();
        private final int nativeSampleRate;
        private final EarlyDelay early = new EarlyDelay();
        private final AcousticFeedbackField listenerField = new AcousticFeedbackField();
        private final AcousticFeedbackField sourceField = new AcousticFeedbackField();
        private AcousticPcmAsset streamAsset;
        private double frameCursor;
        private volatile VoiceAcoustics acoustics = VoiceAcoustics.SILENT;
        private volatile float volume = 1.0F;
        private volatile float pitch = 1.0F;
        private volatile boolean looping;
        private volatile boolean playing;
        private volatile boolean paused;
        private volatile boolean inputEnded;
        private volatile boolean finished;
        private VoiceAcoustics renderedAcoustics;
        private float renderedListenerSend;
        private float renderedListenerHighFrequency = 1.0F;
        private float renderedSourceSend;
        private float renderedSourceHighFrequency = 1.0F;

        private Voice(AcousticPcmAsset staticAsset, int nativeSampleRate) {
            this.staticAsset = staticAsset;
            this.nativeSampleRate = nativeSampleRate;
        }

        private static Voice staticVoice(AcousticPcmAsset asset) {
            return new Voice(asset, asset.sampleRate());
        }

        private static Voice streamingVoice(AudioFormat format) {
            return new Voice(null, Math.max(1, Math.round(format.getSampleRate())));
        }

        private void append(AcousticPcmAsset asset) {
            if (!inputEnded) {
                stream.add(asset);
            }
        }

        private void updateAcoustics(VoiceAcoustics next) {
            listenerField.configure(
                    next.listenerRoom(), next.listenerSend()
            );
            sourceField.configure(next.sourceRoom(), next.sourceSend());
            // Publish the transport gains only after both feedback networks have their
            // matching boundary coefficients. The callback therefore observes either
            // the complete old response or the complete new response, never a send from
            // one geometric result with RT60 values from another.
            acoustics = next;
        }

        private void updateSpatial(
                Vec3 earlyDirection,
                AmbisonicDirection propagationDirection
        ) {
            VoiceAcoustics current = acoustics;
            acoustics = new VoiceAcoustics(
                    current.early(),
                    current.earlySend(),
                    earlyDirection,
                    propagationDirection,
                    current.listenerRoom(),
                    current.listenerSend(),
                    current.listenerHighFrequency(),
                    current.sourceRoom(),
                    current.sourceSend(),
                    current.sourceHighFrequency(),
                    current.controlCoefficient()
            );
        }

        private void endInput() {
            playing = false;
            inputEnded = true;
            listenerField.endInput();
            sourceField.endInput();
            early.endInput();
        }

        private boolean hasPendingEnergy() {
            return early.hasPendingEnergy()
                    || listenerField.hasPendingEnergy()
                    || sourceField.hasPendingEnergy();
        }

        private void render(
                float[] first,
                float[] second,
                float[] third,
                float[] fourth,
                int frames,
                boolean ambisonic
        ) {
            if (finished || paused) {
                return;
            }
            for (int frame = 0; frame < frames; frame++) {
                VoiceAcoustics current = acoustics;
                if (current != renderedAcoustics) {
                    early.configure(
                            current.early(), current.earlySend(),
                            current.earlyDirection(), current.controlCoefficient()
                    );
                    if (renderedAcoustics == null) {
                        renderedListenerSend = current.listenerSend();
                        renderedListenerHighFrequency =
                                current.listenerHighFrequency();
                        renderedSourceSend = current.sourceSend();
                        renderedSourceHighFrequency =
                                current.sourceHighFrequency();
                    }
                    renderedAcoustics = current;
                }
                float response = current.controlCoefficient();
                renderedListenerSend += (
                        current.listenerSend() - renderedListenerSend
                ) * response;
                renderedListenerHighFrequency += (
                        current.listenerHighFrequency()
                                - renderedListenerHighFrequency
                ) * response;
                renderedSourceSend += (
                        current.sourceSend() - renderedSourceSend
                ) * response;
                renderedSourceHighFrequency += (
                        current.sourceHighFrequency()
                                - renderedSourceHighFrequency
                ) * response;
                float input = playing && !inputEnded ? nextSample() * volume : 0.0F;
                float earlyOutput = early.process(input);
                long listenerOutput = listenerField.process(
                        input * renderedListenerSend,
                        renderedListenerHighFrequency
                );
                long sourceOutput = sourceField.process(
                        input * renderedSourceSend,
                        renderedSourceHighFrequency
                );
                float listenerLeft = unpackLeft(listenerOutput);
                float listenerRight = unpackRight(listenerOutput);
                float sourceLeft = unpackLeft(sourceOutput);
                float sourceRight = unpackRight(sourceOutput);
                if (ambisonic) {
                    addAmbisonicPoint(
                            earlyOutput,
                            early.currentDirectionX(),
                            early.currentDirectionY(),
                            early.currentDirectionZ(),
                            first, second, third, fourth, frame
                    );
                    addAmbisonicDiffuse(
                            listenerLeft, listenerRight,
                            first, third, frame
                    );
                    addAmbisonicDirectionalField(
                            sourceLeft, sourceRight,
                            current.propagationDirection(),
                            first, second, third, fourth, frame
                    );
                } else {
                    first[frame] += earlyOutput * early.currentLeftPan()
                            + listenerLeft + sourceLeft;
                    second[frame] += earlyOutput * early.currentRightPan()
                            + listenerRight + sourceRight;
                }
            }
            if (inputEnded && !hasPendingEnergy()) {
                finished = true;
            }
        }

        private float nextSample() {
            AcousticPcmAsset asset = staticAsset;
            if (asset == null) {
                if (streamAsset == null || frameCursor >= streamAsset.frameCount()) {
                    streamAsset = stream.poll();
                    frameCursor = 0.0;
                    if (streamAsset == null) {
                        return 0.0F;
                    }
                }
                asset = streamAsset;
            }
            int frames = asset.frameCount();
            if (frames == 0) {
                return 0.0F;
            }
            if (frameCursor >= frames) {
                if (looping && staticAsset != null) {
                    frameCursor %= frames;
                } else {
                    endInput();
                    return 0.0F;
                }
            }
            int first = Math.min((int) frameCursor, frames - 1);
            int second = first + 1 < frames ? first + 1 : looping ? 0 : first;
            float fraction = (float) (frameCursor - first);
            float firstSample = asset.monoFrame(first);
            float sample = firstSample
                    + (asset.monoFrame(second) - firstSample) * fraction;
            frameCursor += pitch * nativeSampleRate / (double) OUTPUT_RATE;
            return sample;
        }
    }

    static final class EarlyDelay {
        private static final int CAPACITY = 16_384;
        private static final float LOW_SPLIT = onePoleCoefficient(250.0F);
        private static final float HIGH_SPLIT = onePoleCoefficient(4_000.0F);
        private float[] delay;
        private int write;
        private float currentDelayFrames;
        private float targetDelayFrames;
        private float currentGain;
        private float targetGain;
        private float currentLowFrequency = 1.0F;
        private float targetLowFrequency = 1.0F;
        private float currentHighFrequency = 1.0F;
        private float targetHighFrequency = 1.0F;
        private float currentLeftPan = 0.70710677F;
        private float targetLeftPan = 0.70710677F;
        private float currentRightPan = 0.70710677F;
        private float targetRightPan = 0.70710677F;
        private float currentDirectionX;
        private float currentDirectionY;
        private float currentDirectionZ;
        private float targetDirectionX;
        private float targetDirectionY;
        private float targetDirectionZ;
        private float response = 0.001F;
        private float lowSplitState;
        private float highSplitState;
        private int remainingFrames;

        void configure(
                EarlyReflection reflection,
                float transportGain,
                Vec3 listenerDirection,
                float controlCoefficient
        ) {
            targetDelayFrames = clamp(
                    reflection.delay() * OUTPUT_RATE,
                    0.0F,
                    CAPACITY - 2.0F
            );
            targetGain = clamp(
                    reflection.gain() * transportGain, 0.0F, 1.0F
            );
            targetLowFrequency = clamp(
                    reflection.lowFrequencyGain(), 0.0F, 1.0F
            );
            targetHighFrequency = clamp(
                    reflection.highFrequencyGain(), 0.0F, 1.0F
            );
            targetDirectionX = (float) listenerDirection.x;
            targetDirectionY = (float) listenerDirection.y;
            targetDirectionZ = (float) listenerDirection.z;
            float pan = clamp(targetDirectionX, -1.0F, 1.0F);
            targetLeftPan = (float) Math.sqrt(0.5F * (1.0F - pan));
            targetRightPan = (float) Math.sqrt(0.5F * (1.0F + pan));
            response = clamp(controlCoefficient, 1.0E-5F, 1.0F);
            if (targetGain > 1.0E-7F && delay == null) {
                delay = new float[CAPACITY];
                currentDelayFrames = targetDelayFrames;
                currentGain = targetGain;
                currentLowFrequency = targetLowFrequency;
                currentHighFrequency = targetHighFrequency;
                currentLeftPan = targetLeftPan;
                currentRightPan = targetRightPan;
                currentDirectionX = targetDirectionX;
                currentDirectionY = targetDirectionY;
                currentDirectionZ = targetDirectionZ;
            }
        }

        float process(float input) {
            if (delay == null) {
                return 0.0F;
            }
            currentDelayFrames += (targetDelayFrames - currentDelayFrames) * response;
            currentGain += (targetGain - currentGain) * response;
            currentLowFrequency += (
                    targetLowFrequency - currentLowFrequency
            ) * response;
            currentHighFrequency += (
                    targetHighFrequency - currentHighFrequency
            ) * response;
            currentLeftPan += (targetLeftPan - currentLeftPan) * response;
            currentRightPan += (targetRightPan - currentRightPan) * response;
            currentDirectionX += (targetDirectionX - currentDirectionX) * response;
            currentDirectionY += (targetDirectionY - currentDirectionY) * response;
            currentDirectionZ += (targetDirectionZ - currentDirectionZ) * response;
            delay[write] = input;
            // Adding a tiny negative float to a large ring length may round to the
            // length itself (for example -0.0001 + 16384 -> 16384). Normalize after
            // the addition before indexing.
            float read = wrapRingPosition(
                    write - currentDelayFrames, CAPACITY
            );
            int first = (int) read;
            int second = (first + 1) % CAPACITY;
            float delayed = delay[first] + (delay[second] - delay[first]) * (read - first);
            write = (write + 1) % CAPACITY;
            lowSplitState += (delayed - lowSplitState) * LOW_SPLIT;
            highSplitState += (delayed - highSplitState) * HIGH_SPLIT;
            float low = lowSplitState;
            float middle = highSplitState - low;
            float high = delayed - highSplitState;
            float sample = (
                    low * currentLowFrequency
                            + middle
                            + high * currentHighFrequency
            ) * currentGain;
            if (Math.abs(input) > 1.0E-7F) {
                remainingFrames = Math.max(remainingFrames, (int) Math.ceil(targetDelayFrames) + 2);
            } else if (remainingFrames > 0) {
                remainingFrames--;
            }
            return sample;
        }

        private float currentLeftPan() {
            return currentLeftPan;
        }

        private float currentRightPan() {
            return currentRightPan;
        }

        private float currentDirectionX() {
            return currentDirectionX;
        }

        private float currentDirectionY() {
            return currentDirectionY;
        }

        private float currentDirectionZ() {
            return currentDirectionZ;
        }

        private void endInput() {
            // process() already retains every reflection fed by a real PCM sample.
        }

        private boolean hasPendingEnergy() {
            return remainingFrames > 0
                    || Math.abs(lowSplitState) > 1.0E-7F
                    || Math.abs(highSplitState) > 1.0E-7F;
        }

        private static float onePoleCoefficient(float cutoff) {
            return 1.0F - (float) Math.exp(
                    -2.0 * Math.PI * cutoff / OUTPUT_RATE
            );
        }
    }

    private static long pack(float left, float right) {
        return Integer.toUnsignedLong(Float.floatToRawIntBits(left)) << 32
                | Integer.toUnsignedLong(Float.floatToRawIntBits(right));
    }

    private static float unpackLeft(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static float unpackRight(long packed) {
        return Float.intBitsToFloat((int) packed);
    }
}
