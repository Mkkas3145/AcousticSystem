package org.macaroon.acousticsystem.client.simulation;

import org.macaroon.acousticsystem.client.material.AcousticBands;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A normalized, reflection-only room impulse response synthesized from the
 * energy-time histogram produced by the geometric acoustics tracer.
 */
public final class RoomImpulseResponse {
    public static final int SAMPLE_RATE = 24_000;
    public static final RoomImpulseResponse SILENT = new RoomImpulseResponse(
            SAMPLE_RATE,
            new float[]{0.0F},
            0L
    );

    private static final float MINIMUM_ENERGY = 1.0E-12F;
    private static final int MAX_BLEND_CACHE_ENTRIES = 64;
    private static final Map<BlendKey, RoomImpulseResponse> BLEND_CACHE = new ConcurrentHashMap<>();
    private final int sampleRate;
    private final float[] samples;
    private final long signature;

    private RoomImpulseResponse(int sampleRate, float[] samples, long signature) {
        this.sampleRate = sampleRate;
        this.samples = samples;
        this.signature = signature;
    }

    static RoomImpulseResponse synthesize(float[][] energyHistogram, double binSeconds, long seed) {
        return synthesize(energyHistogram, binSeconds, seed, binSeconds);
    }

    static RoomImpulseResponse synthesize(
            float[][] energyHistogram,
            double binSeconds,
            long seed,
            double monteCarloTimeUncertainty
    ) {
        validateHistogram(energyHistogram, binSeconds);
        int binCount = energyHistogram[0].length;
        int sampleCount = Math.max(1, (int) Math.ceil(binCount * binSeconds * SAMPLE_RATE));
        float[] impulse = new float[sampleCount];
        float[] noise = new float[sampleCount];
        long randomState = mix64(seed ^ 0xC6BC279692B5C323L);

        for (int band = 0; band < AcousticBands.COUNT; band++) {
            for (int sample = 0; sample < sampleCount; sample++) {
                randomState = xorshift64(randomState);
                noise[sample] = ((randomState >>> 40) * 0x1.0p-23F) - 1.0F;
            }

            bandPassInPlace(noise, AcousticBands.CENTERS_HZ[band], SAMPLE_RATE);
            renderBandEnergy(
                    impulse,
                    noise,
                    energyHistogram[band],
                    binSeconds,
                    SAMPLE_RATE,
                    AcousticBands.CENTERS_HZ[band],
                    monteCarloTimeUncertainty
            );
        }

        double totalEnergy = 0.0;
        for (float sample : impulse) {
            totalEnergy += sample * sample;
        }
        if (totalEnergy <= MINIMUM_ENERGY) {
            return SILENT;
        }

        // The ray histogram describes the temporal and spectral distribution.
        // Absolute receiver coupling is applied per source by the wet-send gain.
        float normalization = (float) (1.0 / Math.sqrt(totalEnergy));
        for (int sample = 0; sample < impulse.length; sample++) {
            impulse[sample] *= normalization;
        }
        return new RoomImpulseResponse(SAMPLE_RATE, impulse, signature(impulse, SAMPLE_RATE));
    }

