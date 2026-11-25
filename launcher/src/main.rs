use anyhow::{bail, Result};
use std::path::PathBuf;
use wabbit_launcher::{download::new_client, fabric::install_fabric, install::{install_vanilla, Dirs},
    launch::{build_command, LaunchOptions}};

fn default_root() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        return PathBuf::from(appdata).join(".wabbit");
    }
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".wabbit")
}

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned()
}

const USAGE: &str = "Wabbit launcher (core)\n\
  wabbit install <mc-version>            download vanilla (e.g. 1.21.11)\n\
  wabbit fabric  <mc-version>            install vanilla + Fabric, prints the version id\n\
  wabbit launch  <version-id> [--name Player] [--java java] [--mem 4096]\n\
Add --dir <folder> to use a different game folder.";

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let dirs = Dirs { root: flag(&args, "--dir").map(PathBuf::from).unwrap_or_else(default_root) };
    std::fs::create_dir_all(&dirs.root)?;

    match (args.get(0).map(String::as_str), args.get(1)) {
        (Some("install"), Some(mc)) => {
            install_vanilla(&new_client()?, &dirs, mc).await?;
            println!("Installed {mc}");
        }
        (Some("fabric"), Some(mc)) => {
            let id = install_fabric(&new_client()?, &dirs, mc).await?;
            println!("Installed {id}");
        }
        (Some("launch"), Some(id)) => {
            let opts = LaunchOptions {
                version_id: id.clone(),
                player_name: flag(&args, "--name").unwrap_or_else(|| "Player".into()),
                java: flag(&args, "--java").unwrap_or_else(|| "java".into()),
                max_mem_mb: flag(&args, "--mem").and_then(|m| m.parse().ok()).unwrap_or(4096),
            };
            let status = build_command(&dirs, &opts)?.status()?;
            if !status.success() {
                bail!("Minecraft exited with {status}");
            }
        }
        _ => println!("{USAGE}"),
    }
    Ok(())
}
