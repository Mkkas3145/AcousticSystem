package org.macaroon.acousticsystem.client.audio;

import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.simulation.RoomAcoustics;

/**
 * Independent, continuously morphing late-field state for one acoustic voice.
 *
 * <p>The feedback network runs at 24 kHz because a diffuse tail contains little
 * directional energy above that Nyquist band. The input is explicitly averaged before
 * decimation, while its low, middle and high feedback losses are derived independently
 * from the measured RT60 ratios. Eight mutually incommensurate delay lines and an
 * orthonormal Hadamard junction avoid both a repeating single echo and energy growth.</p>
 */
final class AcousticFeedbackField {
    private static final int INTERNAL_RATE = SoftwareAcousticMixer.OUTPUT_RATE / 2;
    private static final int LINE_COUNT = 8;
    private static final int CAPACITY = 8_192;
    private static final int DIFFUSER_COUNT = 4;
    private static final int DIFFUSER_CAPACITY = 512;
    private static final float INVERSE_SQRT_EIGHT = 0.3535533905932738F;
    private static final float[] RATIOS = {
            1.0000F, 1.1173F, 1.2537F, 1.3989F,
            1.5571F, 1.7321F, 1.9189F, 2.1293F
    };
    private static final float[] INPUT_SIGNS = {
            1.0F, -1.0F, 1.0F, 1.0F, -1.0F, 1.0F, -1.0F, -1.0F
    };
    private static final float[] LEFT_SIGNS = {
            1.0F, 1.0F, -1.0F, 1.0F, -1.0F, -1.0F, 1.0F, -1.0F
    };
    private static final float[] RIGHT_SIGNS = {
            1.0F, -1.0F, 1.0F, 1.0F, 1.0F, -1.0F, -1.0F, 1.0F
    };
    private static final float[] DIFFUSER_RATIOS = {
            0.07F, 0.11F, 0.17F, 0.23F
    };
    private static final float LOW_SPLIT = onePoleCoefficient(250.0F);
    private static final float HIGH_SPLIT = onePoleCoefficient(4_000.0F);
    private static final double TAIL_RELATIVE_AMPLITUDE_FLOOR = 1.0E-4;

    private float[][] lines;
    private float[][] diffuserLines;
    /**
     * Publishes the complete delay network to the real-time callback. The control
     * thread owns construction and coefficient initialization; the callback must not
     * infer readiness from either array because the two allocations are independent.
     */
    private volatile boolean ready;
    private final float[] junction = new float[LINE_COUNT];
    private final float[] lowState = new float[LINE_COUNT];
    private final float[] highLowState = new float[LINE_COUNT];
    private final float[] delayFrames = new float[LINE_COUNT];
    private final float[] targetDelayFrames = new float[LINE_COUNT];
    private final float[] lowFeedback = new float[LINE_COUNT];
    private final float[] targetLowFeedback = new float[LINE_COUNT];
    private final float[] midFeedback = new float[LINE_COUNT];
    private final float[] targetMidFeedback = new float[LINE_COUNT];
    private final float[] highFeedback = new float[LINE_COUNT];
    private final float[] targetHighFeedback = new float[LINE_COUNT];
    private final int[] diffuserWrite = new int[DIFFUSER_COUNT];
    private final float[] diffuserDelay = new float[DIFFUSER_COUNT];
    private final float[] targetDiffuserDelay = new float[DIFFUSER_COUNT];
    private int write;
    private int outputPhase;
    private int remainingOutputFrames;
    private float decimationInput;
    private float previousLeft;
    private float previousRight;
    private float nextLeft;
    private float nextRight;
    private float modulationPhase;
    private volatile RoomAcoustics targetRoom = RoomAcoustics.OUTDOORS;
    private float roomGain;
    private float roomHighFrequency = 1.0F;
    private float lateGain;
    private float modulationDepth;
    private float modulationTime = 0.25F;
    private float targetBoundaryMorph = 1.0F;
    private int targetTailOutputFrames;
    private float diffuserFeedback;
    private float targetDiffuserFeedback;

