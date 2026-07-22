<div align="center">

<img src="src/main/resources/assets/acousticsystem/icon.png" width="128" height="128" alt="AcousticSystem logo"/>

# Sound Acoustic Physics

**Real-time acoustic physics for Minecraft worlds**

[![Version](https://img.shields.io/badge/Version-1.16.1-f2b632?style=for-the-badge)](https://github.com/Mkkas3145/AcousticSystem/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1.2%20%7C%2026.2-62b47a?style=for-the-badge)](#compatibility)
[![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-4c78a8?style=for-the-badge)](#compatibility)
[![한국어](https://img.shields.io/badge/README-한국어-0a66c2?style=for-the-badge)](README.ko.md)

[Features](#features) · [How it works](#how-it-works) · [Compatibility](#compatibility) · [Installation](#installation) · [Building](#building) · [Report a bug](https://github.com/Mkkas3145/AcousticSystem/issues)

</div>

---

## What does it do?

Sound changes when a wall stands in the way, when you step out of a room, or when you enter water. AcousticSystem continuously reads the current world geometry, so placing or breaking blocks also affects sounds that are already playing.

## Features

- Occlusion based on wall material and thickness
- Sound propagation around corners, through openings, and along connected structures
- Reflections from walls, floors, and ceilings
- Reverb shaped by room size, openings, and surface materials
- Physical sound transitions between air, water, and lava
- Continuous updates for moving sources, listeners, and changed blocks
- Four quality presets with detailed settings
- JSON-based acoustic properties for blocks and fluids

## How it works

Every positional world sound goes through the same propagation pipeline. The result is not selected from a list of room presets.

```text
world geometry and materials
            ↓
direct sound and transmission
            ↓
diffraction and structural paths
            ↓
early reflections and late-field tracing
            ↓
distance, atmosphere, and medium losses
            ↓
real-time software mixer
```

### Propagation and occlusion

The direct path is sampled as a finite first Fresnel-zone wavefront instead of a single center ray. Each sample is intersected with the actual collision shapes, and the distance travelled inside every material is accumulated. Transmission loss is then applied independently across eight frequency bands, so several layers continue to attenuate the sound and thin or partial blocks do not behave like full one-metre walls.

### Diffraction

When the direct wavefront is blocked, the solver builds an on-demand hierarchy of chunk, portal, visibility, and diffraction-edge nodes. A* searches this graph for complete routes through doors, around corners, and across multi-turn passages. The finished paths are evaluated per frequency band with knife-edge diffraction based on the Fresnel parameter and the Fresnel–Kirchhoff attenuation approximation. Valid arrivals are combined by energy and direction instead of choosing one arbitrary doorway.

### Distance and air absorption

Free-field distance loss follows spherical spreading: pressure falls approximately as `1 / distance`, which corresponds to inverse-square intensity and about 6 dB of loss whenever distance doubles.

Air absorption uses the ISO 9613-1 model in each of the eight frequency bands. Temperature, relative humidity, and atmospheric pressure determine the oxygen and nitrogen relaxation losses. For a path of length `d`, each band receives the pressure gain `exp(-αd)`, so high frequencies disappear faster over long distances without imposing an artificial hearing cutoff.

### Reflections

Early reflections use image-source candidates from nearby surfaces. Each candidate is checked against current world geometry before its delay, direction, distance loss, and material-dependent absorption, transmission, and scattering are applied. This makes stone, wood, wool, and other material groups produce different reflected spectra.

### Room analysis and reverb

The late field is measured with multi-bounce Monte Carlo ray tracing. Rays lose energy at every material hit and escape through real openings. Opening loss is included with a Sabine-style room-energy model, while a 0.5 ms energy histogram records the arrival field. Backward Schroeder energy integration and decay-curve regression estimate RT60 for each frequency band from that traced response.

The measured field is rendered by an eight-delay-line Feedback Delay Network (FDN) running at 24 kHz. The network uses mutually incommensurate delays, an energy-preserving normalized Hadamard feedback matrix, and four Schroeder all-pass diffusers to build a dense tail without turning it into a repeated echo. Low, middle, and high feedback losses are derived separately from the measured RT60 ratios, and the coefficients morph continuously when the listener moves or the room changes.

### Fluids and medium boundaries

Air, water, and lava are treated as acoustic media with their own sound speed and impedance. Every path is split into its real medium segments. At a boundary, reflected and transmitted energy comes from the impedance relation between both media, while delay comes from the distance travelled at each medium's sound speed. Source coupling near a fluid surface is integrated over patches on the six voxel faces, which avoids turning a briefly exposed or partly submerged source into an all-air or all-water special case.

### Structure-borne sound

Contact sounds can also excite connected collision solids. A* follows face-connected solid blocks, while material coupling and frequency-dependent damping determine how much vibration reaches the listener. This is the path used for cases such as footsteps travelling through a floor; it is not tied to a specific sound ID.

### Dynamic updates and performance

World data is stored in immutable chunk-section snapshots. Block and fluid changes replace only affected sections, while propagation work runs away from the render thread. Active sounds are re-evaluated as the source, listener, or geometry changes, and the audio callback continuously applies the latest direction and acoustic coefficients. Quality presets adjust tracing budgets rather than switching to a different acoustic model.

Material absorption, transmission loss, scattering, medium impedance, and related band data are defined in `src/client/resources/assets/acousticsystem/acoustic_materials/default.json`.

## Compatibility

| Minecraft | Java | Fabric | Forge | NeoForge |
|---|---:|:---:|:---:|:---:|
| 1.21.11 | 21 | ✓ | ✓ | ✓ |
| 26.1.2 | 25 | ✓ | ✓ | ✓ |
| 26.2 | 25 | ✓ | ✓ | ✓ |

AcousticSystem is a client-side mod. Fabric installations also require the matching version of Fabric API. Its software mixer requires an OpenAL Soft device with `AL_SOFT_callback_buffer` support.

## Installation

1. Choose the JAR that matches your Minecraft version and mod loader.
2. Place it in your Minecraft `mods` folder.
3. Open **Music & Sound Options → Sound Physics** to select a quality preset or adjust individual settings.

## Building

Build the current Fabric target:

```bash
./gradlew build
```

Build every supported Minecraft and loader combination:

```bash
./gradlew buildAllMinecraftVersions
```

Finished JARs are collected under `build/libs/<Minecraft version>/`.

---

If you notice a bug or an unnatural sound, please open an [issue](https://github.com/Mkkas3145/AcousticSystem/issues) with your Minecraft version, mod loader, and steps to reproduce it.
