package org.macaroon.acousticsystem.client.simulation;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.BufferUtils;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenALConvolutionSmokeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "ACOUSTICSYSTEM_OPENAL_SMOKE", matches = "true")
    void prewarmsRealTimeEaxWithoutBlockingTheFirstSound() throws ReflectiveOperationException {
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        assertTrue(device != 0L);
        ALCCapabilities deviceCapabilities = ALC.createCapabilities(device);
        IntBuffer attributes = BufferUtils.createIntBuffer(3);
        // The user's actual OpenAL device exposes two sends. Exercise the
        // multiplexed remote-room path rather than only the ideal three-send case.
        attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(2).put(0).flip();
        long context = ALC10.alcCreateContext(device, attributes);
        assertTrue(context != 0L);
        assertTrue(ALC10.alcMakeContextCurrent(context));
        AL.createCapabilities(deviceCapabilities);

        int source = AL10.alGenSources();
        try {
            AcousticResult result = new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.25F, 1.0F, EarlyReflection.SILENT, 0.0F,
                    4.0,
                    new Vec3(4.0, 0.0, 0.0),
                    RoomAcoustics.OUTDOORS,
                    RoomImpulseResponse.SILENT
            );

            long warmupStarted = System.nanoTime();
            OpenALAcousticEffects.initialize();
            double warmupMilliseconds = (System.nanoTime() - warmupStarted) / 1_000_000.0;
            long started = System.nanoTime();
            OpenALAcousticEffects.applyBeforePlay(source, result);
            double elapsedMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
            assertThrows(
                    NoSuchFieldException.class,
                    () -> sourceState(source).getClass().getDeclaredField("equalizerBus"),
                    "Dry spectral energy must not leave the positional/HRTF direct path"
            );
            RoomAcoustics indoor = new RoomAcoustics(
                    0.75F, 0.68F, 0.32F, 0.72F, 0.95F,
                    1.8F, 0.65F, 1.15F,
                    0.28F, 0.018F, Vec3.ZERO,
                    1.2F, 0.032F, Vec3.ZERO,
                    0.6F, 0.08F, 0.94F
            );
            RoomAcoustics remoteFieldRoom = new RoomAcoustics(
                    0.92F, 0.88F, 0.38F, 0.66F, 0.94F,
                    3.2F, 0.52F, 1.28F,
                    0.35F, 0.024F, Vec3.ZERO,
                    1.5F, 0.045F, Vec3.ZERO,
                    0.8F, 0.12F, 0.92F
            );
            RoomProbe sourceField = new RoomProbe(
                    remoteFieldRoom,
                    List.of(),
                    List.of(new RoomProbe.OpeningSample(
                            new Vec3(2.0, 0.0, 0.0),
                            new Vec3(1.0, 0.0, 0.0),
                            1.0F
                    ))
            );
            AcousticResult remoteRoomResult = new AcousticResult(
                    0.7F, 0.8F,
                    0.7F, 0.65F, 0.55F, 0.45F,
                    0.2F, 0.75F, EarlyReflection.SILENT, 0.0F,
                    4.0, new Vec3(4.0, 0.0, 0.0),
                    RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                    sourceField, Vec3.ZERO
            );
            OpenALAcousticEffects.apply(source, remoteRoomResult);
            Object remoteSourceBus = sourceStateObject(source, "sourceRoomBus");
            assertNotNull(
                    remoteSourceBus,
                    "A remote emitter must keep feeding its own shared room field"
            );
            AcousticResult indoorResult = new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.25F, 0.8F,
                    new EarlyReflection(0.2F, 0.7F, 0.015F, new Vec3(-1.0, 0.0, 0.0)),
                    0.0F,
                    4.0,
                    new Vec3(4.0, 0.0, 0.0),
                    indoor,
                    RoomImpulseResponse.SILENT
            );
            long indoorStarted = System.nanoTime();
            OpenALAcousticEffects.updateListenerRoom(indoor);
            assertSame(
                    sourceStateObject(source, "reverbBus"),
                    sourceStateObject(source, "primaryLateBus"),
                    "The primary late send must remain listener-owned on a two-send device"
            );
            assertNotSame(
                    remoteSourceBus,
                    sourceStateObject(source, "primaryLateBus"),
                    "A remote field must use the first send instead of replacing listener late reverb"
            );
            OpenALAcousticEffects.apply(source, indoorResult);
            RoomProbe sealedListenerRoom = new RoomProbe(
                    indoor, List.of(), List.of()
            );
            OpenALAcousticEffects.updateListenerRoom(
                    sealedListenerRoom, Vec3.ZERO, 1L
            );
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(0.05, 0.0, 0.0)
            );
            assertTrue(
                    (float) objectField(staticField("activeReverbBus"), "outputGain") > 0.05F,
                    "A sealed listener-owned room must not be muted merely because it has no openings"
            );
            Object stablePhysicalRoomBus = staticField("activeReverbBus");
            RoomAcoustics shiftedEstimateInSameRoom = new RoomAcoustics(
                    0.54F, 0.49F, indoor.gain(), 0.61F, 0.96F,
                    2.7F, 0.58F, 1.31F,
                    indoor.reflectionsGain(), indoor.reflectionsDelay(), Vec3.ZERO,
                    indoor.lateReverbGain(), indoor.lateReverbDelay(), Vec3.ZERO,
                    0.4F, 0.02F, 0.95F
            );
            OpenALAcousticEffects.updateListenerRoom(
                    new RoomProbe(
                            shiftedEstimateInSameRoom, List.of(), List.of()
                    ),
                    new Vec3(0.25, 0.0, 0.0),
                    2L
            );
            assertSame(
                    stablePhysicalRoomBus,
                    staticField("activeReverbBus"),
                    "Numeric probe variation inside one physical field must not replace its global reverb bus"
            );
            assertEquals(0, (int) objectField(remoteSourceBus, "sourceReferences"));
            assertTrue(
                    (boolean) objectField(remoteSourceBus, "sourceField"),
                    "A stopped source-room tail must retain its portal transport identity"
            );
            long remoteFieldToken = (long) objectField(
                    remoteSourceBus, "fieldToken"
            );
            OpenALAcousticEffects.TailFieldRequest remoteTail =
                    OpenALAcousticEffects.tailFieldRequests().stream()
                            .filter(request -> request.fieldToken() == remoteFieldToken)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "A stopped remote field must remain geometrically sampled until its RT60 ends"
                            ));
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(4.0, 0.0, 0.0)
            );
            setObjectLong(
                    remoteSourceBus,
                    "lastOutputGainUpdateNanoseconds",
                    0L
            );
            OpenALAcousticEffects.updateTailField(
                    remoteTail.fieldToken(),
                    new RoomProbe(remoteFieldRoom, List.of(), List.of()),
                    remoteTail.position()
            );
            assertEquals(
                    0.0F,
                    (float) objectField(remoteSourceBus, "outputGain"),
                    1.0E-6F,
                    "Sealing the last opening must stop a stored remote tail from leaking through stale geometry"
            );
            Object leftEarlyBus = sourceStateObject(source, "earlyReflectionBus");
            assertNotNull(
                    leftEarlyBus,
                    "A calculated first-order reflection must use its source-owned send"
            );
            int opposingSource = AL10.alGenSources();
            try {
                AcousticResult opposingResult = new AcousticResult(
                        1.0F, 1.0F,
                        1.0F, 1.0F, 1.0F, 1.0F,
                        0.25F, 0.8F,
                        new EarlyReflection(
                                0.2F, 0.7F, 0.015F,
                                new Vec3(1.0, 0.0, 0.0)
                        ),
                        0.0F, 4.0,
                        new Vec3(-4.0, 0.0, 0.0),
                        indoor,
                        RoomImpulseResponse.SILENT
                );
                OpenALAcousticEffects.applyBeforePlay(opposingSource, opposingResult);
                assertNotSame(
                        leftEarlyBus,
                        sourceStateObject(opposingSource, "earlyReflectionBus"),
                        "Opposing physical arrivals must not overwrite one shared pan"
                );
                assertSame(
                        sourceStateObject(source, "reverbBus"),
                        sourceStateObject(opposingSource, "reverbBus"),
                        "Only the listener-owned late field is shared"
                );
            } finally {
                OpenALAcousticEffects.releaseSource(opposingSource);
                AL10.alDeleteSources(opposingSource);
            }
            double indoorMilliseconds = (System.nanoTime() - indoorStarted) / 1_000_000.0;
            Object gainOnlyBus = staticField("activeReverbBus");
            double[] updateMilliseconds = new double[6];
            for (int index = 0; index < updateMilliseconds.length; index++) {
                float gain = 0.33F + index * 0.03F;
                RoomAcoustics changed = new RoomAcoustics(
                        0.75F, 0.68F, gain, 0.72F, 0.95F,
                        1.8F, 0.65F, 1.15F,
                        0.28F, 0.018F, Vec3.ZERO,
                        1.2F, 0.032F, Vec3.ZERO,
                        0.6F, 0.08F, 0.94F
                );
                AcousticResult changedResult = new AcousticResult(
                        1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                        0.25F, 0.8F, EarlyReflection.SILENT, 0.0F, 4.0,
                        new Vec3(4.0, 0.0, 0.0), changed, RoomImpulseResponse.SILENT
                );
                long updateStarted = System.nanoTime();
                OpenALAcousticEffects.updateListenerRoom(changed);
                OpenALAcousticEffects.apply(source, changedResult);
                updateMilliseconds[index] = (System.nanoTime() - updateStarted) / 1_000_000.0;
            }
            assertSame(
                    gainOnlyBus,
                    staticField("activeReverbBus"),
                    "Listener wet-gain changes must update the active slot instead of replacing its DSP"
            );
            RoomAcoustics fastChangedRoom = new RoomAcoustics(
                    0.75F, 0.68F, 0.41F, 0.48F, 0.82F,
                    1.8F, 0.65F, 1.15F,
                    0.12F, 0.041F, Vec3.ZERO,
                    0.55F, 0.061F, Vec3.ZERO,
                    0.6F, 0.08F, 0.94F
            );
            OpenALAcousticEffects.updateListenerRoom(fastChangedRoom);
            assertSame(
                    gainOnlyBus,
                    staticField("activeReverbBus"),
                    "Fast gain, spectrum and delay changes must not restart the decay network"
            );
            System.out.println("warmupMilliseconds=" + warmupMilliseconds
                    + ", firstSoundApplyMilliseconds=" + elapsedMilliseconds
                    + ", firstIndoorBusMilliseconds=" + indoorMilliseconds
                    + ", roomUpdateMilliseconds=" + java.util.Arrays.toString(updateMilliseconds));
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            assertTrue(elapsedMilliseconds < warmupMilliseconds);
            assertTrue(indoorMilliseconds < 10.0, () -> "first listener-room commit exceeded 10 ms: " + indoorMilliseconds);
            for (double update : updateMilliseconds) {
                assertTrue(update < 10.0, () -> "listener-room update exceeded 10 ms: " + update);
            }

            RoomAcoustics newestRoom = new RoomAcoustics(
                    0.78F, 0.73F, 0.51F, 0.76F, 0.96F,
                    2.0F, 0.69F, 1.18F,
                    0.31F, 0.019F, Vec3.ZERO,
                    1.3F, 0.034F, Vec3.ZERO,
                    0.6F, 0.05F, 0.94F
            );
            AcousticResult newestResult = new AcousticResult(
                    0.73F, 0.81F,
                    0.73F, 0.70F, 0.66F, 0.59F,
                    0.84F, 0.78F, EarlyReflection.SILENT, 0.0F,
                    1.4, new Vec3(1.4, 0.0, 0.0),
                    newestRoom, RoomImpulseResponse.SILENT
            );
            RoomAcoustics staleRoom = new RoomAcoustics(
                    0.20F, 0.18F, 0.07F, 0.45F, 0.82F,
                    0.35F, 0.45F, 0.75F,
                    0.04F, 0.008F, Vec3.ZERO,
                    0.12F, 0.012F, Vec3.ZERO,
                    0.2F, 0.0F, 0.98F
            );
            AcousticResult staleResult = new AcousticResult(
                    0.11F, 0.35F,
                    0.11F, 0.08F, 0.05F, 0.03F,
                    0.09F, 0.42F, EarlyReflection.SILENT, 0.0F,
                    2.8, new Vec3(2.8, 0.0, 0.0),
                    staleRoom, RoomImpulseResponse.SILENT
            );
            setStaticLong("lastListenerRoomUpdateNanoseconds", 0L);
            setSourceStateLong(source, "lastUpdateNanoseconds", 0L);
            OpenALAcousticEffects.applySequenced(source, newestResult, 200L);
            float acceptedRoomGain = ((RoomAcoustics) staticField("smoothedListenerRoom")).gain();
            float acceptedDirectGain = sourceStateFloat(source, "directGain");
            OpenALAcousticEffects.applySequenced(source, staleResult, 100L);
            assertEquals(
                    acceptedRoomGain,
                    ((RoomAcoustics) staticField("smoothedListenerRoom")).gain(),
                    "An older channel callback must not roll the shared room field backward"
            );
            assertEquals(
                    acceptedDirectGain,
                    sourceStateFloat(source, "directGain"),
                    "An older channel callback must not roll a voice response backward"
            );

            int laggingSource = AL10.alGenSources();
            try {
                OpenALAcousticEffects.applyBeforePlay(laggingSource, result);
                setStaticLong("lastListenerRoomUpdateNanoseconds", 0L);
                setSourceStateLong(source, "lastUpdateNanoseconds", 0L);
                OpenALAcousticEffects.applySequenced(source, newestResult, 300L);
                float globallyNewestRoomGain =
                        ((RoomAcoustics) staticField("smoothedListenerRoom")).gain();

                setSourceStateLong(laggingSource, "lastUpdateNanoseconds", 0L);
                OpenALAcousticEffects.applySequenced(laggingSource, staleResult, 250L);
                assertEquals(
                        staleResult.directGain(),
                        sourceStateFloat(laggingSource, "directGain"),
                        1.0E-6F,
                        "A source must accept its own newest result even when another channel advanced the room"
                );
                assertEquals(
                        globallyNewestRoomGain,
                        ((RoomAcoustics) staticField("smoothedListenerRoom")).gain(),
                        "A lagging source must not roll the shared listener field backward"
                );

                OpenALAcousticEffects.applySequenced(laggingSource, newestResult, 240L);
                assertEquals(
                        staleResult.directGain(),
                        sourceStateFloat(laggingSource, "directGain"),
                        1.0E-6F,
                        "The same source must still reject its own out-of-order result"
                );
            } finally {
                OpenALAcousticEffects.releaseSource(laggingSource);
                AL10.alDeleteSources(laggingSource);
            }

            Object establishedBus = staticField("activeReverbBus");
            Object establishedKey = objectField(establishedBus, "key");
            Object establishedRoom = staticField("smoothedListenerRoom");
            OpenALAcousticEffects.prepareListenerRoomForOnset(RoomAcoustics.OUTDOORS);
            assertSame(
                    establishedBus,
                    staticField("activeReverbBus"),
                    "Starting another sound must not replace the listener field of existing voices"
            );
            assertSame(establishedRoom, staticField("smoothedListenerRoom"));

            int oneShot = AL10.alGenSources();
            OpenALAcousticEffects.applyBeforePlay(oneShot, result);
            AcousticResult occludedOnset = new AcousticResult(
                    0.2F, 0.35F,
                    0.35F, 0.28F, 0.18F, 0.10F,
                    0.12F, 0.45F, EarlyReflection.SILENT, 0.08F,
                    4.5, new Vec3(3.5, 0.0, 0.5),
                    indoor, RoomImpulseResponse.SILENT
            );
            setSourceStateLong(oneShot, "lastUpdateNanoseconds", System.nanoTime());
            OpenALAcousticEffects.applyOnsetCorrection(oneShot, occludedOnset);
            float correctedOnsetGain = sourceStateFloat(oneShot, "directGain");
            assertTrue(
                    correctedOnsetGain > occludedOnset.directGain()
                            && correctedOnsetGain < result.directGain(),
                    "The first physical correction must follow the real-dt response instead of snapping"
            );
            setStaticLong("lastListenerRoomUpdateNanoseconds", 0L);
            OpenALAcousticEffects.updateListenerRoom(new RoomAcoustics(
                    0.70F, 0.72F, 0.29F, 0.69F, 0.94F,
                    1.6F, 0.62F, 1.12F,
                    0.24F, 0.016F, Vec3.ZERO,
                    1.1F, 0.029F, Vec3.ZERO,
                    0.5F, 0.0F, 0.95F
            ));
            Object migratedBus = staticField("activeReverbBus");
            assertNotSame(
                    establishedBus,
                    migratedBus,
                    "A new listener space must receive a fresh field while the old tail decays"
            );
            assertNotSame(
                    establishedKey,
                    objectField(migratedBus, "key"),
                    "The replacement field must contain the newly measured room"
            );
            assertSame(migratedBus, sourceStateObject(source, "reverbBus"));
            assertSame(
                    migratedBus,
                    sourceStateObject(oneShot, "reverbBus"),
                    "A listener-room transition must migrate every active wet send together"
            );
            assertTrue(
                    (long) objectField(establishedBus, "tailExpiresAtNanoseconds")
                            > System.nanoTime(),
                    "The previous room bus must remain protected until its measured RT60 ends"
            );
            assertTrue(
                    (long) objectField(establishedBus, "tailExpiresAtNanoseconds")
                            - System.nanoTime()
                            > (long) (indoor.decayTime() * 1.8 * 1_000_000_000L),
                    "A stopped EAX field must survive beyond one RT60 so it cannot be hard-muted while still audible"
            );
            setObjectLong(
                    establishedBus,
                    "lastOutputGainUpdateNanoseconds",
                    System.nanoTime() - 1_000_000_000L
            );
            setStaticLong("lastListenerRoomUpdateNanoseconds", 0L);
            OpenALAcousticEffects.updateListenerRoom(RoomAcoustics.OUTDOORS);
            int retiredSlot = (int) objectField(establishedBus, "slot");
            assertTrue(
                    EXTEfx.alGetAuxiliaryEffectSlotf(
                            retiredSlot, EXTEfx.AL_EFFECTSLOT_GAIN
                    ) < indoor.gain() * 0.05F,
                    "A retired indoor field must not follow the listener outdoors at full level"
            );
            OpenALAcousticEffects.apply(oneShot, indoorResult);
            OpenALAcousticEffects.releaseSource(oneShot);
            AL10.alDeleteSources(oneShot);
            assertAllReverbSlotsKeepTheirTailGain();

            List<Integer> burstSources = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                int burstSource = AL10.alGenSources();
                assertTrue(burstSource != 0, "OpenAL source pool exhausted during repeated-sound test");
                burstSources.add(burstSource);
                float low = 0.18F + (index % 7) * 0.11F;
                float midLow = 0.22F + (index % 5) * 0.13F;
                float midHigh = 0.16F + (index % 6) * 0.12F;
                float high = 0.12F + (index % 4) * 0.17F;
                AcousticResult burstResult = new AcousticResult(
                        Math.max(Math.max(low, midLow), Math.max(midHigh, high)),
                        high / Math.max(low, 0.01F),
                        Math.min(low, 1.0F), Math.min(midLow, 1.0F),
                        Math.min(midHigh, 1.0F), Math.min(high, 1.0F),
                        0.35F, 0.7F, EarlyReflection.SILENT, 0.0F,
                        5.0, new Vec3(index % 8, 0.0, index / 8.0),
                        indoor, RoomImpulseResponse.SILENT
                );
                OpenALAcousticEffects.applyBeforePlay(burstSource, burstResult);
                assertEquals(
                        AL10.AL_NO_ERROR,
                        AL10.alGetError(),
                        "EFX failed on simultaneous repeated sound " + index
                );
            }
            for (int burstSource : burstSources) {
                OpenALAcousticEffects.releaseSource(burstSource);
                AL10.alDeleteSources(burstSource);
            }
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
        } finally {
            OpenALAcousticEffects.releaseSource(source);
            OpenALAcousticEffects.shutdown();
            AL10.alDeleteSources(source);
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
        }
    }

    private static void assertAllReverbSlotsKeepTheirTailGain() throws ReflectiveOperationException {
        Field poolField = OpenALAcousticEffects.class.getDeclaredField("REVERB_POOL");
        poolField.setAccessible(true);
        List<?> buses = (List<?>) poolField.get(null);
        assertTrue(buses.size() == 4);
        Field slotField = buses.getFirst().getClass().getDeclaredField("slot");
        slotField.setAccessible(true);
        for (Object bus : buses) {
            int slot = slotField.getInt(bus);
            float gain = EXTEfx.alGetAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN);
            assertTrue(gain >= 0.0F && gain <= 1.0F);
        }
    }

    private static float sourceStateFloat(int source, String fieldName)
            throws ReflectiveOperationException {
        Object state = sourceState(source);
        Field valueField = state.getClass().getDeclaredField(fieldName);
        valueField.setAccessible(true);
        return valueField.getFloat(state);
    }

    private static void setObjectLong(Object target, String fieldName, long value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static void setSourceStateLong(int source, String fieldName, long value)
            throws ReflectiveOperationException {
        Object state = sourceState(source);
        Field valueField = state.getClass().getDeclaredField(fieldName);
        valueField.setAccessible(true);
        valueField.setLong(state, value);
    }

    private static Object sourceStateObject(int source, String fieldName)
            throws ReflectiveOperationException {
        Object state = sourceState(source);
        Field valueField = state.getClass().getDeclaredField(fieldName);
        valueField.setAccessible(true);
        return valueField.get(state);
    }

    private static Object sourceState(int source) throws ReflectiveOperationException {
        Field sourcesField = OpenALAcousticEffects.class.getDeclaredField("SOURCES");
        sourcesField.setAccessible(true);
        Map<?, ?> sources = (Map<?, ?>) sourcesField.get(null);
        return sources.get(source);
    }

    private static Object staticField(String fieldName) throws ReflectiveOperationException {
        Field field = OpenALAcousticEffects.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object objectField(Object object, String fieldName)
            throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setStaticLong(String fieldName, long value)
            throws ReflectiveOperationException {
        Field field = OpenALAcousticEffects.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(null, value);
    }
}
