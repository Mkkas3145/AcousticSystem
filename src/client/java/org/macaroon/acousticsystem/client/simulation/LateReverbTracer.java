package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.macaroon.acousticsystem.client.material.AcousticBands;
import org.macaroon.acousticsystem.client.material.AcousticMaterial;
import org.macaroon.acousticsystem.client.material.AcousticTuning;
import org.macaroon.acousticsystem.client.material.MediumProfile;
import org.macaroon.acousticsystem.physics.ReverbDecayEstimator;
import org.macaroon.acousticsystem.physics.ReverbFieldEstimator;

import java.util.Arrays;

final class LateReverbTracer {
    private static final double SPEED_OF_SOUND = 343.0;
    private static final double MAXIMUM_HISTOGRAM_BIN_SECONDS = 0.0005;
    private static final int RUSSIAN_ROULETTE_START_BOUNCE = 3;
    private static final float RUSSIAN_ROULETTE_REFERENCE_ENERGY = 0.08F;
    private static final double SURFACE_OFFSET = 0.003;
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final long MONTE_CARLO_SEED = 0x6A09E667F3BCC909L;

    private LateReverbTracer() {
    }

    static Estimate trace(
            BlockGetter level,
            Vec3 listener,
            Vec3[] directionGrid,
            AcousticTracer.SurfaceHit[] initialHits,
            AcousticTuning tuning,
            float fallbackDecayTime
    ) {
        return trace(
                level, listener, directionGrid, initialHits, tuning,
                MediumProfile.AIR, null, 0.0F, fallbackDecayTime
        );
    }

