# Viewpoint for Mac

![platform](https://img.shields.io/badge/platform-macOS-52525b?style=flat-square&logo=apple&logoColor=white&labelColor=18181b)
![Project Zomboid](https://img.shields.io/badge/Project%20Zomboid-42.21-52525b?style=flat-square&logo=steam&logoColor=white&labelColor=18181b)
![OpenGL](https://img.shields.io/badge/OpenGL-4.5%20on%202.1-52525b?style=flat-square&logo=opengl&logoColor=white&labelColor=18181b)
![ZombieBuddy](https://img.shields.io/badge/ZombieBuddy-2.3%2B-52525b?style=flat-square&labelColor=18181b)
![Java](https://img.shields.io/badge/Java-21%2B-52525b?style=flat-square&logo=openjdk&logoColor=white&labelColor=18181b)
![version](https://img.shields.io/badge/version-0.1.0-52525b?style=flat-square&labelColor=18181b)
![license](https://img.shields.io/badge/license-MIT-52525b?style=flat-square&labelColor=18181b)

A ZombieBuddy Java mod that lets [Project Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) run on macOS.

On a Mac, Project Zomboid gets a legacy OpenGL 2.1 context: 16 texture units and GLSL 1.20. Viewpoint is written for OpenGL 4.5, with GLSL 3.30, direct state access, texture and sampler objects, indirect draws and around 140 texture units. This mod translates between the two. On Windows and Linux it does nothing.

## How it works

### What gets rewritten as it loads

`Remap` rewrites Viewpoint's bytecode as the classes load. Any call into LWJGL's GL classes that `G` has a stand-in for, with the same name and descriptor, goes to `G` instead. The game's own GL calls stay as they are.

`Addons` does the same for every installed mod whose `mod.info` requires Viewpoint, using that mod's `javaPkgName`. It finds them at start-up, so add-ons that draw for Viewpoint get the same stand-ins and the same shader translation. Some add-ons reach LWJGL by reflection (`getDeclaredMethod` on `GL33`…), and `Reflect` hands those `G`'s methods instead.

`SlotPatch` limits Viewpoint's model batches (`ModelBatches.slot`) to as many textures as the model shaders keep. That number lives in `ModelSlots`. It starts at 8 and only drops when a model program can't fit the Mac's units any other way.

### Shaders

`Glsl` translates each shader when `glShaderSource` is called, targeting GLSL 1.20 with `EXT_gpu_shader4`, `EXT_texture_array`, `ARB_draw_instanced` and `ARB_shader_texture_lod`. `in` and `out` become `attribute` and `varying`, and `layout(location)` turns into bind calls made before linking. `texture()`, `texelFetch()` and the other texture functions become overloaded helpers, and so do `inverse()` and `determinant()`. A `samplerBuffer` becomes a 2D texture read row by row. Declarations end up one per line.

`Prog` links the program. When a program uses more than 16 texture units, its cheapest `sampler2D`s move into the `Atlas`: small tables first, then screen buffers, then depth, and the relief last (see `Glsl.cost`). Each one is copied into the atlas whenever it changes and read through two uniforms. Huge textures are stored at most 2048 a side, though the shader still sees their real size, and all atlases together stay under 768 MB. If nothing is left to move and the program has the model texture array, it keeps fewer model textures.

### State the 2.1 context lacks

The layer keeps this state in software and makes it real right before each draw.

- `G` and `Draw` handle virtual texture units, mapped onto the 16 real ones for each draw through a plan cached per program. Between them they also cover sampler objects (`Smp`), immutable storage and views (`Tex`), buffer textures and indirect commands (`Buf`, `Merged`), vertex array objects with separate formats (`Vao`), indirect multi-draws with base instance, per-buffer clears, image copies and range mapping.
- `NoAttribs` exists because legacy GL draws nothing unless attribute 0 is enabled, while Viewpoint's full-screen passes build their triangle from `gl_VertexID` alone. Those draws get a stand-in attribute 0.
- `OutsideBinds` catches model textures that Viewpoint binds with the game's own `Texture.bind()`, which bypasses `G`. A scratch real unit picks up those binds.
- `Readback` sends depth reads into a pixel-pack buffer through a GPU copy, so they don't stall. The performance notes below explain why that matters.
- `Timers` builds `glQueryCounter` timestamps from chained elapsed-time queries when asked to.

For diagnostics there are `Dump` (requests in `dump.txt`), `Stats` (counts and GPU reports, plus one automatic measurement per game), `Prof` (a stack sampler) and `HotReload` (for development).

## Status

The offline checks pass on an M5 Pro (`tools/test.sh`). The mod compiles and all 55 smoke checks pass. Every shader in Viewpoint's packs (Default, Library, Vivid and Vanilla) and in True Weathers' pack compiles and links, using the atlas where the game would. The 12 installed Workshop add-ons built on Viewpoint go through `Remap`, their shader files compile, and none of the GL calls they keep is missing on the Mac.

In the game, before the 2026-10-25 performance pass, Viewpoint drew its lit scene, sky and clouds, the player's character and zombies at 27 to 37 fps on an M5 Pro at 1920x1200. That pass removed the depth-read stall and cut the work per draw, but it hasn't been measured in the game yet. The next game measures itself.

Workshop add-ons have only been checked offline so far. True Weathers, Immersive Physics & Weather, Recoil & ADS, Advanced Throwables and True Ballistics draw through the layer. Door, Surface, Near Vegetation, QOL, Isometric No More and Immersive Driving don't need anything from it.

An independent review on 2026-10-25 turned up these bugs. They are fixed now, and each one has a smoke check that fails without its fix.

- Per-draw data with a divisor of 2^30, which Viewpoint uses for its pack draws, always read the first draw's data, because the layer divided the base instance by the divisor.
- Reading a buffer the GPU had written into, as the lamp read-backs do, returned the stale CPU copy.
- Textures and buffers deleted and created again under the same name kept their old contents in the atlas and in buffer textures.
- A few state leaks: after a failed indirect draw, from GL errors on names only the layer knows, and from debug reads.

Still open: Merged's "buffers of different formats share a texture" warning.

The Mac can't run Minecraft-style shader packs (Iris), which need compute, tessellation, UBOs or image load/store. Upscalers aren't supported either, and DLSS, FSR and XeSS are Windows-only anyway.

## Install

You need Project Zomboid on Steam with ZombieBuddy, and JDK 21 or newer for `javac`. The game's own JRE runs the mod.

```bash
tools/build.sh --install            # → ~/Zomboid/mods/ViewpointMac
```

In the game, enable ZombieBuddy, Viewpoint for Mac and Viewpoint, then approve the ViewpointMac JAR when ZombieBuddy asks. It asks again after every new build. The launch option is `-javaagent:ZombieBuddy.jar --`. Keep Steam running, or the game stops while booting.

## For add-on authors

If your `mod.info` has `require=...,\Viewpoint` and a `javaPkgName`, your add-on works on the Mac with no extra work, because its classes get rewritten the same way Viewpoint's do. A package that draws for Viewpoint without requiring it can be listed in `~/Zomboid/viewpointmac/packages.txt`.

A few things help:

- Compile shaders and set uniforms either through Viewpoint (`GlProgram`, shader pack passes) or through LWJGL from your own package. Both reach the translator. Write GLSL 3.30.
- Use as many texture units as you like, since the Mac maps them onto its 16 real ones for each draw. `GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS` returns 188, and the last few units belong to the layer.
- A program can sample more than 16 textures, because the extra `sampler2D`s move into an atlas. Integer, array, cube, 3D and shadow samplers can't move, so keep those few. A model program that still doesn't fit makes every model batch hold fewer textures.
- Compute shaders, image load/store, uniform and storage buffer blocks and tessellation aren't available on the Mac. Depth reads into a pixel-pack buffer are fine up to 64x64, and the layer makes them non-blocking.

## Development

Before installing a build, run every offline check:

```bash
tools/test.sh
```

The script compiles the mod and runs the smoke test. Then it compiles and links every shader pack with the defines the game would use, and runs each installed add-on and Viewpoint itself through `Remap`, checking the result against what the Mac's context really exports. Last, it reports the layer's cost per frame. It exits non-zero if anything fails.

Each tool it uses also works on its own:

| Tool | What it does |
|---|---|
| `tools/SmokeTest.java` | Drives the stand-ins the way Viewpoint does, in a hidden Mac context, and checks both the pixels and the bookkeeping. |
| `tools/check.sh` (`ShaderCheck`) | Translates, compiles and links a folder of shaders. Pass `-Ddefines=` and `-Dpacks=` for a pack's real defines, or `-Dverbose` to trace atlas packing. |
| `tools/RemapCheck.java` | Runs `Remap` over extracted classes. With `-Dcontext` it also checks that every GL call left for LWJGL exists on the Mac. |
| `tools/bench.sh` | Times a Viewpoint-shaped frame through the layer, on the CPU and until the GPU is done. Give it another build's classes to compare, or set `BENCH_OPTS=-Dprofile=S` to sample stacks. |
| `tools/probe.sh` | Measures the Mac's legacy context itself: its extensions and limits, and which reads and writes stall. |
| `tools/packplan.sh` | Plans atlas packing offline for given shader pairs. |

### With the game running

- `tools/game.sh start` launches the game with ZombieBuddy's local Lua server and loads a copy of a save (`Apocalypse/vpmac-test`, never the player's own) past "click to start". `tools/game.sh stop` quits and restores the player's Continue. In between you can use `view`, `lua`, `log`, `crash` and `shot`, which captures only the game's window.
- `tools/dev.sh` compiles into `build/dev-classes`, and the running game watches that folder. Changed method bodies and brand-new classes take effect in about a second.
- `tools/perfcheck.sh` measures a running game in one go: frame rate, `stats:`, `gpu:` and `profile:`.
- Lines written to `~/Zomboid/viewpointmac/dump.txt` ask for a look inside the next frames (see `Dump`). You can ask for a sampler's texture, `N:bindings`, `after:N` (what program N drew), `uniforms:N`, `set:N:uName=value`, `errors` or `screen`. PNGs go to `dumps/` and value ranges go to the log. `stats:S` logs what the layer did per second over S seconds. `gpu:S` logs Viewpoint's own GPU time per pass along with the layer's (atlas copies, buffer textures, texture views, depth reads). `profile:S` writes `profile.txt`. Requests nothing answers within 20 s are dropped, and `clear` drops them right away.
- Once per game, a minute after Viewpoint starts drawing for real, the layer measures itself for 20 s (frames per second and counts, then GPU passes) and writes the results to the log. A file named `no-auto-measure` next to `dump.txt` turns this off.
- Shaders the Mac refuses are saved with their logs under `~/Zomboid/viewpointmac/failed-shaders/`. Programs that use the atlas are saved under `packed-shaders/`, as the game built them.

## Performance notes

These findings come from `tools/probe.sh` and `tools/bench.sh` on an M5 Pro (macOS 27, "2.1 Metal"), along with what the layer does about each one.

- A `glReadPixels` of `DEPTH_COMPONENT`/`FLOAT` into a pixel-pack buffer blocks the CPU until the GPU has finished everything queued, 34 ms behind 35 ms of work. Color reads into a pack buffer (`RED`/`RG`/`RGBA` as `FLOAT`) don't wait. Viewpoint reads the depth under the cursor every frame, so `Readback` copies that depth on the GPU into an R32F target and reads that instead. The bits come out identical and the read takes about 0.1 ms.
- `EXT_timer_query` works, but `ARB_timer_query` doesn't exist. `Timers` builds `glQueryCounter` timestamps from a chain of elapsed-time queries. Each switch costs about 35 µs of CPU, so the timers only run when asked for. Occlusion queries go straight to GL.
- The GPU handles plain fills quickly: 40 full-screen passes over 1920x1200 RGBA16F targets take 0.6 ms, which is also why atlas copies of screen buffers are cheap. Clearing a target before drawing over all of it is slower (2.7 ms), since the Mac's GL doesn't turn the clear into a load action.
- Indirect draws can't be merged into fewer GL calls. Each command brings its own per-instance data (`aMesh`, `aDraw`) and 2.1 has no base instance, so every command is its own draw.
- A `glGetIntegerv` costs about 0.35 µs and a draw with unchanged state about 0.1 µs. For each draw the layer sticks to a cached plan (`Draw.plan`), saves the array buffer once per vertex-array replay or indirect draw, and does nothing for `Dump` unless something was asked for. What remains in the bench is the GL calls themselves: the draws, and one binding query per borrowed unit. That query stays because the game's own code binds textures behind the layer's back.
- Over 2000 bench frames, repeated, the layer's CPU cost per frame went from 1.02 to 1.03 ms before the 2026-10-25 pass down to 0.81 to 0.83 ms after.

## License

MIT, see [LICENSE](LICENSE). Project Viewpoint, ZombieBuddy and Project Zomboid belong to their own authors. This repository holds none of their code, only the shader constants Viewpoint generates (`tools/constants.glsl`, `tools/runtime-defines.glsl`), which the offline checks need.
