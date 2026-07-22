package org.macaroon.acousticsystem.client.material;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AcousticMaterialRegistry {
    private static volatile Snapshot snapshot = new Snapshot(
            AcousticMaterial.DEFAULT,
            AcousticMaterial.DEFAULT_FLUID,
            AcousticTuning.DEFAULT,
            List.of(),
            List.of(),
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>()
    );
    private static volatile long revision;

    private AcousticMaterialRegistry() {
    }

    public static AcousticMaterial find(BlockState state) {
        Snapshot current = snapshot;
        return current.blockMaterialCache().computeIfAbsent(
                state,
                ignored -> resolveBlockMaterial(current, state)
        );
    }

    private static AcousticMaterial resolveBlockMaterial(Snapshot current, BlockState state) {
        List<Rule> rules = current.blockRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule rule = rules.get(i);
            if (rule.matches(state)) {
                return rule.material();
            }
        }
        return current.defaultMaterial();
    }

    public static AcousticMaterial findFluid(FluidState state) {
        Snapshot current = snapshot;
        return current.fluidMaterialCache().computeIfAbsent(
                state,
                ignored -> resolveFluidMaterial(current, state)
        );
    }

    private static AcousticMaterial resolveFluidMaterial(Snapshot current, FluidState state) {
        List<FluidRule> rules = current.fluidRules();
        for (int i = rules.size() - 1; i >= 0; i--) {
            FluidRule rule = rules.get(i);
            if (rule.matches(state)) {
                return rule.material();
            }
        }
        return current.defaultFluidMaterial();
    }

    public static AcousticTuning tuning() {
        return AcousticQualityConfig.apply(snapshot.tuning());
    }

    public static long revision() {
        return 31L * revision + AcousticQualityConfig.revision();
    }

    static void replace(
            AcousticMaterial defaultMaterial,
            AcousticMaterial defaultFluidMaterial,
            AcousticTuning tuning,
            List<Rule> blockRules,
            List<FluidRule> fluidRules
    ) {
        snapshot = new Snapshot(
                defaultMaterial,
                defaultFluidMaterial,
                tuning,
                List.copyOf(blockRules),
                List.copyOf(fluidRules),
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>()
        );
        revision++;
    }

    record Snapshot(
            AcousticMaterial defaultMaterial,
            AcousticMaterial defaultFluidMaterial,
            AcousticTuning tuning,
            List<Rule> blockRules,
            List<FluidRule> fluidRules,
            ConcurrentMap<BlockState, AcousticMaterial> blockMaterialCache,
            ConcurrentMap<FluidState, AcousticMaterial> fluidMaterialCache
    ) {
    }

    public static final class Rule {
        private final String selector;
        private final String id;
        private final boolean tag;
        private final AcousticMaterial material;

        public Rule(String selector, AcousticMaterial material) {
            this.selector = selector;
            this.id = selector.startsWith("#") ? selector.substring(1) : selector;
            this.tag = selector.startsWith("#");
            this.material = material;
        }

        boolean matches(BlockState state) {
            if (tag) {
                return state.getBlock().builtInRegistryHolder().tags()
                        .anyMatch(value -> value.location().toString().equals(id));
            }
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id);
        }

        AcousticMaterial material() {
            return material;
        }

        @Override
        public String toString() {
            return selector;
        }
    }

    public static final class FluidRule {
        private final String selector;
        private final String id;
        private final boolean tag;
        private final AcousticMaterial material;

        public FluidRule(String selector, AcousticMaterial material) {
            this.selector = selector;
            this.id = selector.startsWith("#") ? selector.substring(1) : selector;
            this.tag = selector.startsWith("#");
            this.material = material;
        }

        boolean matches(FluidState state) {
            if (tag) {
                return state.getType().builtInRegistryHolder().tags()
                        .anyMatch(value -> value.location().toString().equals(id));
            }
            return BuiltInRegistries.FLUID.getKey(state.getType()).toString().equals(id);
        }

        AcousticMaterial material() {
            return material;
        }

        @Override
        public String toString() {
            return selector;
        }
    }
}
