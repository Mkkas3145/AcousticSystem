package org.macaroon.acousticsystem.client.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

import java.util.Arrays;

public final class AcousticMaterial {
    public static final AcousticMaterial DEFAULT = new AcousticMaterial(
            new float[]{0.06F, 0.08F, 0.10F, 0.13F, 0.17F, 0.23F, 0.30F, 0.38F},
            new float[]{0.25F, 0.19F, 0.13F, 0.08F, 0.045F, 0.022F, 0.010F, 0.004F},
            0.10F,
            1.0F
    );
    public static final AcousticMaterial DEFAULT_FLUID = new AcousticMaterial(
            new float[]{0.005F, 0.008F, 0.012F, 0.020F, 0.035F, 0.060F, 0.095F, 0.14F},
            new float[]{1.0F, 1.0F, 1.0F, 0.999999F, 0.999993F, 0.999986F, 0.999971F, 0.999914F},
            new float[]{0.995F, 0.995F, 0.992F, 0.988F, 0.980F, 0.965F, 0.940F, 0.900F},
            MediumProfile.WATER,
            0.08F,
            1.0F
    );

    private final float[] absorption;
    private final float[] transmissionLossDbPerMeter;
    private final float[] boundaryTransmission;
    private final MediumProfile medium;
    private final float[] scattering;
    private final float[] structuralCoupling;
    private final float[] structuralLossDbPerMeter;
    private final float thickness;

    public AcousticMaterial(float[] absorption, float[] transmission, float scattering, float thickness) {
        this(
                absorption,
                transmission,
                new float[]{1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F},
                MediumProfile.AIR,
                scattering,
                thickness
        );
    }

    public AcousticMaterial(
            float[] absorption,
            float[] transmission,
            float[] boundaryTransmission,
            MediumProfile medium,
            float scattering,
            float thickness
    ) {
        this(
                absorption,
                transmission,
                boundaryTransmission,
                medium,
                constantBands(scattering),
                thickness
        );
    }

    public AcousticMaterial(
            float[] absorption,
            float[] transmission,
            float[] boundaryTransmission,
            MediumProfile medium,
            float[] scattering,
            float thickness
    ) {
        this(
                absorption,
                transmission,
                boundaryTransmission,
                medium,
                scattering,
                new float[]{0.42F, 0.36F, 0.27F, 0.17F, 0.09F, 0.040F, 0.014F, 0.004F},
                new float[]{2.0F, 3.0F, 5.0F, 9.0F, 15.0F, 24.0F, 37.0F, 53.0F},
                thickness
        );
    }

    public AcousticMaterial(
            float[] absorption,
            float[] transmission,
            float[] boundaryTransmission,
            MediumProfile medium,
            float[] scattering,
            float[] structuralCoupling,
            float[] structuralLossDbPerMeter,
            float thickness
    ) {
        this.absorption = clampBands(absorption);
        this.transmissionLossDbPerMeter = gainsToLossDbPerMeter(transmission);
        this.boundaryTransmission = clampBands(boundaryTransmission);
        this.medium = medium;
        this.scattering = clampBands(scattering);
        this.structuralCoupling = clampBands(structuralCoupling);
        this.structuralLossDbPerMeter = clampLossBands(structuralLossDbPerMeter);
        this.thickness = Mth.clamp(thickness, 0.05F, 8.0F);
    }

    public float absorption(int band) {
        return absorption[band];
    }

    public float transmission(int band) {
        return transmissionGain(band, 1.0);
    }

    public float transmissionGain(int band, double distanceMeters) {
        return (float) Math.pow(
                10.0,
                -transmissionLossDbPerMeter[band] * Math.max(0.0, distanceMeters) / 20.0
        );
    }

    public float surfaceTransmission(int band, double metersPerBlock) {
        return transmissionGain(band, thickness * metersPerBlock);
    }

    public float boundaryTransmission(int band) {
        return boundaryTransmission[band];
    }

    public MediumProfile medium() {
        return medium;
    }

    public float scattering() {
        float sum = 0.0F;
        for (float value : scattering) {
            sum += value;
        }
        return sum / scattering.length;
    }

    public float scattering(int band) {
        return scattering[band];
    }

    public float structuralCoupling(int band) {
        return structuralCoupling[band];
    }