    static void prewarm() {
        AcousticFeedbackField field = new AcousticFeedbackField();
        field.configure(new RoomAcoustics(
                0.8F, 0.75F, 0.3F, 0.7F, 0.95F,
                1.2F, 0.6F, 1.2F,
                0.3F, 0.018F, Vec3.ZERO,
                1.1F, 0.03F, Vec3.ZERO,
                0.6F, 0.04F, 0.95F
        ), 0.25F);
        for (int sample = 0; sample < SoftwareAcousticMixer.OUTPUT_RATE; sample++) {
            field.process(sample == 0 ? 0.25F : 0.0F, 0.8F);
        }
    }

    void configure(RoomAcoustics room, float inputGain) {
        RoomAcoustics next = room == null ? RoomAcoustics.OUTDOORS : room;
        if ((inputGain <= 1.0E-7F || next.gain() <= 1.0E-7F)
                && !ready) {
            targetRoom = next;
            return;
        }
        boolean initialize = !ready;
        if (initialize) {
            lines = new float[LINE_COUNT][CAPACITY];
            diffuserLines = new float[DIFFUSER_COUNT][DIFFUSER_CAPACITY];
            roomGain = next.gain();
            roomHighFrequency = next.gainHighFrequency();
            lateGain = next.lateReverbGain();
            modulationDepth = next.modulationDepth();
            modulationTime = Math.max(0.04F, next.modulationTime());
            diffuserFeedback = 0.68F
                    * clamp(next.diffusion(), 0.0F, 1.0F);
        }

        float baseSeconds = clamp(
                next.reflectionsDelay() + next.lateReverbDelay(),
                0.008F,
                0.155F
        );
        targetBoundaryMorph = 1.0F - (float) Math.exp(
                -1.0F / (INTERNAL_RATE * Math.max(0.004F, baseSeconds))
        );
        double maximumOutputAmplitude = Math.max(0.0, inputGain)
                * Math.max(0.0, next.gain())
                * Math.max(0.0, next.lateReverbGain());
        double decaySeconds = maximumOutputAmplitude
                <= TAIL_RELATIVE_AMPLITUDE_FLOOR
                ? 0.0
                : Math.max(0.1, next.decayTime())
                * Math.log10(
                        maximumOutputAmplitude / TAIL_RELATIVE_AMPLITUDE_FLOOR
                ) / 3.0;
        targetTailOutputFrames = Math.max(0, Math.round((float) (
                decaySeconds + next.reflectionsDelay() + next.lateReverbDelay()
        ) * SoftwareAcousticMixer.OUTPUT_RATE));
        targetDiffuserFeedback = 0.68F
                * clamp(next.diffusion(), 0.0F, 1.0F);
        for (int stage = 0; stage < DIFFUSER_COUNT; stage++) {
            targetDiffuserDelay[stage] = clamp(
                    baseSeconds * DIFFUSER_RATIOS[stage] * INTERNAL_RATE,
                    13.0F,
                    DIFFUSER_CAPACITY - 3.0F
            );
            if (diffuserDelay[stage] == 0.0F) {
                diffuserDelay[stage] = targetDiffuserDelay[stage];
            }
        }
        // A low modal density packs the delay distribution closer together; a dense
        // diffuse field spans the complete incommensurate set.
        float ratioSpread = 0.35F + 0.65F * clamp(next.density(), 0.0F, 1.0F);
        for (int line = 0; line < LINE_COUNT; line++) {
            float ratio = 1.0F + (RATIOS[line] - 1.0F) * ratioSpread;
            float delay = clamp(
                    baseSeconds * ratio * INTERNAL_RATE,
                    23.0F,
                    CAPACITY - 4.0F
            );
            targetDelayFrames[line] = delay;
            double delaySeconds = delay / INTERNAL_RATE;
            targetLowFeedback[line] = rt60Feedback(
                    delaySeconds,
                    next.decayTime() * next.decayLowFrequencyRatio()
            );
            targetMidFeedback[line] = rt60Feedback(
                    delaySeconds,
                    next.decayTime()
            );
            targetHighFeedback[line] = rt60Feedback(
                    delaySeconds,
                    next.decayTime() * next.decayHighFrequencyRatio()
            );
            if (delayFrames[line] == 0.0F) {
                delayFrames[line] = targetDelayFrames[line];
                lowFeedback[line] = targetLowFeedback[line];
                midFeedback[line] = targetMidFeedback[line];
                highFeedback[line] = targetHighFeedback[line];
            }
        }
        // Volatile publication comes last so the audio callback cannot combine the
        // new room record with a partially written set of line coefficients. On the
        // first configuration, ready is the final release-store for both buffer arrays
        // and every scalar/target array initialized above.
        targetRoom = next;
        if (initialize) {
            ready = true;
        }
    }

