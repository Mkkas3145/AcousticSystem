package org.macaroon.acousticsystem.client.material;

import com.google.gson.JsonArray;
import net.minecraft.util.Mth;

public final class AcousticBands {
    public static final int COUNT = 8;
    public static final int[] CENTERS_HZ = {63, 125, 250, 500, 1000, 2000, 4000, 8000};

    private AcousticBands() {
    }

    public static float[] read(JsonArray array, String fieldName) {
        if (array.size() != 4 && array.size() != COUNT) {
            throw new IllegalArgumentException("'" + fieldName + "' must contain four or eight frequency bands");
        }
        float[] input = new float[array.size()];
        for (int band = 0; band < input.length; band++) {
            input[band] = array.get(band).getAsFloat();
        }
        return expand(input);
    }

    public static float[] expand(float[] values) {
        if (values.length == COUNT) {
            return values.clone();
        }
        if (values.length != 4) {
            throw new IllegalArgumentException("Acoustic data must contain four or eight frequency bands");
        }
        return new float[]{
                values[0],
                mix(values[0], values[1], 0.5F),
                values[1],
                mix(values[1], values[2], 1.0F / 3.0F),
                mix(values[1], values[2], 2.0F / 3.0F),
                values[2],
                mix(values[2], values[3], 0.5F),
                values[3]
        };
    }

    public static float[] clamp(float[] values) {
        float[] result = expand(values);
        for (int band = 0; band < result.length; band++) {
            result[band] = Mth.clamp(result[band], 0.0F, 1.0F);
        }
        return result;
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }
}
