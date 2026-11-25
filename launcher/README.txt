Wabbit launcher - step 1 (core), Rust

Build:   cargo build --release      Test: cargo test
Use:     wabbit fabric 1.21.11
         wabbit launch fabric-loader-<ver>-1.21.11 --name YourName
Needs Java 21 on PATH (or --java <path>) for 1.21.x.

Done:    version download, libraries (sha1 verified, parallel), assets, Fabric install,
         inheritsFrom merge, Mojang rules, offline-account launch.
Not yet: Microsoft sign-in, auto Java download, mod profiles + auto-installing the Wabbit mod,
         Tauri UI, log-reading trackers.
Status:  written without a Rust compiler available - not yet compiled or run. Windows/Linux
         paths follow the manifest rules; macOS ARM natives are untested.