    long process(float input, float transportHighFrequency) {
        if (!ready) {
            return 0L;
        }
        decimationInput += input;
        outputPhase ^= 1;
        if (outputPhase == 0) {
            previousLeft = nextLeft;
            previousRight = nextRight;
            processInternal(decimationInput * 0.5F, transportHighFrequency);
            decimationInput = 0.0F;
            if (Math.abs(input) > 1.0E-7F) {
                remainingOutputFrames = Math.max(
                        remainingOutputFrames,
                        targetTailOutputFrames
                );
            }
        }
        if (Math.abs(input) <= 1.0E-7F && remainingOutputFrames > 0) {
            remainingOutputFrames--;
        }
        float fraction = outputPhase == 0 ? 1.0F : 0.5F;
        return pack(
                previousLeft + (nextLeft - previousLeft) * fraction,
                previousRight + (nextRight - previousRight) * fraction
        );
    }

    void endInput() {
        // process() records decay whenever real energy is injected. A silent or
        // never-started voice has no physical tail to synthesize at release time.
    }

    boolean hasPendingEnergy() {
        return ready && remainingOutputFrames > 0;
    }

    private void processInternal(float input, float transportHighFrequency) {
        RoomAcoustics room = targetRoom;
        // This coefficient depends only on the published room boundary. Computing it
        // in configure() removes one transcendental operation per field per DSP sample.
        float morph = targetBoundaryMorph;
        roomGain += (room.gain() - roomGain) * morph;
        roomHighFrequency += (
                room.gainHighFrequency() - roomHighFrequency
        ) * morph;
        lateGain += (room.lateReverbGain() - lateGain) * morph;
        modulationDepth += (
                room.modulationDepth() - modulationDepth
        ) * morph;
        modulationTime += (
                Math.max(0.04F, room.modulationTime()) - modulationTime
        ) * morph;

        float highTransport = clamp(
                roomHighFrequency * transportHighFrequency
                        * room.airAbsorptionGainHighFrequency(),
                0.0F,
                1.0F
        );
        float modulationSamples = clamp(modulationDepth, 0.0F, 1.0F) * 7.0F;
        if (modulationSamples > 1.0E-5F) {
            modulationPhase += 1.0F / (INTERNAL_RATE * modulationTime);
            modulationPhase -= (float) Math.floor(modulationPhase);
        }
        float diffuseInput = diffuse(input, morph);
        for (int line = 0; line < LINE_COUNT; line++) {
            delayFrames[line] += (
                    targetDelayFrames[line] - delayFrames[line]
            ) * morph;
            lowFeedback[line] += (
                    targetLowFeedback[line] - lowFeedback[line]
            ) * morph;
            midFeedback[line] += (
                    targetMidFeedback[line] - midFeedback[line]
            ) * morph;
            highFeedback[line] += (
                    targetHighFeedback[line] - highFeedback[line]
            ) * morph;

            float modulationOffset = 0.0F;
            if (modulationSamples > 1.0E-5F) {
                float phase = modulationPhase + line * (1.0F / LINE_COUNT);
                phase -= (float) Math.floor(phase);
                modulationOffset = (
                        1.0F - 4.0F * Math.abs(phase - 0.5F)
                ) * modulationSamples;
            }
            float value = readLine(
                    line,
                    delayFrames[line] + modulationOffset
            );
            lowState[line] += (value - lowState[line]) * LOW_SPLIT;
            highLowState[line] += (value - highLowState[line]) * HIGH_SPLIT;
            float low = lowState[line];
            float high = value - highLowState[line];
            float middle = highLowState[line] - low;
            junction[line] = low * lowFeedback[line]
                    + middle * midFeedback[line]
                    + high * highFeedback[line] * highTransport;
        }

        hadamard(junction);
        float injection = diffuseInput * INVERSE_SQRT_EIGHT;
        for (int line = 0; line < LINE_COUNT; line++) {
            lines[line][write] = junction[line] * INVERSE_SQRT_EIGHT
                    + injection * INPUT_SIGNS[line];
        }
        write++;
        if (write == CAPACITY) {
            write = 0;
        }

        float left = 0.0F;
        float right = 0.0F;
        for (int line = 0; line < LINE_COUNT; line++) {
            left += junction[line] * LEFT_SIGNS[line];
            right += junction[line] * RIGHT_SIGNS[line];
        }
        float outputGain = roomGain * lateGain * 0.125F;
        nextLeft = left * outputGain;
        nextRight = right * outputGain;
    }