    static RoomImpulseResponse synthesizeLateField(
            float[][] tracedEnergyHistogram,
            double binSeconds,
            long seed,
            double monteCarloTimeUncertainty,
            float[] decayTimeByBand,
            float diffusion
    ) {
        validateHistogram(tracedEnergyHistogram, binSeconds);
        if (decayTimeByBand.length != AcousticBands.COUNT) {
            throw new IllegalArgumentException("Late-field synthesis requires one RT60 value per acoustic band");
        }
        float diffuseFraction = Math.max(0.0F, Math.min(1.0F, diffusion));
        float coherentFraction = 1.0F - diffuseFraction;
        int modeledBinCount = tracedEnergyHistogram[0].length;
        for (int band = 0; band < AcousticBands.COUNT; band++) {
            int firstArrivalBin = firstArrivalBin(tracedEnergyHistogram[band]);
            if (firstArrivalBin >= 0) {
                modeledBinCount = Math.max(
                        modeledBinCount,
                        firstArrivalBin + (int) Math.ceil(
                                Math.max(0.1F, decayTimeByBand[band]) / binSeconds
                        ) + 1
                );
            }
        }
        float[][] modeledEnergy = new float[AcousticBands.COUNT][modeledBinCount];

        for (int band = 0; band < AcousticBands.COUNT; band++) {
            float tracedTotal = 0.0F;
            int firstArrivalBin = -1;
            for (int bin = 0; bin < tracedEnergyHistogram[band].length; bin++) {
                float energy = Math.max(0.0F, tracedEnergyHistogram[band][bin]);
                tracedTotal += energy;
                if (firstArrivalBin < 0 && energy > MINIMUM_ENERGY) {
                    firstArrivalBin = bin;
                }
            }
            if (tracedTotal <= MINIMUM_ENERGY || firstArrivalBin < 0) {
                continue;
            }

            double rt60 = Math.max(0.1, decayTimeByBand[band]);
            double buildupTime = Math.max(binSeconds, monteCarloTimeUncertainty);
            double diffuseWeightSum = 0.0;
            for (int bin = firstArrivalBin; bin < modeledEnergy[band].length; bin++) {
                double elapsed = (bin - firstArrivalBin + 0.5) * binSeconds;
                double buildup = 1.0 - Math.exp(-elapsed / buildupTime);
                double decay = Math.pow(10.0, -6.0 * elapsed / rt60);
                modeledEnergy[band][bin] = (float) (buildup * decay);
                diffuseWeightSum += modeledEnergy[band][bin];
            }
            float diffuseScale = diffuseWeightSum <= MINIMUM_ENERGY
                    ? 0.0F
                    : (float) (tracedTotal / diffuseWeightSum);
            for (int bin = 0; bin < modeledEnergy[band].length; bin++) {
                float diffuseEnergy = modeledEnergy[band][bin] * diffuseScale;
                float coherentEnergy = bin < tracedEnergyHistogram[band].length
                        ? Math.max(0.0F, tracedEnergyHistogram[band][bin])
                        : 0.0F;
                modeledEnergy[band][bin] = diffuseEnergy * diffuseFraction
                        + coherentEnergy * coherentFraction;
            }
        }

        return synthesize(
                modeledEnergy,
                binSeconds,
                seed,
                monteCarloTimeUncertainty
        );
    }

    /**
     * Combines independent reflection fields in the energy domain. The inputs remain
     * normalized room responses; their calculated path powers determine their share of
     * the resulting temporal response rather than a binary source/listener room choice.
     */
    static RoomImpulseResponse blendEnergy(
            RoomImpulseResponse first,
            float firstPower,
            RoomImpulseResponse second,
            float secondPower
    ) {
        float nonNegativeFirst = Math.max(0.0F, firstPower);
        float nonNegativeSecond = Math.max(0.0F, secondPower);
        if (nonNegativeFirst <= MINIMUM_ENERGY || first.isSilent()) {
            return nonNegativeSecond <= MINIMUM_ENERGY ? SILENT : second;
        }
        if (nonNegativeSecond <= MINIMUM_ENERGY || second.isSilent()) {
            return first;
        }
        float totalPower = nonNegativeFirst + nonNegativeSecond;
        int firstShare = Math.round(nonNegativeFirst / totalPower * 32.0F);
        BlendKey key = new BlendKey(
                first.signature(),
                second.signature(),
                firstShare
        );
        RoomImpulseResponse cached = BLEND_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        int sampleRate = Math.max(first.sampleRate, second.sampleRate);
        if (first.sampleRate != second.sampleRate) {
            throw new IllegalArgumentException("Room impulse responses must use the same sample rate");
        }
        int sampleCount = Math.max(first.samples.length, second.samples.length);
        float[] mixed = new float[sampleCount];
        float firstAmplitude = (float) Math.sqrt(nonNegativeFirst / totalPower);
        float secondAmplitude = (float) Math.sqrt(nonNegativeSecond / totalPower);
        double energy = 0.0;
        for (int sample = 0; sample < sampleCount; sample++) {
            float value = (sample < first.samples.length ? first.samples[sample] * firstAmplitude : 0.0F)
                    + (sample < second.samples.length ? second.samples[sample] * secondAmplitude : 0.0F);
            mixed[sample] = value;
            energy += value * value;
        }
        if (energy <= MINIMUM_ENERGY) {
            return SILENT;
        }
        float normalization = (float) (1.0 / Math.sqrt(energy));
        for (int sample = 0; sample < mixed.length; sample++) {
            mixed[sample] *= normalization;
        }
        RoomImpulseResponse blended = new RoomImpulseResponse(
                sampleRate,
                mixed,
                signature(mixed, sampleRate)
        );
        if (BLEND_CACHE.size() >= MAX_BLEND_CACHE_ENTRIES) {
            BLEND_CACHE.keySet().stream().findFirst().ifPresent(BLEND_CACHE::remove);
        }
        BLEND_CACHE.put(key, blended);
        return blended;
    }

