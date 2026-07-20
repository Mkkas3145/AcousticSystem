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
import org.lwjgl.openal.EXTSourceDistanceModel;
import org.lwjgl.openal.EXTLinearDistance;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.SOFTCallbackBuffer;
import org.lwjgl.openal.SOFTCallbackBufferType;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;
import org.macaroon.acousticsystem.client.audio.OpenALAcousticEffects;
import org.macaroon.acousticsystem.client.config.AcousticQualityConfig;

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
    void prewarmsRealTimeEaxWithoutBlockingTheFirstSound() throws Exception {
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        assertTrue(device != 0L);
        ALCCapabilities deviceCapabilities = ALC.createCapabilities(device);
        IntBuffer attributes = BufferUtils.createIntBuffer(3);
        // The production context now explicitly requests the three independent paths.
        attributes.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(3).put(0).flip();
        long context = ALC10.alcCreateContext(device, attributes);
        assertTrue(context != 0L);
        assertTrue(ALC10.alcMakeContextCurrent(context));
        assertTrue(
                ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS) >= 3,
                "The bundled OpenAL Soft context must honor the three-send request"
        );
        var alCapabilities = AL.createCapabilities(deviceCapabilities);
        assertTrue(
                alCapabilities.AL_SOFT_callback_buffer,
                "The software field mixer requires AL_SOFT_callback_buffer"
        );
        AL10.alEnable(EXTSourceDistanceModel.AL_SOURCE_DISTANCE_MODEL);

        int callbackBuffer = AL10.alGenBuffers();
        int callbackSource = AL10.alGenSources();
        SOFTCallbackBufferType silenceCallback = new SOFTCallbackBufferType() {
            @Override
            public int invoke(long userptr, long sampledata, int numbytes) {
                MemoryUtil.memSet(sampledata, 0, numbytes);
                return numbytes;
            }
        };
        SOFTCallbackBuffer.alBufferCallbackSOFT(
                callbackBuffer,
                EXTFloat32.AL_FORMAT_STEREO_FLOAT32,
                48_000,
                silenceCallback,
                1L
        );
        AL10.alSourcei(callbackSource, AL10.AL_BUFFER, callbackBuffer);
        AL10.alSourcePlay(callbackSource);
        Thread.sleep(20L);
        assertEquals(AL10.AL_PLAYING, AL10.alGetSourcei(callbackSource, AL10.AL_SOURCE_STATE));
        AL10.alSourceStop(callbackSource);
        AL10.alSourcei(callbackSource, AL10.AL_BUFFER, 0);
        AL10.alDeleteSources(callbackSource);
        AL10.alDeleteBuffers(callbackBuffer);
        silenceCallback.free();
        assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());

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
            assertEquals(AL10.AL_NONE,
                    AL10.alGetSourcei(source, AL10.AL_DISTANCE_MODEL));
            assertEquals(AL10.AL_FALSE, AL10.alGetSourcei(
                    source, EXTEfx.AL_AUXILIARY_SEND_FILTER_GAIN_AUTO
            ), "The solver, not a second EFX law, owns absolute propagation gain");
            int conservedSource = AL10.alGenSources();
            try {
                AcousticResult distant = new AcousticResult(
                        1.0F, 0.4F,
                        1.0F, 0.8F, 0.5F, 0.2F,
                        0.8F, 0.3F,
                        new EarlyReflection(0.6F, 0.25F, 0.05F, new Vec3(1.0, 0.0, 0.0)),
                        0.0F, 500.0, new Vec3(500.0, 0.0, 0.0),
                        RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                        0.002F
                );
                OpenALAcousticEffects.applyBeforePlay(conservedSource, distant);
                int directFilter = sourceStateInt(conservedSource, "directFilter");
                int reverbFilter = sourceStateInt(conservedSource, "reverbFilter");
                int reflectionFilter = sourceStateInt(conservedSource, "earlyReflectionFilter");
                assertEquals(0.002F, EXTEfx.alGetFilterf(
                        directFilter, EXTEfx.AL_LOWPASS_GAIN
                ), 0.00001F);
                assertEquals(0.0016F, EXTEfx.alGetFilterf(
                        reverbFilter, EXTEfx.AL_LOWPASS_GAIN
                ), 0.00001F);
                assertEquals(0.0012F, EXTEfx.alGetFilterf(
                        reflectionFilter, EXTEfx.AL_LOWPASS_GAIN
                ), 0.00001F);
            } finally {
                OpenALAcousticEffects.releaseSource(conservedSource);
                AL10.alDeleteSources(conservedSource);
            }
            int authoredRangeSource = AL10.alGenSources();
            try {
                OpenALAcousticEffects.configureDistanceAttenuation(
                        authoredRangeSource, 16.0F
                );
                AcousticResult distantLoop = new AcousticResult(
                        1.0F, 1.0F,
                        1.0F, 1.0F, 1.0F, 1.0F,
                        1.0F, 1.0F, EarlyReflection.SILENT, 0.0F,
                        500.0, new Vec3(500.0, 0.0, 0.0),
                        RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                        0.002F
                );
                OpenALAcousticEffects.applyBeforePlay(
                        authoredRangeSource, distantLoop
                );
                int directFilter = sourceStateInt(
                        authoredRangeSource, "directFilter"
                );
                assertTrue(EXTEfx.alGetFilterf(
                        directFilter, EXTEfx.AL_LOWPASS_GAIN
                ) < 1.0E-8F, "A 16 m authored loop must fall below audibility at 500 m");
            } finally {
                OpenALAcousticEffects.releaseSource(authoredRangeSource);
                AL10.alDeleteSources(authoredRangeSource);
            }
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
                    0.2F, 0.75F,
                    new EarlyReflection(
                            0.2F, 0.68F, 0.027F,
                            new Vec3(-1.0, 0.0, 0.0)
                    ),
                    0.0F,
                    4.0, new Vec3(4.0, 0.0, 0.0),
                    RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                    sourceField, Vec3.ZERO
            );
            setStaticObject("lastDynamicListenerPosition", new Vec3(4.0, 0.0, 0.0));
            OpenALAcousticEffects.apply(source, remoteRoomResult);
            Object remoteEarlyBus = sourceStateObject(source, "earlyReflectionBus");
            Object remoteSourceBus = sourceStateObject(source, "sourceRoomBus");
            assertNotNull(
                    remoteEarlyBus,
                    "A calculated source reflection must own its independent send"
            );
            assertNotNull(
                    remoteSourceBus,
                    "A remote diffuse field must own the third send"
            );
            assertThrows(
                    NoSuchFieldException.class,
                    () -> remoteEarlyBus.getClass().getDeclaredField("sourceRoom"),
                    "An early-delay network must never retain a diffuse room tail"
            );
            int earlyEffect = (int) objectField(remoteEarlyBus, "effect");
            assertTrue(EXTEfx.alGetEffectf(
                    earlyEffect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN
            ) > 0.0F, "The calculated echo must remain present in a remote field");
            int sourceRoomEffect = (int) objectField(remoteSourceBus, "effect");
            assertTrue(EXTEfx.alGetEffectf(
                    sourceRoomEffect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN
            ) > 0.0F, "The source-side diffuse field must remain independent");

            List<Integer> distinctFieldSources = new ArrayList<>();
            for (int fieldIndex = 0; fieldIndex < 8; fieldIndex++) {
                int fieldSource = AL10.alGenSources();
                assertTrue(fieldSource != 0);
                distinctFieldSources.add(fieldSource);
                RoomAcoustics distinctRoom = new RoomAcoustics(
                        0.12F + fieldIndex * 0.1F, 0.66F, 0.25F, 0.7F, 0.94F,
                        0.1F, 0.58F, 1.16F,
                        0.24F, 0.01F, Vec3.ZERO,
                        1.0F, 0.01F, Vec3.ZERO,
                        0.55F, 0.06F, 0.94F
                );
                AcousticResult distinctFieldResult = new AcousticResult(
                        0.65F, 0.72F,
                        0.65F, 0.6F, 0.5F, 0.4F,
                        0.18F, 0.7F, EarlyReflection.SILENT, 0.0F,
                        5.0, new Vec3(fieldIndex + 2.0, 0.0, 1.0),
                        RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                        new RoomProbe(
                                distinctRoom, List.of(), sourceField.openings()
                        ),
                        new Vec3(fieldIndex, 0.0, 0.0)
                );
                OpenALAcousticEffects.applyBeforePlay(
                        fieldSource, distinctFieldResult
                );
                assertNotNull(
                        sourceStateObject(fieldSource, "sourceRoomBus"),
                        "A fifth-or-later simultaneous room must not lose its field"
                );
            }
            assertTrue(
                    ((List<?>) staticField("REVERB_POOL")).size() > 4,
                    "Four prewarmed networks must not become a runtime field limit"
            );
            for (int fieldSource : distinctFieldSources) {
                OpenALAcousticEffects.releaseSource(fieldSource);
                AL10.alDeleteSources(fieldSource);
            }
            Thread.sleep(350L);
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(4.001, 0.0, 0.0)
            );
            assertTrue(
                    ((List<?>) staticField("REVERB_POOL")).size() <= 4,
                    "Finished room responses must return to the warm reserve; size="
                            + ((List<?>) staticField("REVERB_POOL")).size()
            );
            RoomAcoustics editedRemoteFieldRoom = new RoomAcoustics(
                    0.45F, 0.62F, 0.24F, 0.51F, 0.86F,
                    1.1F, 0.48F, 1.08F,
                    0.18F, 0.013F, Vec3.ZERO,
                    0.7F, 0.025F, Vec3.ZERO,
                    0.35F, 0.03F, 0.96F
            );
            AcousticResult editedRemoteRoomResult = new AcousticResult(
                    0.7F, 0.8F,
                    0.7F, 0.65F, 0.55F, 0.45F,
                    0.2F, 0.62F,
                    new EarlyReflection(
                            0.18F, 0.61F, 0.031F,
                            new Vec3(-0.8, 0.0, -0.2)
                    ),
                    0.0F,
                    4.0, new Vec3(4.0, 0.0, 0.0),
                    RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                    new RoomProbe(
                            editedRemoteFieldRoom, List.of(), sourceField.openings()
                    ),
                    new Vec3(0.2, 0.0, 0.0)
            );
            setObjectLong(
                    remoteSourceBus,
                    "lastBoundaryUpdateNanoseconds",
                    System.nanoTime() - 1_000_000_000L
            );
            OpenALAcousticEffects.apply(source, editedRemoteRoomResult);
            assertSame(
                    remoteSourceBus,
                    sourceStateObject(source, "sourceRoomBus"),
                    "Moving inside a remote field must preserve its diffuse response"
            );
            RoomAcoustics evolvedRemoteRoom = (RoomAcoustics) objectField(
                    remoteSourceBus, "room"
            );
            assertTrue(
                    evolvedRemoteRoom.decayTime() < remoteFieldRoom.decayTime()
                            && evolvedRemoteRoom.decayTime() >= editedRemoteFieldRoom.decayTime(),
                    "A moving remote field must evolve toward its current measured boundary"
            );
            AcousticResult sealedRemoteRoomResult = new AcousticResult(
                    0.7F, 0.8F,
                    0.7F, 0.65F, 0.55F, 0.45F,
                    0.2F, 0.62F,
                    new EarlyReflection(
                            0.18F, 0.61F, 0.031F,
                            new Vec3(-0.8, 0.0, -0.2)
                    ),
                    0.0F,
                    4.0, new Vec3(4.0, 0.0, 0.0),
                    RoomAcoustics.OUTDOORS, RoomImpulseResponse.SILENT,
                    new RoomProbe(editedRemoteFieldRoom, List.of(), List.of()),
                    new Vec3(0.2, 0.0, 0.0)
            );
            OpenALAcousticEffects.apply(source, sealedRemoteRoomResult);
            Object sealedRemoteBus = sourceStateObject(source, "sourceRoomBus");
            assertSame(
                    remoteSourceBus,
                    sealedRemoteBus,
                    "An aperture edit must evolve the existing diffuse response"
            );
            assertTrue(
                    EXTEfx.alGetEffectf(
                            (int) objectField(
                                    sourceStateObject(source, "earlyReflectionBus"),
                                    "effect"
                            ),
                            EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN
                    ) > 0.0F,
                    "A sealed remote field must not suppress its independently calculated echo"
            );
            int continuousEarlyPoolSize = ((List<?>) staticField(
                    "EARLY_REFLECTION_POOL"
            )).size();
            for (int edit = 0; edit < 256; edit++) {
                OpenALAcousticEffects.apply(
                        source,
                        (edit & 1) == 0
                                ? editedRemoteRoomResult
                                : sealedRemoteRoomResult
                );
                Object continuousBus = sourceStateObject(
                        source, "earlyReflectionBus"
                );
                assertSame(
                        remoteEarlyBus,
                        continuousBus,
                        "Frequent topology edits must retain one continuous response state"
                );
                assertTrue(
                        EXTEfx.alGetEffectf(
                                (int) objectField(continuousBus, "effect"),
                                EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN
                        ) > 0.0F,
                        "A topology update suppressed a still-valid calculated echo"
                );
            }
            assertEquals(
                    continuousEarlyPoolSize,
                    ((List<?>) staticField("EARLY_REFLECTION_POOL")).size(),
                    "Frequent space edits must not accumulate retired echo networks"
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
            double indoorMilliseconds = (System.nanoTime() - indoorStarted) / 1_000_000.0;
            assertSame(
                    sourceStateObject(source, "reverbBus"),
                    sourceStateObject(source, "primaryLateBus"),
                    "The primary late send must remain listener-owned"
            );
            assertNotSame(
                    remoteSourceBus,
                    sourceStateObject(source, "primaryLateBus"),
                    "The source diffuse field must not replace listener late reverb"
            );
            OpenALAcousticEffects.apply(source, indoorResult);
            RoomProbe sealedListenerRoom = new RoomProbe(
                    indoor, List.of(), List.of()
            );
            OpenALAcousticEffects.updateListenerRoom(
                    sealedListenerRoom, Vec3.ZERO, 1L, 70L
            );
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(0.05, 0.0, 0.0)
            );
            float sealedOutputGain = (float) objectField(
                    staticField("activeReverbBus"), "outputGain"
            );
            assertTrue(
                    sealedOutputGain > 0.05F,
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
            float outputBeforeSameFieldUpdate = (float) objectField(
                    stablePhysicalRoomBus, "outputGain"
            );
            OpenALAcousticEffects.updateListenerRoom(
                    new RoomProbe(
                            shiftedEstimateInSameRoom, List.of(), List.of()
                    ),
                    new Vec3(0.25, 0.0, 0.0),
                    2L,
                    70L
            );
            assertSame(
                    stablePhysicalRoomBus,
                    staticField("activeReverbBus"),
                    "Numeric probe variation inside one physical field must not replace its global reverb bus"
            );
            assertTrue(
                    Math.abs((float) objectField(stablePhysicalRoomBus, "outputGain")
                            - outputBeforeSameFieldUpdate) < 0.05F,
                    "Moving inside one diffuse field must not pump its global output gain"
            );
            setStaticLong(
                    "lastListenerRoomUpdateNanoseconds",
                    System.nanoTime() - 1_000_000_000L
            );
            OpenALAcousticEffects.updateListenerPosition(
                    new Vec3(0.251, 0.0, 0.0)
            );
            RoomAcoustics evolvedField = (RoomAcoustics) objectField(
                    stablePhysicalRoomBus, "room"
            );
            assertTrue(
                    evolvedField.decayTime() > indoor.decayTime()
                            && evolvedField.decayTime() < shiftedEstimateInSameRoom.decayTime(),
                    "A live feedback field must continuously adopt the measured space instead of changing gain only"
            );
            OpenALAcousticEffects.updateListenerRoom(
                    sealedListenerRoom,
                    new Vec3(0.3, 0.0, 0.0),
                    3L,
                    71L
            );
            assertSame(
                    stablePhysicalRoomBus,
                    staticField("activeReverbBus"),
                    "A scene revision must update boundary conditions without resetting stored feedback"
            );
            RoomProbe cavePortalProbe = new RoomProbe(
                    indoor,
                    List.of(),
                    List.of(new RoomProbe.OpeningSample(
                            new Vec3(0.5, 0.0, 0.0),
                            new Vec3(1.0, 0.0, 0.0),
                            1.0F
                    ))
            );
            OpenALAcousticEffects.updateListenerRoom(
                    cavePortalProbe, Vec3.ZERO, 4L, 71L
            );
            RoomAcoustics adjacentCaveChamber = new RoomAcoustics(
                    0.35F, 0.82F, 0.47F, 0.58F, 0.91F,
                    3.4F, 0.52F, 1.42F,
                    0.42F, 0.024F, Vec3.ZERO,
                    1.4F, 0.041F, Vec3.ZERO,
                    0.7F, 0.03F, 0.93F
            );
            OpenALAcousticEffects.updateListenerRoom(
                    new RoomProbe(adjacentCaveChamber, List.of(), List.of()),
                    new Vec3(1.0, 0.0, 0.0),
                    5L,
                    72L
            );
            Object adjacentFieldBus = staticField("activeReverbBus");
            assertSame(
                    stablePhysicalRoomBus,
                    adjacentFieldBus,
                    "Crossing a finite opening must evolve one persistent listener field"
            );
            float previousFieldGain = (float) objectField(
                    adjacentFieldBus, "outputGain"
            );
            for (int edit = 0; edit < 128; edit++) {
                RoomAcoustics changedBoundary = (edit & 1) == 0
                        ? shiftedEstimateInSameRoom
                        : adjacentCaveChamber;
                OpenALAcousticEffects.updateListenerRoom(
                        new RoomProbe(changedBoundary, List.of(), List.of()),
                        new Vec3(1.0 + edit * 0.01, 0.0, 0.0),
                        6L + edit,
                        73L + edit
                );
                assertSame(
                        adjacentFieldBus,
                        staticField("activeReverbBus"),
                        "Rapid block edits must not allocate or reset another feedback network"
                );
                float fieldGain = (float) objectField(
                        adjacentFieldBus, "outputGain"
                );
                assertTrue(Float.isFinite(fieldGain));
                assertTrue(
                        Math.abs(fieldGain - previousFieldGain) < 0.05F,
                        "A block revision introduced a discontinuous late-field level"
                );
                previousFieldGain = fieldGain;
            }
            Object leftEarlyBus = sourceStateObject(source, "earlyReflectionBus");
            assertNotNull(
                    leftEarlyBus,
                    "A calculated first-order reflection must use its source-owned send"
            );
            AcousticResult movedReflectionResult = new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.25F, 0.8F,
                    new EarlyReflection(
                            0.18F, 0.62F, 0.043F,
                            new Vec3(0.3, 0.0, -0.95)
                    ),
                    0.0F, 4.0,
                    new Vec3(4.0, 0.0, 0.0),
                    indoor,
                    RoomImpulseResponse.SILENT
            );
            OpenALAcousticEffects.applyBeforePlay(source, movedReflectionResult);
            assertSame(
                    leftEarlyBus,
                    sourceStateObject(source, "earlyReflectionBus"),
                    "A moving reflection must morph its existing delay line instead of switching it off"
            );
            assertEquals(
                    0.043F,
                    EXTEfx.alGetEffectf(
                            (int) objectField(leftEarlyBus, "effect"),
                            EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY
                    ),
                    1.0E-6F,
                    "The early path delay must reach EFX continuously without a quantized time step"
            );
            AcousticResult temporarilySilentReflection = new AcousticResult(
                    1.0F, 1.0F,
                    1.0F, 1.0F, 1.0F, 1.0F,
                    0.25F, 0.8F,
                    EarlyReflection.SILENT,
                    0.0F, 4.0,
                    new Vec3(4.0, 0.0, 0.0),
                    indoor,
                    RoomImpulseResponse.SILENT
            );
            OpenALAcousticEffects.applyBeforePlay(
                    source, temporarilySilentReflection
            );
            assertSame(
                    leftEarlyBus,
                    sourceStateObject(source, "earlyReflectionBus"),
                    "A zero-energy snapshot must stop input without deleting a pending echo"
            );
            assertEquals(
                    0.0F,
                    EXTEfx.alGetFilterf(
                            sourceStateInt(source, "earlyReflectionFilter"),
                            EXTEfx.AL_LOWPASS_GAIN
                    ),
                    1.0E-6F
            );
            OpenALAcousticEffects.applyBeforePlay(source, indoorResult);
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
            OpenALAcousticEffects.updateListenerRoom(new RoomAcoustics(
                    0.75F, 0.68F, 0.33F, 0.72F, 0.95F,
                    1.8F, 0.65F, 1.15F,
                    0.28F, 0.018F, Vec3.ZERO,
                    1.2F, 0.032F, Vec3.ZERO,
                    0.6F, 0.08F, 0.94F
            ));
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
            Object establishedFeedbackRoom = objectField(gainOnlyBus, "room");
            OpenALAcousticEffects.updateListenerRoom(fastChangedRoom);
            assertSame(
                    gainOnlyBus,
                    staticField("activeReverbBus"),
                    "Fast gain, spectrum and delay changes must not restart the decay network"
            );
            RoomAcoustics continuouslyMorphedRoom = (RoomAcoustics) objectField(
                    gainOnlyBus, "room"
            );
            assertNotSame(
                    establishedFeedbackRoom,
                    continuouslyMorphedRoom,
                    "The retained feedback network must continuously receive its evolved boundary coefficients"
            );
            assertTrue(
                    Math.abs(continuouslyMorphedRoom.decayTime()
                            - ((RoomAcoustics) establishedFeedbackRoom).decayTime()) < 0.05F,
                    "A fast boundary update must evolve instead of stepping to an unrelated decay"
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
            OpenALAcousticEffects.applyOnsetCorrection(source, staleResult, 150L);
            assertEquals(
                    acceptedDirectGain,
                    sourceStateFloat(source, "directGain"),
                    "A late onset completion must not overwrite a newer moving-source snapshot"
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
            assertSame(
                    establishedBus,
                    migratedBus,
                    "A new listener space must evolve the persistent acoustic energy state"
            );
            assertNotSame(
                    establishedKey,
                    objectField(migratedBus, "key"),
                    "The persistent field must index the newly measured boundary"
            );
            assertSame(migratedBus, sourceStateObject(source, "reverbBus"));
            assertSame(
                    migratedBus,
                    sourceStateObject(oneShot, "reverbBus"),
                    "A listener-room transition must keep every active wet send on the same state"
            );
            setStaticLong("lastListenerRoomUpdateNanoseconds", 0L);
            OpenALAcousticEffects.updateListenerRoom(RoomAcoustics.OUTDOORS);
            setStaticLong(
                    "lastListenerRoomUpdateNanoseconds",
                    System.nanoTime() - 1_000_000_000L
            );
            OpenALAcousticEffects.updateListenerPosition(new Vec3(12.0, 0.0, 0.0));
            int persistentSlot = (int) objectField(establishedBus, "slot");
            assertTrue(
                    EXTEfx.alGetAuxiliaryEffectSlotf(
                            persistentSlot, EXTEfx.AL_EFFECTSLOT_GAIN
                    ) < indoor.gain(),
                    "The persistent field must evolve toward the outdoor boundary"
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

            // Repeated batches model a long play session, not merely one concurrent
            // peak. Every early network is allowed to finish its real delay before it
            // is reclaimed; no live voice or calculated effect is dropped to make room.
            for (int cycle = 0; cycle < 24; cycle++) {
                List<Integer> transientSources = new ArrayList<>();
                for (int voice = 0; voice < 12; voice++) {
                    int transientSource = AL10.alGenSources();
                    assertTrue(
                            transientSource != 0,
                            "OpenAL source allocation degraded in cycle " + cycle
                    );
                    transientSources.add(transientSource);
                    double angle = (cycle * 12 + voice) * 0.371;
                    AcousticResult transientResult = new AcousticResult(
                            0.8F, 0.75F,
                            0.8F, 0.72F, 0.61F, 0.5F,
                            0.22F, 0.7F,
                            new EarlyReflection(
                                    0.18F + voice * 0.01F,
                                    0.62F,
                                    0.001F,
                                    new Vec3(Math.cos(angle), 0.0, Math.sin(angle))
                            ),
                            0.0F,
                            6.0,
                            new Vec3(Math.cos(angle) * 6.0, 0.0, Math.sin(angle) * 6.0),
                            indoor,
                            RoomImpulseResponse.SILENT
                    );
                    OpenALAcousticEffects.applyBeforePlay(
                            transientSource, transientResult
                    );
                }
                for (int transientSource : transientSources) {
                    OpenALAcousticEffects.releaseSource(transientSource);
                    AL10.alDeleteSources(transientSource);
                }
                Thread.sleep(2L);
                OpenALAcousticEffects.updateListenerPosition(
                        new Vec3(12.0 + cycle * 0.001, 0.0, 0.0)
                );
                assertTrue(
                        ((List<?>) staticField("EARLY_REFLECTION_POOL")).size() <= 6,
                        "Expired physical delay lines accumulated in cycle " + cycle
                );
                int probeSource = AL10.alGenSources();
                assertTrue(
                        probeSource != 0,
                        "A long session exhausted OpenAL sources after cycle " + cycle
                );
                AL10.alDeleteSources(probeSource);
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            }

            AcousticQualityConfig.setEnabled(false);
            OpenALAcousticEffects.useVanillaProcessing();
            assertEquals(
                    EXTLinearDistance.AL_LINEAR_DISTANCE_CLAMPED,
                    AL10.alGetSourcei(source, AL10.AL_DISTANCE_MODEL),
                    "The master switch must restore Minecraft's vanilla distance model"
            );
            assertTrue(
                    ((Map<?, ?>) staticField("SOURCES")).isEmpty(),
                    "The master switch must detach every mod-owned live source state"
            );
            AcousticQualityConfig.setEnabled(true);
            OpenALAcousticEffects.applyBeforePlay(source, indoorResult);
            assertEquals(AL10.AL_NONE, AL10.alGetSourcei(source, AL10.AL_DISTANCE_MODEL));
        } finally {
            AcousticQualityConfig.setEnabled(true);
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
        assertTrue(buses.size() >= 4);
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

    private static int sourceStateInt(int source, String fieldName)
            throws ReflectiveOperationException {
        Object state = sourceState(source);
        Field valueField = state.getClass().getDeclaredField(fieldName);
        valueField.setAccessible(true);
        return valueField.getInt(state);
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

    private static void setStaticObject(String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = OpenALAcousticEffects.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
