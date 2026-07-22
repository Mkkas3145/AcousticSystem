package org.macaroon.acousticsystem.client.material;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.macaroon.acousticsystem.AcousticSystem;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class AcousticMaterialReloadListener extends SimplePreparableReloadListener<AcousticMaterialReloadListener.Prepared> {
    private static final Gson GSON = new Gson();

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        var resources = resourceManager.listResources(
                "acoustic_materials",
                id -> id.toString().endsWith(".json")
        );
        List<NamedResource> ordered = resources.entrySet().stream()
                .map(entry -> new NamedResource(entry.getKey().toString(), entry.getValue()))
                .sorted(Comparator.comparing(NamedResource::id))
                .toList();

        AcousticMaterial defaultMaterial = AcousticMaterial.DEFAULT;
        AcousticMaterial defaultFluidMaterial = AcousticMaterial.DEFAULT_FLUID;
        AcousticTuning tuning = AcousticTuning.DEFAULT;
        List<AcousticMaterialRegistry.Rule> rules = new ArrayList<>();
        List<AcousticMaterialRegistry.FluidRule> fluidRules = new ArrayList<>();
        for (NamedResource entry : ordered) {
            try (Reader reader = entry.resource().openAsReader()) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                AcousticMaterial fileDefault = defaultMaterial;
                AcousticMaterial fileFluidDefault = defaultFluidMaterial;
                AcousticTuning fileTuning = tuning;
                List<AcousticMaterialRegistry.Rule> fileRules = new ArrayList<>();
                List<AcousticMaterialRegistry.FluidRule> fileFluidRules = new ArrayList<>();
                if (root.has("tuning")) {
                    fileTuning = AcousticTuning.fromJson(root.getAsJsonObject("tuning"), fileTuning);
                }
                if (root.has("default")) {
                    fileDefault = AcousticMaterial.fromJson(
                            root.getAsJsonObject("default"), fileDefault, fileTuning.metersPerBlock()
                    );
                }
                if (root.has("default_fluid")) {
                    fileFluidDefault = AcousticMaterial.fromJson(
                            root.getAsJsonObject("default_fluid"), fileFluidDefault,
                            fileTuning.metersPerBlock()
                    );
                }

                JsonArray materials = root.getAsJsonArray("materials");
                if (materials != null) {
                    for (JsonElement element : materials) {
                        JsonObject materialObject = element.getAsJsonObject();
                        JsonArray blocks = materialObject.getAsJsonArray("blocks");
                        JsonArray fluids = materialObject.getAsJsonArray("fluids");
                        if ((blocks == null || blocks.isEmpty()) && (fluids == null || fluids.isEmpty())) {
                            throw new IllegalArgumentException("A material entry requires a non-empty 'blocks' or 'fluids' array");
                        }
                        if (blocks != null) {
                            AcousticMaterial material = AcousticMaterial.fromJson(
                                    materialObject, fileDefault, fileTuning.metersPerBlock()
                            );
                            for (JsonElement block : blocks) {
                                String selector = block.getAsString();
                                validateSelector(selector);
                                fileRules.add(new AcousticMaterialRegistry.Rule(selector, material));
                            }
                        }
                        if (fluids != null) {
                            AcousticMaterial material = AcousticMaterial.fromJson(
                                    materialObject, fileFluidDefault, fileTuning.metersPerBlock()
                            );
                            for (JsonElement fluid : fluids) {
                                String selector = fluid.getAsString();
                                validateSelector(selector);
                                fileFluidRules.add(new AcousticMaterialRegistry.FluidRule(selector, material));
                            }
                        }
                    }
                }

                if (root.has("replace") && root.get("replace").getAsBoolean()) {
                    rules.clear();
                    fluidRules.clear();
                }
                defaultMaterial = fileDefault;
                defaultFluidMaterial = fileFluidDefault;
                tuning = fileTuning;
                rules.addAll(fileRules);
                fluidRules.addAll(fileFluidRules);
            } catch (Exception exception) {
                AcousticSystem.LOGGER.error("Failed to load acoustic material file {} from pack {}",
                        entry.id(), entry.resource().sourcePackId(), exception);
            }
        }
        return new Prepared(defaultMaterial, defaultFluidMaterial, tuning, rules, fluidRules);
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        AcousticMaterialRegistry.replace(
                prepared.defaultMaterial(),
                prepared.defaultFluidMaterial(),
                prepared.tuning(),
                prepared.rules(),
                prepared.fluidRules()
        );
        AcousticSystem.LOGGER.info(
                "Loaded {} acoustic block selectors and {} fluid selectors",
                prepared.rules().size(),
                prepared.fluidRules().size()
        );
    }

    private static void validateSelector(String selector) {
        String id = selector.startsWith("#") ? selector.substring(1) : selector;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid block or tag identifier: " + selector);
        }
    }

    record Prepared(
            AcousticMaterial defaultMaterial,
            AcousticMaterial defaultFluidMaterial,
            AcousticTuning tuning,
            List<AcousticMaterialRegistry.Rule> rules,
            List<AcousticMaterialRegistry.FluidRule> fluidRules
    ) {
    }

    private record NamedResource(String id, Resource resource) {
    }
}