    private float diffuse(float input, float morph) {
        diffuserFeedback += (
                targetDiffuserFeedback - diffuserFeedback
        ) * morph;
        float sample = input;
        for (int stage = 0; stage < DIFFUSER_COUNT; stage++) {
            diffuserDelay[stage] += (
                    targetDiffuserDelay[stage] - diffuserDelay[stage]
            ) * morph;
            int write = diffuserWrite[stage];
            float position = SoftwareAcousticMixer.wrapRingPosition(
                    write - diffuserDelay[stage], DIFFUSER_CAPACITY
            );
            int first = (int) position;
            int second = first + 1;
            if (second == DIFFUSER_CAPACITY) {
                second = 0;
            }
            float delayed = diffuserLines[stage][first]
                    + (diffuserLines[stage][second]
                    - diffuserLines[stage][first]) * (position - first);
            float allPass = delayed - diffuserFeedback * sample;
            diffuserLines[stage][write] = sample
                    + diffuserFeedback * allPass;
            // A Schroeder all-pass has unity magnitude response. Feeding its complete
            // output to the next stage increases echo density without changing the
            // total reverberant energy. Linear dry/wet interpolation here caused
            // phase cancellation four times in succession and nearly muted caves.
            sample = allPass;
            write++;
            if (write == DIFFUSER_CAPACITY) {
                write = 0;
            }
            diffuserWrite[stage] = write;
        }
        return sample;
    }

    private float readLine(int line, float delay) {
        float position = SoftwareAcousticMixer.wrapRingPosition(
                write - clamp(delay, 2.0F, CAPACITY - 2.0F), CAPACITY
        );
        int first = (int) position;
        int second = first + 1;
        if (second == CAPACITY) {
            second = 0;
        }
        float fraction = position - first;
        return lines[line][first]
                + (lines[line][second] - lines[line][first]) * fraction;
    }

    private static void hadamard(float[] values) {
        for (int width = 1; width < LINE_COUNT; width <<= 1) {
            for (int start = 0; start < LINE_COUNT; start += width << 1) {
                for (int offset = 0; offset < width; offset++) {
                    int left = start + offset;
                    int right = left + width;
                    float a = values[left];
                    float b = values[right];
                    values[left] = a + b;
                    values[right] = a - b;
                }
            }
        }
    }

    private static float rt60Feedback(double delaySeconds, double rt60Seconds) {
        return (float) Math.pow(
                10.0,
                -3.0 * delaySeconds / Math.max(0.1, rt60Seconds)
        );
    }

    private static float onePoleCoefficient(float cutoff) {
        return 1.0F - (float) Math.exp(
                -2.0 * Math.PI * cutoff / INTERNAL_RATE
        );
    }

    private static long pack(float left, float right) {
        return Integer.toUnsignedLong(Float.floatToRawIntBits(left)) << 32
                | Integer.toUnsignedLong(Float.floatToRawIntBits(right));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
