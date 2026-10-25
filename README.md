# Viewpoint for Mac

A ZombieBuddy Java mod that lets [Project Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) run on macOS.

Project Zomboid gets a legacy **OpenGL 2.1** context on a Mac (16 texture units, GLSL 1.20). Viewpoint is written against OpenGL 4.5 (GLSL 3.30, direct state access, texture/sampler objects, indirect draws, ~140 texture units). This mod sits between the two; on Windows and Linux it does nothing.

## How it works

Rewriting what loads:

- **`Remap`** rewrites Viewpoint's bytecode as it loads: every call into LWJGL's GL classes that `G` has a stand-in for (same name and descriptor) is pointed at `G`. The game's own GL calls are untouched.
- **`Addons`** extends that to every installed mod whose `mod.info` requires Viewpoint (its `javaPkgName`), found at start-up, so add-ons that draw for Viewpoint get the same stand-ins and shader translation. Add-ons that reach LWJGL by reflection (`getDeclaredMethod` on `GL33`…) get `G`'s methods back through **`Reflect`**.
- **`SlotPatch`** makes Viewpoint's model batches (`ModelBatches.slot`) hold as many textures as the model shaders keep: **`ModelSlots`**, 8, fewer if a model program can't fit the Mac's units otherwise.

Shaders:

- **`Glsl`** translates each shader at `glShaderSource` time to GLSL 1.20 + `EXT_gpu_shader4`, `EXT_texture_array`, `ARB_draw_instanced`, `ARB_shader_texture_lod`: `in`/`out` → `attribute`/`varying`, `layout(location)` → bind calls before linking, `texture()`/`texelFetch()`/… → overloaded helpers, `samplerBuffer` → a 2D texture read row by row, `inverse()`/`determinant()` → helpers, one declaration a line.
- **`Prog`** links; a program over 16 texture units moves its cheapest `sampler2D`s into the **`Atlas`** (small tables first, screen buffers next, depth and then the relief last; see `Glsl.cost`), copied there whenever they change and read through two uniforms per texture. Huge textures are stored at most 2048 a side (the shader still sees their real size); all atlases together stay under 768 MB. When nothing is left to move and the program has the model texture array, it keeps fewer model textures.

State the 2.1 context lacks, kept in software and made real right before each draw:

- **`G`** and **`Draw`**: virtual texture units (mapped onto the 16 real ones per draw, through a plan cached per program), sampler objects (**`Smp`**), immutable storage and views (**`Tex`**), buffer textures and indirect commands (**`Buf`**, **`Merged`**), vertex array objects with separate formats (**`Vao`**), indirect multi-draws with base instance, per-buffer clears, image copies, range mapping.
- **`NoAttribs`**: legacy GL draws nothing unless attribute 0 is enabled, and Viewpoint's full-screen passes make their triangle from `gl_VertexID` alone; those draws get a stand-in attribute 0.
- **`OutsideBinds`**: Viewpoint binds model textures with the game's own `Texture.bind()`, which bypasses `G`; a scratch real unit catches those binds.
- **`Readback`**: depth reads into a pixel-pack buffer go through a GPU copy, so they don't stall (see the performance notes).
- **`Timers`**: `glQueryCounter` timestamps from chained elapsed-time queries, on demand.

Diagnostics: **`Dump`** (requests in `dump.txt`), **`Stats`** (counts and GPU reports, plus one automatic measurement a game), **`Prof`** (a stack sampler), **`HotReload`** (development).

## Status

