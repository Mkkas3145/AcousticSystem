package org.macaroon.acousticsystem.client.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticMaterialDefaultsTest {
    @Test
    void majorMaterialFamiliesHaveDistinctPhysicalResponses() throws Exception {
        JsonObject root = loadDefaults();
        JsonArray materials = root.getAsJsonArray("materials");

        AcousticMaterial stone = materialFor(materials, "#minecraft:mineable/pickaxe");
        AcousticMaterial concrete = materialFor(materials, "minecraft:white_concrete");
        AcousticMaterial wood = materialFor(materials, "#minecraft:logs");
        AcousticMaterial sand = materialFor(materials, "#minecraft:sand");
        AcousticMaterial wool = materialFor(materials, "#minecraft:wool");
        AcousticMaterial carpet = materialFor(materials, "#minecraft:wool_carpets");
        AcousticMaterial glass = materialFor(materials, "#minecraft:impermeable");
        AcousticMaterial metal = materialFor(materials, "#minecraft:copper");

        assertTrue(materials.size() >= 17);
        assertTrue(reflectedPower(stone, 3) > 0.95F);
        assertTrue(reflectedPower(metal, 3) > reflectedPower(stone, 3));
        assertTrue(
                reflectedPairGain(wood, 0) / reflectedAnchor(wood)
                        < reflectedPairGain(stone, 0) / reflectedAnchor(stone) - 0.05F,
                "Wood's low-frequency transmission must remain distinguishable from stone reflections"
        );
        assertTrue(reflectedPower(wool, 3) < 0.11F);
        assertTrue(wool.absorption(3) > sand.absorption(3));
        assertTrue(sand.absorption(3) > wood.absorption(3));
        assertTrue(glass.scattering(5) < stone.scattering(5));
        assertTrue(wool.transmission(6) < carpet.transmission(6));
        assertTrue(
                stone.transmission(0) < 0.004F,
                "A metre of stone must not leave an audible dry low-frequency leak"
        );
        assertTrue(
                concrete.transmission(0) < 0.005F,
                "A metre of concrete must not leave an audible dry low-frequency leak"
        );
        assertTrue(
                metal.structuralCoupling(1) > stone.structuralCoupling(1)
                        && stone.structuralCoupling(1) > wool.structuralCoupling(1),
                "Mechanical contact must couple most strongly into metal and least into wool"
        );
        assertTrue(
                metal.structuralGain(1, 4.0) > wool.structuralGain(1, 4.0) * 1000.0F,
                "A connected metal structure must carry vibration farther than insulation"
        );
        assertEquals(1.0F, root.getAsJsonObject("tuning").get("meters_per_block").getAsFloat());
        assertTrue(root.getAsJsonObject("tuning")
                .get("realistic_distance_attenuation").getAsBoolean());
        assertEquals(1.0F, root.getAsJsonObject("tuning")
                .get("distance_reference_meters").getAsFloat());
        assertEquals(1.0F, root.getAsJsonObject("tuning")
                .get("distance_rolloff_factor").getAsFloat());
        assertEquals(20.0F, root.getAsJsonObject("tuning")
                .get("air_temperature_celsius").getAsFloat());
        assertEquals(50.0F, root.getAsJsonObject("tuning")
                .get("relative_humidity_percent").getAsFloat());
        assertEquals(101.325F, root.getAsJsonObject("tuning")
                .get("air_pressure_kpa").getAsFloat());
    }

    @Test
    void everyMaterialConservesOrLosesEnergy() throws Exception {
        JsonArray materials = loadDefaults().getAsJsonArray("materials");
        for (int index = 0; index < materials.size(); index++) {
            JsonObject object = materials.get(index).getAsJsonObject();
            if (!object.has("blocks")) {
                continue;
            }
            AcousticMaterial material = AcousticMaterial.fromJson(
                    object,
                    AcousticMaterial.DEFAULT
            );
            for (int band = 0; band < AcousticBands.COUNT; band++) {
                int materialIndex = index;
                int bandIndex = band;
                float accounted = material.absorption(band)
                        + material.transmission(band) * material.transmission(band);
                assertTrue(
                        accounted <= 1.0001F,
                        () -> "material=" + materialIndex + ", band=" + bandIndex + ", energy=" + accounted
                );
            }
        }
    }

    @Test
    void everyMinecraftTagSelectorExistsInTheTargetVersion() throws Exception {
        JsonArray materials = loadDefaults().getAsJsonArray("materials");
        for (int materialIndex = 0; materialIndex < materials.size(); materialIndex++) {
            JsonObject material = materials.get(materialIndex).getAsJsonObject();
            JsonArray blocks = material.getAsJsonArray("blocks");
            if (blocks == null) {
                continue;
            }
            for (int selectorIndex = 0; selectorIndex < blocks.size(); selectorIndex++) {
                String selector = blocks.get(selectorIndex).getAsString();
                if (!selector.startsWith("#minecraft:")) {
                    continue;
                }
                String path = "/data/minecraft/tags/block/"
                        + selector.substring("#minecraft:".length()) + ".json";
                try (InputStream stream = AcousticMaterialDefaultsTest.class.getResourceAsStream(path)) {
                    if (stream != null) {
                        continue;
                    }
                }
                String legacyPath = "/data/minecraft/tags/blocks/"
                        + selector.substring("#minecraft:".length()) + ".json";
                try (InputStream stream = AcousticMaterialDefaultsTest.class.getResourceAsStream(legacyPath)) {
                    assertNotNull(stream, () -> "Missing Minecraft tag " + selector);
                }
            }
        }
    }

    @Test
    void explicitPeriodicEchoControlsAreNotPartOfTheMaterialModel() throws Exception {
        String defaults = loadDefaults().toString();
        assertFalse(defaults.contains("echo_time"));
        assertFalse(defaults.contains("echo_depth"));
        assertFalse(defaults.contains("max_echo_depth"));
    }

    @Test
    void acousticInterpolationUsesARealTimeConstantInsteadOfUpdateCounts() throws Exception {
        JsonObject tuningObject = loadDefaults().getAsJsonObject("tuning");
        assertTrue(tuningObject.has("acoustic_response_time_ms"));
        assertFalse(tuningObject.has("reverb_update_interval_ms"));
        AcousticTuning tuning = AcousticTuning.fromJson(tuningObject, AcousticTuning.DEFAULT);
        assertEquals(8.0F, tuning.acousticResponseTimeMilliseconds(), 1.0E-4F);

        JsonObject legacy = new JsonObject();
        legacy.addProperty("reverb_parameter_smoothing", 0.72F);
        AcousticTuning converted = AcousticTuning.fromJson(legacy, AcousticTuning.DEFAULT);
        assertEquals(
                (float) (-50.0 / Math.log(0.72)),
                converted.acousticResponseTimeMilliseconds(),
                0.01F
        );
    }

    @Test
    void reflectedDistanceAttenuationIsConfigurableAndDefaultsToPhysicalMode() throws Exception {
        AcousticTuning defaults = AcousticTuning.fromJson(
                loadDefaults().getAsJsonObject("tuning"),
                AcousticTuning.DEFAULT
        );
        assertTrue(defaults.realisticDistanceAttenuation());
        assertEquals(1.0F, defaults.distanceReferenceMeters());
        assertEquals(1.0F, defaults.airAbsorptionScale());
        assertEquals(1.0F, defaults.distanceRolloffFactor());
        assertEquals(20.0F, defaults.airTemperatureCelsius());
        assertEquals(50.0F, defaults.relativeHumidityPercent());
        assertEquals(101.325F, defaults.airPressureKilopascals());

        JsonObject disabled = new JsonObject();
        disabled.addProperty("realistic_distance_attenuation", false);
        assertFalse(
                AcousticTuning.fromJson(disabled, defaults)
                        .realisticDistanceAttenuation()
        );
    }

    @Test
    void defaultSolidTransmissionIsSpecifiedAsLossPerMeter() throws Exception {
        JsonObject root = loadDefaults();
        assertTrue(root.getAsJsonObject("default").has("transmission_loss_db_per_meter"));
        assertFalse(root.toString().contains("transmission_loss_db_per_block"));
        for (var element : root.getAsJsonArray("materials")) {
            JsonObject material = element.getAsJsonObject();
            if (material.has("blocks")) {
                assertTrue(
                        material.has("transmission_loss_db_per_meter"),
                        () -> "Solid material has no distance-normalized TL: " + material.get("blocks")
                );
                assertTrue(
                        material.has("structural_coupling")
                                && material.has("structural_loss_db_per_meter"),
                        () -> "Solid material has no mechanical propagation model: "
                                + material.get("blocks")
                );
            }
        }
    }

    @Test
    void waterUsesBulkAbsorptionAndPhysicalMediumProperties() throws Exception {
        JsonObject root = loadDefaults();
        JsonObject water = materialObjectFor(
                root.getAsJsonArray("materials"),
                "#minecraft:water",
                "fluids"
        );
        JsonObject medium = water.getAsJsonObject("medium");

        assertTrue(water.has("transmission_loss_db_per_meter"));
        assertFalse(water.has("transmission"));
        assertEquals(1480.0F, medium.get("sound_speed_meters_per_second").getAsFloat());
        assertEquals(1_480_000.0F, medium.get("acoustic_impedance_rayl").getAsFloat());
        assertTrue(
                water.getAsJsonArray("transmission_loss_db_per_meter").get(7).getAsFloat() < 0.001F,
                "Even 8 kHz bulk-water loss is measured per kilometre, not as a large per-block muffling"
        );
    }

    private static float reflectedPower(AcousticMaterial material, int band) {
        return Math.max(
                0.0F,
                1.0F - material.absorption(band)
                        - material.transmission(band) * material.transmission(band)
        );
    }

    private static float reflectedPairGain(AcousticMaterial material, int firstBand) {
        return ((float) Math.sqrt(reflectedPower(material, firstBand))
                + (float) Math.sqrt(reflectedPower(material, firstBand + 1))) * 0.5F;
    }

    private static float reflectedAnchor(AcousticMaterial material) {
        float anchor = 0.0F;
        for (int band = 0; band < AcousticBands.COUNT; band += 2) {
            anchor = Math.max(anchor, reflectedPairGain(material, band));
        }
        return anchor;
    }

    private static AcousticMaterial materialFor(JsonArray materials, String selector) {
        return AcousticMaterial.fromJson(
                materialObjectFor(materials, selector, "blocks"),
                AcousticMaterial.DEFAULT
        );
    }

    private static JsonObject materialObjectFor(
            JsonArray materials,
            String selector,
            String selectorArrayName
    ) {
        for (int index = 0; index < materials.size(); index++) {
            JsonObject object = materials.get(index).getAsJsonObject();
            JsonArray blocks = object.getAsJsonArray(selectorArrayName);
            if (blocks == null) {
                continue;
            }
            for (int block = 0; block < blocks.size(); block++) {
                if (selector.equals(blocks.get(block).getAsString())) {
                    return object;
                }
            }
        }
        throw new AssertionError("Missing material selector " + selector);
    }

    private static JsonObject loadDefaults() throws Exception {
        InputStream stream = AcousticMaterialDefaultsTest.class.getResourceAsStream(
                "/assets/acousticsystem/acoustic_materials/default.json"
        );
        assertNotNull(stream);
        try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
