# Wabbit

A fast native Minecraft **1.21.11 + Fabric** launcher written in Rust (egui).
One 5.5 MB `wabbit.exe`, no browser engine; the window is up in ~150 ms.

Theme: Midnight Black `#101114`, Steel Silver `#A1A1AA`, Crimson Red `#E32636`.

## What it does

- **Microsoft sign-in with a code** (live.com device flow → Xbox → Minecraft), refresh token saved in `%APPDATA%\wabbit\settings.json`.
- **Installs everything itself** into `%APPDATA%\.wabbit`: Mojang's Java 21 runtime, the 1.21.11 client, libraries (x64 natives), assets, Fabric Loader 0.20.1. Every file is SHA-1 checked; identical files already in `%APPDATA%\.minecraft` or `.pikaclient` are copied instead of downloaded (a full install took 29 s on the dev PC).
- **Performance mods from Modrinth** (`src/mods.rs`): Fabric API, Sodium, Sodium Extra, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, Krypton, Dynamic FPS, BadOptimizations, Cull Leaves, Debugify, FastQuit (+ Cloth Config), Fast IP Ping, Language Reload, Mod Menu. Only files Wabbit installed are ever replaced; your own jars are left alone.
- **eqsual on the left** (the launcher's character): the real skin of the player `eqsual` (UUID `1f0af4ba70144850803dc55c714f2d3f`), drawn as pixel art. The last skin is cached so startup never waits on the network; every button click re-checks Mojang (at most once per 15 s — Mojang rate-limits) and redraws if the skin changed.

## Build

```bash
cargo build --release        # -> target/release/wabbit.exe  (copy to dist/)
```

## Test hooks

```bash
WABBIT_SHOT=out.png [WABBIT_TAB=play|mods|settings] wabbit.exe   # screenshot + out.json (first-frame time), then exit
WABBIT_SELFTEST=<empty dir> wabbit.exe                           # real install + mods into <dir>, then start the game offline
```

Game output goes to `<game dir>\logs\wabbit-launch.log` (plain text).