    private static int firstArrivalBin(float[] histogram) {
        for (int bin = 0; bin < histogram.length; bin++) {
            if (histogram[bin] > MINIMUM_ENERGY) {
                return bin;
            }
        }
        return -1;
    }

    private static void renderBandEnergy(
            float[] impulse,
            float[] filteredNoise,
            float[] histogram,
            double binSeconds,
            int sampleRate,
            float centerFrequency,
            double monteCarloTimeUncertainty
    ) {
        float totalBandEnergy = 0.0F;
        for (float energy : histogram) {
            totalBandEnergy += Math.max(0.0F, energy);
        }
        if (totalBandEnergy <= MINIMUM_ENERGY) {
            return;
        }

        float[] smoothedEnergy = causalEnergyDensity(
                histogram,
                binSeconds,
                centerFrequency,
                monteCarloTimeUncertainty
        );
        double noiseEnergy = 0.0;
        for (float sample : filteredNoise) {
            noiseEnergy += sample * sample;
        }
        if (noiseEnergy <= MINIMUM_ENERGY) {
            return;
        }
        float noiseRmsScale = (float) Math.sqrt(filteredNoise.length / noiseEnergy);
        double samplesPerBin = binSeconds * sampleRate;
        double renderedEnergy = 0.0;
        for (int sample = 0; sample < filteredNoise.length; sample++) {
            int bin = Math.min(
                    smoothedEnergy.length - 1,
                    (int) Math.floor(sample / samplesPerBin)
            );
            float energyPerSample = (float) (smoothedEnergy[bin] / samplesPerBin);
            float rendered = filteredNoise[sample]
                    * noiseRmsScale
                    * (float) Math.sqrt(Math.max(0.0F, energyPerSample));
            filteredNoise[sample] = rendered;
            renderedEnergy += rendered * rendered;
        }
        if (renderedEnergy <= MINIMUM_ENERGY) {
            return;
        }
        float energyCorrection = (float) Math.sqrt(totalBandEnergy / renderedEnergy);
        for (int sample = 0; sample < impulse.length; sample++) {
            impulse[sample] += filteredNoise[sample] * energyCorrection;
        }
    }