    public float structuralGain(int band, double distanceMeters) {
        return (float) Math.pow(
                10.0,
                -structuralLossDbPerMeter[band] * Math.max(0.0, distanceMeters) / 20.0
        );
    }

    public float thickness() {
        return thickness;
    }

    public static AcousticMaterial fromJson(JsonObject object, AcousticMaterial fallback) {
        return fromJson(object, fallback, 1.0F);
    }

    public static AcousticMaterial fromJson(
            JsonObject object,
            AcousticMaterial fallback,
            float metersPerBlock
    ) {
        return new AcousticMaterial(
                readBands(object, "absorption", fallback.absorption),
                lossDbToGains(readTransmission(
                        object,
                        fallback.transmissionLossDbPerMeter,
                        metersPerBlock
                )),
                readBands(object, "boundary_transmission", fallback.boundaryTransmission),
                MediumProfile.fromJson(
                        object.has("medium") ? object.getAsJsonObject("medium") : null,
                        fallback.medium
                ),
                readScattering(object, fallback),
                readBands(object, "structural_coupling", fallback.structuralCoupling),
                object.has("structural_loss_db_per_meter")
                        ? readLossBands(object, "structural_loss_db_per_meter")
                        : fallback.structuralLossDbPerMeter.clone(),
                object.has("thickness") ? object.get("thickness").getAsFloat() : fallback.thickness
        );
    }

    private static float[] readBands(JsonObject object, String name, float[] fallback) {
        if (!object.has(name)) {
            return fallback.clone();
        }

        JsonArray array = object.getAsJsonArray(name);
        return AcousticBands.read(array, name);
    }

    private static float[] clampBands(float[] values) {
        return AcousticBands.clamp(values);
    }

    private static float[] readTransmission(
            JsonObject object,
            float[] fallbackLossDbPerMeter,
            float metersPerBlock
    ) {
        if (object.has("transmission_loss_db_per_meter")) {
            return readLossBands(object, "transmission_loss_db_per_meter");
        }
        if (object.has("transmission_loss_db_per_block")) {
            float[] perBlock = readLossBands(object, "transmission_loss_db_per_block");
            float[] perMeter = new float[AcousticBands.COUNT];
            for (int band = 0; band < perMeter.length; band++) {
                perMeter[band] = perBlock[band] / Math.max(metersPerBlock, 1.0E-4F);
            }
            return perMeter;
        }
        if (object.has("transmission")) {
            return gainsToLossDbPerMeter(readBands(object, "transmission", lossDbToGains(fallbackLossDbPerMeter)));
        }
        return fallbackLossDbPerMeter.clone();
    }

    private static float[] readLossBands(JsonObject object, String name) {
        float[] values = AcousticBands.read(object.getAsJsonArray(name), name);
        return clampLossBands(values);
    }

    private static float[] clampLossBands(float[] values) {
        values = values.clone();
        for (int band = 0; band < values.length; band++) {
            values[band] = Mth.clamp(values[band], 0.0F, 240.0F);
        }
        return values;
    }

    private static float[] gainsToLossDbPerMeter(float[] gains) {
        float[] loss = new float[AcousticBands.COUNT];
        float[] normalized = AcousticBands.clamp(gains);
        for (int band = 0; band < loss.length; band++) {
            loss[band] = (float) (-20.0 * Math.log10(Math.max(normalized[band], 1.0E-12F)));
        }
        return loss;
    }

    private static float[] lossDbToGains(float[] loss) {
        float[] gains = new float[AcousticBands.COUNT];
        for (int band = 0; band < gains.length; band++) {
            gains[band] = (float) Math.pow(10.0, -loss[band] / 20.0);
        }
        return gains;
    }

    private static float[] readScattering(JsonObject object, AcousticMaterial fallback) {
        if (!object.has("scattering")) {
            return fallback.scattering.clone();
        }
        if (!object.get("scattering").isJsonArray()) {
            return constantBands(object.get("scattering").getAsFloat());
        }
        return AcousticBands.read(object.getAsJsonArray("scattering"), "scattering");
    }

    private static float[] constantBands(float value) {
        float[] values = new float[AcousticBands.COUNT];
        Arrays.fill(values, Mth.clamp(value, 0.0F, 1.0F));
        return values;
    }
}