    static Estimate trace(
            BlockGetter level,
            Vec3 listener,
            Vec3[] directionGrid,
            AcousticTracer.SurfaceHit[] initialHits,
            AcousticTuning tuning,
            MediumProfile medium,
            AcousticMaterial mediumMaterial,
            float mediumWeight,
            float fallbackDecayTime
    ) {
        double soundSpeed = Mth.lerp(
                mediumWeight,
                SPEED_OF_SOUND,
                medium.soundSpeedMetersPerSecond()
        );
        int rayCount = Math.min(tuning.lateReverbRayCount(), directionGrid.length);
        int maxBounces = tuning.lateReverbMaxBounces();
        double maximumSegmentDistance = tuning.adaptiveRoomProbeDistance();
        double histogramDuration = Math.max(
                0.64,
                maxBounces * tuning.meters(maximumSegmentDistance) / soundSpeed
        );
        int histogramBins = Math.max(
                1,
                (int) Math.ceil(histogramDuration / MAXIMUM_HISTOGRAM_BIN_SECONDS)
        );
        double binSeconds = histogramDuration / histogramBins;
        float[][] histogram = new float[AcousticBands.COUNT][histogramBins];
        float[][] bounceEnergy = new float[AcousticBands.COUNT][maxBounces];
        double[][] bounceTimeEnergy = new double[AcousticBands.COUNT][maxBounces];
        float[] earlyBands = new float[AcousticBands.COUNT];
        float[] lateBands = new float[AcousticBands.COUNT];

        double earlyTimeEnergy = 0.0;
        double earlyWeight = 0.0;
        double lateTimeEnergy = 0.0;
        double lateWeight = 0.0;
        double segmentDistanceSum = 0.0;
        double scatteringEnergy = 0.0;
        double lateDirectionEnergy = 0.0;
        Vec3 lateDirectionSum = Vec3.ZERO;
        int hitEvents = 0;
        // Common random numbers keep the Monte Carlo field correlated while the
        // listener moves. Geometry still changes every arrival and energy value, but a
        // half-block boundary no longer replaces a long cavern tail with unrelated noise.
        long seed = MONTE_CARLO_SEED;
        int directionOffset = Math.floorMod((int) seed, directionGrid.length);
        float[] energy = new float[AcousticBands.COUNT];
        AcousticTracer.SurfaceHit[] hitHolder = new AcousticTracer.SurfaceHit[1];

        for (int ray = 0; ray < rayCount; ray++) {
            int directionIndex = (directionOffset + ray * directionGrid.length / rayCount) % directionGrid.length;
            Vec3 position = listener;
            Vec3 direction = directionGrid[directionIndex];
            Arrays.fill(energy, 1.0F);
            double traveledDistance = 0.0;

            for (int bounce = 0; bounce < maxBounces; bounce++) {
                AcousticTracer.SurfaceHit hit = bounce == 0
                        ? initialHits[directionIndex]
                        : AcousticTracer.firstAcousticSurface(
                                level,
                                position,
                                position.add(direction.scale(maximumSegmentDistance)),
                                hitHolder
                        );
                if (hit == null) {
                    break;
                }

                hitEvents++;
                double segmentDistanceMeters = tuning.meters(hit.distance());
                segmentDistanceSum += segmentDistanceMeters;
                traveledDistance += segmentDistanceMeters;
                double arrivalTime = traveledDistance / soundSpeed;
                AcousticMaterial material = hit.material();
                for (int band = 0; band < energy.length; band++) {
                    float propagation = Mth.lerp(
                            mediumWeight,
                            AcousticTracer.airAbsorption(band, segmentDistanceMeters),
                            mediumMaterial == null
                                    ? 1.0F
                                    : mediumMaterial.transmissionGain(
                                            band,
                                            segmentDistanceMeters
                                    )
                    );
                    float reflectedPower = AcousticTracer.surfaceReflectedPower(
                            hit,
                            band,
                            tuning.metersPerBlock()
                    );
                    energy[band] *= propagation * propagation * reflectedPower;
                }

                int bin = Math.min(histogramBins - 1, (int) (arrivalTime / binSeconds));
                for (int band = 0; band < energy.length; band++) {
                    float normalizedEnergy = energy[band] / rayCount;
                    histogram[band][bin] += normalizedEnergy;
                    bounceEnergy[band][bounce] += normalizedEnergy;
                    bounceTimeEnergy[band][bounce] += arrivalTime * normalizedEnergy;
                    if (bounce == 0) {
                        earlyBands[band] += normalizedEnergy;
                    } else {
                        lateBands[band] += normalizedEnergy;
                    }
                }
                float eventEnergy = weightedEnergy(energy);
                if (bounce == 0) {
                    earlyTimeEnergy += arrivalTime * eventEnergy;
                    earlyWeight += eventEnergy;
                } else {
                    lateTimeEnergy += arrivalTime * eventEnergy;
                    lateWeight += eventEnergy;
                    Vec3 arrivalDirection = hit.location().subtract(listener);
                    if (arrivalDirection.lengthSqr() > 1.0E-8) {
                        lateDirectionSum = lateDirectionSum.add(arrivalDirection.normalize().scale(eventEnergy));
                        lateDirectionEnergy += eventEnergy;
                    }
                }
                scatteringEnergy += material.scattering() * eventEnergy;

                if (eventEnergy < tuning.lateReverbEnergyCutoff()) {
                    break;
                }
                if (bounce >= RUSSIAN_ROULETTE_START_BOUNCE
                        && eventEnergy < RUSSIAN_ROULETTE_REFERENCE_ENERGY) {
                    float survival = Mth.clamp(
                            eventEnergy / RUSSIAN_ROULETTE_REFERENCE_ENERGY,
                            0.10F,
                            1.0F
                    );
                    if (randomUnit(seed, ray, bounce, 0) > survival) {
                        break;
                    }
                    for (int band = 0; band < energy.length; band++) {
                        energy[band] /= survival;
                    }
                }

                Vec3 normal = hit.normal();
                if (direction.dot(normal) > 0.0) {
                    normal = normal.scale(-1.0);
                }
                float scattering = material.scattering();
                if (randomUnit(seed, ray, bounce, 1) < scattering) {
                    direction = cosineHemisphere(
                            normal,
                            randomUnit(seed, ray, bounce, 2),
                            randomUnit(seed, ray, bounce, 3)
                    );
                } else {
                    direction = direction.subtract(normal.scale(2.0 * direction.dot(normal))).normalize();
                }
                position = hit.location().add(normal.scale(SURFACE_OFFSET));
            }
        }

        float earlyEnergy = weightedEnergy(earlyBands);
        float lateEnergy = weightedEnergy(lateBands);
        int retentionBounce = maxBounces - 1;
        float fieldStrength = ReverbFieldEstimator.multiBounceRetention(
                earlyEnergy,
                weightedBounceEnergy(bounceEnergy, retentionBounce),
                retentionBounce
        );
        float temporalDensity = temporalDensity(histogram, earlyEnergy + lateEnergy);
        float density = ReverbFieldEstimator.density(fieldStrength, temporalDensity);
        float angularDiffusion = lateDirectionEnergy <= 1.0E-8
                ? 0.0F
                : Mth.clamp(
                        1.0F - (float) (lateDirectionSum.length() / lateDirectionEnergy),
                        0.0F,
                        1.0F
                );
        float meanScattering = hitEvents == 0
                ? 0.0F
                : Mth.clamp((float) (scatteringEnergy / Math.max(earlyWeight + lateWeight, 1.0E-8)), 0.0F, 1.0F);
        float diffusion = Mth.clamp(
                1.0F - (1.0F - angularDiffusion) * (1.0F - meanScattering),
                0.0F,
                1.0F
        );

        float[] decayByBand = new float[AcousticBands.COUNT];
        double[] bounceTimes = new double[maxBounces];
        for (int band = 0; band < decayByBand.length; band++) {
            float histogramDecay = ReverbDecayEstimator.estimateRt60(
                    histogram[band],
                    binSeconds,
                    fallbackDecayTime
            );
            for (int bounce = 0; bounce < maxBounces; bounce++) {
                bounceTimes[bounce] = bounceEnergy[band][bounce] <= 1.0E-10F
                        ? Double.NaN
                        : bounceTimeEnergy[band][bounce] / bounceEnergy[band][bounce];
            }
            decayByBand[band] = ReverbDecayEstimator.estimateRt60FromDecay(
                    bounceEnergy[band],
                    bounceTimes,
                    histogramDecay
            );
        }
        float decayTime = mean(decayByBand, 2, 6);
        float decayLowRatio = Mth.clamp(mean(decayByBand, 0, 2) / Math.max(decayTime, 0.1F), 0.1F, 2.0F);
        float decayHighRatio = Mth.clamp(mean(decayByBand, 6, 8) / Math.max(decayTime, 0.1F), 0.1F, 2.0F);
        float lowEnergy = mean(earlyBands, 0, 4) + mean(lateBands, 0, 4);
        float highEnergy = mean(earlyBands, 4, 8) + mean(lateBands, 4, 8);
        float highFrequencyGain = Mth.clamp(
                (float) Math.sqrt(highEnergy / Math.max(lowEnergy, 1.0E-6F)),
                0.1F,
                1.0F
        );
        float earlyDelay = earlyWeight <= 1.0E-8 ? 0.0F : (float) (earlyTimeEnergy / earlyWeight);
        float meanLateArrival = lateWeight <= 1.0E-8 ? earlyDelay : (float) (lateTimeEnergy / lateWeight);
        float lateDelay = Math.max(0.0F, meanLateArrival - earlyDelay);
        return new Estimate(
                fieldStrength,
                density,
                diffusion,
                decayTime,
                decayHighRatio,
                decayLowRatio,
                earlyEnergy,
                lateEnergy,
                highFrequencyGain,
                earlyDelay,
                lateDelay,
                RoomImpulseResponse.SILENT
        );
    }

