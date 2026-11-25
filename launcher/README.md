# Wabbit

A fast native Minecraft **1.21.11 + Fabric** launcher written in Rust (egui).
One 5.5 MB `wabbit.exe`, no browser engine; the window is up in ~150 ms.

Theme: Midnight Black `#101114`, Steel Silver `#A1A1AA`, Crimson Red `#E32636`.

## What it does

- **Microsoft sign-in with a code** (live.com device flow → Xbox → Minecraft), refresh token saved in `%APPDATA%\wabbit\settings.json`.
- **Installs everything itself** into `%APPDATA%\.wabbit`: Mojang's Java 21 runtime, the 1.21.11 client, libraries (x64 natives), assets, Fabric Loader 0.20.1. Every file is SHA-1 checked; identical files already in `%APPDATA%\.minecraft` or `.pikaclient` are copied instead of downloaded (a full install took 29 s on the dev PC).
- **Performance mods from Modrinth** (`src/mods.rs`): Fabric API, Sodium, Sodium Extra, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, Krypton, Dynamic FPS, BadOptimizations, Cull Leaves, Debugify, FastQuit (+ Cloth Config), Fast IP Ping, Language Reload, Mod Menu. Only files Wabbit installed are ever replaced; your own jars are left alone.
- **Import from Lunar Client** (Settings tab, `src/lunar.rs`): reads the player's own `%USERPROFILE%\.lunarclient` and copies one profile's game settings and keybinds (`options.txt`), server list, Fabric mods, resource packs and shader packs into Wabbit. Lunar is never changed, and Wabbit's overwritten files go to `<game dir>\backups\lunar-import-<time>\`.
  - Version profiles keep mods in `mods/fabric-<mc>/`, and their packs, shaders and mod configs in Lunar's game directory (`settings/launcher.json` → `gameDirectory`, default `.minecraft`). Modpack profiles keep everything in the profile folder; `mods/dependencies` is skipped because it only holds unpacked copies of libraries already inside the jars.
  - Each jar is looked up on Modrinth by SHA-1:
    - A jar Modrinth lists for 1.21.11 is copied as it is.
    - Otherwise it is swapped for the project's newest 1.21.11 **release**.
    - With no Modrinth build, it is copied only if its `depends.minecraft` allows 1.21.11. Otherwise it is skipped and reported.
  - Mods Wabbit installs itself (Sodium, Fabric API, …) are left to Wabbit. Missing dependencies are fetched. Iris is added when shader packs come over.
  - Lunar's built-in features (keystrokes, cosmetics, …) and their settings can't be moved.
  - Before every Play, `lunar::validate` switches off (renames to `.jar.disabled`) any imported mod with a missing or wrong-version dependency, a `breaks` conflict, or a duplicate of a Wabbit mod. Imported mods are listed with on/off switches at the bottom of the Mods tab.
- **eqsual on the left** (the launcher's character): the real skin of the player `eqsual` (UUID `1f0af4ba70144850803dc55c714f2d3f`), drawn as pixel art. The last skin is cached so startup never waits on the network; every button click re-checks Mojang (at most once per 15 s — Mojang rate-limits) and redraws if the skin changed.

## Build

```bash
cargo build --release        # -> target/release/wabbit.exe  (copy to dist/)
```

## Test hooks

```bash
WABBIT_SHOT=out.png [WABBIT_TAB=play|mods|settings] wabbit.exe   # screenshot + out.json (first-frame time), then exit
WABBIT_SELFTEST=<empty dir> wabbit.exe                           # real install + mods into <dir>, then start the game offline
WABBIT_DIALOG=import wabbit.exe                                  # open with the Lunar import dialog showing
WABBIT_IMPORT=<dir> [WABBIT_IMPORT_PROFILE=<lunar profile folder>] [WABBIT_IMPORT_LAUNCH=1] wabbit.exe
                                                                 # headless Lunar import into <dir>, Wabbit mods, pre-Play check, optionally start the game offline
cargo test --release lunar                                       # version-range parser tests
```

Game output goes to `<game dir>\logs\wabbit-launch.log` (plain text).
