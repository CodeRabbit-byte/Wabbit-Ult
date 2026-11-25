Wabbit launcher - step 1 (core), Rust

Build:   cargo build --release      Test: cargo test
         The program is target/release/wabbit(.exe).
Easy:    double-click wabbit.exe, type a player name, and it installs and starts
         Minecraft 1.21.11 with Fabric. Keep its window open while you play.
Use:     wabbit fabric 1.21.11
         wabbit launch fabric-loader-<ver>-1.21.11 --name YourName
         Options (--name, --java, --mem, --dir) can go anywhere on the line.
Needs Java 21 on PATH (or --java <path>) for 1.21.x. The launcher checks the Java version first
and says so if it is too old.

Done:    version download (checksum verified), libraries (sha1 verified for Mojang and Fabric,
         parallel), assets, Fabric install, inheritsFrom merge, Mojang rules, offline-account launch.
         Downloads time out instead of hanging, and are retried 3 times.
Not yet: Microsoft sign-in, auto Java download, mod profiles + auto-installing the Wabbit mod,
         Tauri UI, log-reading trackers.
Status:  tested on Windows: unit tests pass, and a real install + launch of 1.21.11 with Fabric
         reaches the game. macOS ARM natives are untested.

Changes in 0.1.2
 - Double-clicking the exe now works: it asks for a player name (and remembers it), gets
   Minecraft 1.21.11 + Fabric ready and starts the game. Before, the window just flashed and closed
 - Any error is shown and the window waits for Enter instead of closing
 - The Java message links to https://adoptium.net

Changes in 0.1.1
 - A relative --dir (e.g. --dir game) no longer breaks launching
 - Downloads time out (15 s to connect, 60 s without data) instead of hanging forever
 - Fabric libraries are checked against Fabric's sha1; the version file is checked against Mojang's
 - Clear message when Java is missing or older than the version needs
 - Libraries are filtered by OS before duplicates are removed (fixes versions that list one per OS)
 - Duplicate downloads to the same file are skipped instead of racing
 - Options work in any order; unknown options, missing values and bad player names are reported
 - Re-installs skip already-verified game assets by size instead of hashing several hundred MB again
 - Player names can no longer expand launch placeholders such as ${auth_access_token}
 - The program is now called wabbit(.exe), as the usage text says