- Offline (`tools/test.sh`, on an M5 Pro): the mod compiles; 55 smoke checks pass; every shader of Viewpoint's packs (Default, Library, Vivid, Vanilla) and of True Weathers' pack compiles and links, through the atlas where the game would use it; the 12 installed Workshop add-ons built on Viewpoint go through `Remap`, their shader files compile, and no GL call they keep is missing on the Mac.
- In game, before the 2026-10-25 performance pass: Viewpoint drew its lit scene, sky and clouds, the player's character and zombies, at 27–37 fps on an M5 Pro at 1920x1200. The pass (no depth-read stall, less work per draw) is not measured in game yet; the next game measures itself.
- Workshop add-ons are checked offline only, not yet in game. True Weathers, Immersive Physics & Weather, Recoil & ADS, Advanced Throwables and True Ballistics draw through the layer; Door, Surface, Near Vegetation, QOL, Isometric No More and Immersive Driving need nothing from it.
- Fixed on 2026-10-25 after an independent review, each with a smoke check that fails without the fix: per-draw data with a divisor of 2^30 (Viewpoint's pack draws) read only the first draw's (the base instance was divided by the divisor); reads of a buffer the GPU had written into (lamp read-backs) returned the stale CPU copy; textures and buffers deleted and made again under the same name kept their old contents in the atlas and buffer textures; a few state leaks (a failed indirect draw, GL errors from names only the layer knows, debug reads).
- Still open: Merged's "buffers of different formats share a texture" warning.

Not supported on the Mac (needs compute/tessellation/UBOs/image load-store): Minecraft-style shader packs (Iris), upscalers (DLSS/FSR/XeSS are Windows-only anyway).

## Install

Needs Project Zomboid (Steam) with ZombieBuddy, and a JDK ≥ 21 for `javac` (the game's own JRE runs the mod).

```bash
tools/build.sh --install            # → ~/Zomboid/mods/ViewpointMac
```

In the game: enable ZombieBuddy, Viewpoint for Mac and Viewpoint; approve the ViewpointMac JAR when ZombieBuddy asks (again after each new build). Launch option: `-javaagent:ZombieBuddy.jar --`. Steam must be running, or the game stops while booting.

## For add-on authors

An add-on works on the Mac without doing anything when its `mod.info` has `require=...,\Viewpoint` and a `javaPkgName`: its classes are rewritten like Viewpoint's. A package that draws for Viewpoint without requiring it can be listed in `~/Zomboid/viewpointmac/packages.txt`. What helps:

- Compile shaders and set uniforms through Viewpoint (`GlProgram`, shader pack passes), or through LWJGL from your own package; both reach the translator. Write GLSL 3.30.
- Texture units: use as many as you like (the Mac maps them onto its 16 real ones per draw); `GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS` answers 188, and the last units are the layer's own.
- A program may sample more than 16 textures; `sampler2D`s past that move into an atlas. Integer, array, cube, 3D and shadow samplers can't, so keep those few; a model program that still doesn't fit makes every model batch hold fewer textures.
- Not available on the Mac: compute shaders, image load/store, uniform and storage buffer blocks, tessellation. Depth reads into a pixel-pack buffer are fine (made non-blocking), up to 64x64.

## Development

Run every offline check before installing a build:

```bash
tools/test.sh
```

It compiles the mod, runs the smoke test, compiles and links every shader pack with the defines the game makes, checks each installed add-on and Viewpoint itself through `Remap` (against what the Mac's context really exports), and reports the layer's cost per frame. It exits non-zero on any failure.

The tools behind it, also usable alone:

| Tool | What it does |
|---|---|
| `tools/SmokeTest.java` | Drives the stand-ins the way Viewpoint does in a hidden Mac context and checks the pixels and the bookkeeping. |
| `tools/check.sh` (`ShaderCheck`) | Translates, compiles and links a folder of shaders; `-Ddefines=`/`-Dpacks=` for a pack's real defines, `-Dverbose` to trace atlas packing. |
| `tools/RemapCheck.java` | Runs `Remap` over extracted classes; `-Dcontext` checks every GL call left for LWJGL exists on the Mac. |
| `tools/bench.sh` | Times a Viewpoint-shaped frame through the layer (CPU and to GPU done); give it another build's classes to compare; `BENCH_OPTS=-Dprofile=S` samples stacks. |
| `tools/probe.sh` | Measures the Mac's legacy context itself: extensions, limits, which reads and writes stall. |
| `tools/packplan.sh` | Plans atlas packing offline for given shader pairs. |

With the game:

- `tools/game.sh start` launches the game with ZombieBuddy's local Lua server, loads a copy of a save (`Apocalypse/vpmac-test`, never the player's own) past "click to start"; `tools/game.sh stop` quits and restores the player's Continue. `view`, `lua`, `log`, `crash` and `shot` (the game's window only) work in between.
- `tools/dev.sh` compiles into `build/dev-classes`, which the running game watches: changed method bodies and brand-new classes take effect in about a second.
- `tools/perfcheck.sh` measures a running game in one go: frame rate, `stats:`, `gpu:` and `profile:`.
- Lines in `~/Zomboid/viewpointmac/dump.txt` ask for a look inside the next frames (see `Dump`): a sampler's texture, `N:bindings`, `after:N` (what program N drew), `uniforms:N`, `set:N:uName=value`, `errors`, `screen`; PNGs go to `dumps/`, ranges to the log. `stats:S` logs what the layer did per second over S seconds; `gpu:S` logs Viewpoint's own per-pass GPU times plus the layer's (atlas copies, buffer textures, texture views, depth reads); `profile:S` writes `profile.txt`. Requests nothing answers within 20 s are dropped; `clear` drops them at once.
- Once a game, a minute after Viewpoint starts drawing for real, the layer measures itself for 20 s (frames per second and counts, then GPU passes) and logs it; a file `no-auto-measure` next to `dump.txt` turns that off.
- Shaders the Mac refuses are saved with their logs under `~/Zomboid/viewpointmac/failed-shaders/`; programs that use the atlas, as the game built them, under `packed-shaders/`.

## Performance notes

What `tools/probe.sh` and `tools/bench.sh` found on an M5 Pro (macOS 27, "2.1 Metal"), and what the layer does about it:

- A `glReadPixels` of `DEPTH_COMPONENT`/`FLOAT` into a pixel-pack buffer stops the CPU until the GPU has finished everything queued (34 ms behind 35 ms of work). Color reads into a pack buffer (`RED`/`RG`/`RGBA` as `FLOAT`) don't wait. Viewpoint reads the depth under the cursor every frame, so `Readback` copies that depth on the GPU into an R32F target and reads that instead: same bits, about 0.1 ms.
- `EXT_timer_query` works, `ARB_timer_query` doesn't exist: `Timers` builds `glQueryCounter` timestamps from a chain of elapsed-time queries. Each switch costs ~35 µs of CPU, so they run only while asked for. Occlusion queries go straight to GL.
- The GPU is quick at plain fills: 40 full-screen passes over 1920x1200 RGBA16F targets take 0.6 ms. Clearing a target before drawing over it all is slower (2.7 ms), not faster: the Mac's GL doesn't turn the clear into a load action. Atlas copies of screen buffers are cheap for the same reason.
- Indirect draws can't be merged into fewer GL calls: each command brings its own per-instance data (`aMesh`, `aDraw`) and 2.1 has no base instance, so each one is a draw.
- A `glGetIntegerv` costs ~0.35 µs and a draw with unchanged state ~0.1 µs. Per draw, the layer keeps to a cached plan (`Draw.plan`), one array-buffer save per vertex-array replay or indirect draw, and nothing at all for `Dump` unless something was asked for. What is left in the bench is the GL calls themselves: the draws, and one binding query per borrowed unit (kept, because the game's own code binds textures behind the layer's back).
- Layer cost of the bench frame (2000 frames, repeated): 1.02–1.03 ms of CPU before the 2026-10-25 pass, 0.81–0.83 ms after.