    private static float temporalDensity(float[][] histogram, float totalEnergy) {
        if (totalEnergy <= 1.0E-8F) {
            return 0.0F;
        }
        int binCount = histogram[0].length;
        int first = binCount;
        int last = -1;
        int occupied = 0;
        float threshold = totalEnergy * 0.0001F;
        for (int bin = 0; bin < binCount; bin++) {
            float energy = 0.0F;
            for (float[] band : histogram) {
                energy += band[bin];
            }
            if (energy <= threshold) {
                continue;
            }
            first = Math.min(first, bin);
            last = bin;
            occupied++;
        }
        if (last < first) {
            return 0.0F;
        }
        return occupied / (float) (last - first + 1);
    }

    private static Vec3 cosineHemisphere(Vec3 normal, float firstRandom, float secondRandom) {
        double radius = Math.sqrt(firstRandom);
        double angle = Math.PI * 2.0 * secondRandom;
        double localX = radius * Math.cos(angle);
        double localY = radius * Math.sin(angle);
        double localZ = Math.sqrt(Math.max(0.0, 1.0 - firstRandom));
        Vec3 tangent = Math.abs(normal.y) < 0.999
                ? normal.cross(WORLD_UP).normalize()
                : new Vec3(1.0, 0.0, 0.0);
        Vec3 bitangent = normal.cross(tangent).normalize();
        return tangent.scale(localX).add(bitangent.scale(localY)).add(normal.scale(localZ)).normalize();
    }

    private static float weightedEnergy(float[] bands) {
        return bands[0] * 0.10F + bands[1] * 0.13F
                + bands[2] * 0.15F + bands[3] * 0.16F
                + bands[4] * 0.16F + bands[5] * 0.13F
                + bands[6] * 0.10F + bands[7] * 0.07F;
    }

    private static float weightedBounceEnergy(float[][] bands, int bounce) {
        return bands[0][bounce] * 0.10F + bands[1][bounce] * 0.13F
                + bands[2][bounce] * 0.15F + bands[3][bounce] * 0.16F
                + bands[4][bounce] * 0.16F + bands[5][bounce] * 0.13F
                + bands[6][bounce] * 0.10F + bands[7][bounce] * 0.07F;
    }


    private static float mean(float[] values, int fromInclusive, int toExclusive) {
        float sum = 0.0F;
        for (int index = fromInclusive; index < toExclusive; index++) {
            sum += values[index];
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static float randomUnit(long seed, int ray, int bounce, int dimension) {
        long value = seed
                ^ (long) ray * 0xD6E8FEB86659FD93L
                ^ (long) bounce * 0xA5A3564E27F8862BL
                ^ (long) dimension * 0x9E3779B97F4A7C15L;
        long mixed = mix64(value);
        return (float) ((mixed >>> 40) * 0x1.0p-24);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    record Estimate(
            float fieldStrength,
            float density,
            float diffusion,
            float decayTime,
            float decayHighFrequencyRatio,
            float decayLowFrequencyRatio,
            float earlyEnergy,
            float lateEnergy,
            float highFrequencyGain,
            float earlyDelay,
            float lateDelay,
            RoomImpulseResponse impulseResponse
    ) {
        static Estimate silent() {
            return new Estimate(
                    0.0F, 0.0F, 0.0F,
                    0.12F, 1.0F, 1.0F,
                    0.0F, 0.0F, 1.0F,
                    0.0F, 0.0F,
                    RoomImpulseResponse.SILENT
            );
        }
    }

}