    private static float[] causalEnergyDensity(
            float[] histogram,
            double binSeconds,
            float centerFrequency,
            double monteCarloTimeUncertainty
    ) {
        float[] smoothed = new float[histogram.length];
        double octaveBandwidth = centerFrequency / Math.sqrt(2.0);
        double bandTimeResolution = 1.0 / (2.0 * Math.PI * octaveBandwidth);
        double timeScale = Math.max(
                binSeconds,
                Math.max(bandTimeResolution, monteCarloTimeUncertainty)
        );
        int kernelBins = Math.max(1, (int) Math.ceil(7.0 * timeScale / binSeconds));
        double[] kernel = new double[kernelBins + 1];
        double kernelSum = 0.0;
        for (int offset = 0; offset <= kernelBins; offset++) {
            double time = (offset + 0.5) * binSeconds;
            double normalizedTime = time / timeScale;
            double weight = normalizedTime * Math.exp(-normalizedTime);
            kernel[offset] = weight;
            kernelSum += weight;
        }

        for (int bin = 0; bin < histogram.length; bin++) {
            float eventEnergy = Math.max(0.0F, histogram[bin]);
            if (eventEnergy <= MINIMUM_ENERGY) {
                continue;
            }
            double availableWeight = 0.0;
            int maximumOffset = Math.min(kernelBins, histogram.length - 1 - bin);
            for (int offset = 0; offset <= maximumOffset; offset++) {
                availableWeight += kernel[offset];
            }
            double normalization = availableWeight <= 0.0 ? kernelSum : availableWeight;
            for (int offset = 0; offset <= maximumOffset; offset++) {
                smoothed[bin + offset] += eventEnergy * (float) (kernel[offset] / normalization);
            }
        }
        return smoothed;
    }

    private static void bandPassInPlace(float[] samples, float centerFrequency, int sampleRate) {
        double frequency = Math.min(centerFrequency, sampleRate * 0.45);
        double omega = 2.0 * Math.PI * frequency / sampleRate;
        double sine = Math.sin(omega);
        double cosine = Math.cos(omega);
        double q = 1.0 / Math.sqrt(2.0);
        double alpha = sine / (2.0 * q);
        double inverseA0 = 1.0 / (1.0 + alpha);
        double b0 = alpha * inverseA0;
        double b2 = -b0;
        double a1 = -2.0 * cosine * inverseA0;
        double a2 = (1.0 - alpha) * inverseA0;
        double x1 = 0.0;
        double x2 = 0.0;
        double y1 = 0.0;
        double y2 = 0.0;
        for (int sample = 0; sample < samples.length; sample++) {
            double input = samples[sample];
            double output = b0 * input + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;
            samples[sample] = (float) output;
        }
    }

    private static void validateHistogram(float[][] histogram, double binSeconds) {
        if (histogram.length != AcousticBands.COUNT || histogram[0].length == 0 || binSeconds <= 0.0) {
            throw new IllegalArgumentException("Impulse response requires eight non-empty energy bands and positive bin time");
        }
        int binCount = histogram[0].length;
        for (float[] band : histogram) {
            if (band.length != binCount) {
                throw new IllegalArgumentException("All impulse-response energy bands must have the same length");
            }
        }
    }

    private static long signature(float[] samples, int sampleRate) {
        long hash = 0xCBF29CE484222325L ^ sampleRate;
        for (float sample : samples) {
            hash ^= Float.floatToRawIntBits(sample);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private static long xorshift64(long value) {
        if (value == 0L) {
            value = 0x9E3779B97F4A7C15L;
        }
        value ^= value << 13;
        value ^= value >>> 7;
        return value ^ (value << 17);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int sampleCount() {
        return samples.length;
    }

    public float durationSeconds() {
        return samples.length / (float) sampleRate;
    }

    public float firstArrivalSeconds() {
        for (int sample = 0; sample < samples.length; sample++) {
            if (samples[sample] != 0.0F) {
                return sample / (float) sampleRate;
            }
        }
        return Float.POSITIVE_INFINITY;
    }

    public long signature() {
        return signature;
    }

    public boolean isSilent() {
        return signature == 0L;
    }

    public float[] copySamples() {
        return samples.clone();
    }

    private record BlendKey(long firstSignature, long secondSignature, int firstShare) {
    }
}
