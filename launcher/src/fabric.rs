use crate::download::{download_all, Job};
use crate::install::{fetch_json, fetch_text, install_vanilla, lib_job, Dirs};
use crate::model::*;
use anyhow::{Context, Result};

/// Installs vanilla + the latest stable Fabric loader for `mc`. Returns the Fabric version id to launch.
pub async fn install_fabric(client: &reqwest::Client, dirs: &Dirs, mc: &str) -> Result<String> {
    install_vanilla(client, dirs, mc).await?;

    let list: Vec<LoaderEntry> =
        fetch_json(client, &format!("https://meta.fabricmc.net/v2/versions/loader/{mc}")).await?;
    let loader = list.iter().find(|e| e.loader.stable).or(list.first())
        .context("no Fabric loader available for this Minecraft version")?
        .loader.version.clone();

    let url = format!("https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json");
    let text = fetch_text(client, &url).await?;
    let v: VersionJson = serde_json::from_str(&text)?;

    let path = dirs.version_json(&v.id);
    tokio::fs::create_dir_all(path.parent().unwrap()).await?;
    tokio::fs::write(&path, &text).await?;

    let jobs: Vec<Job> = v.libraries.iter().filter_map(|l| lib_job(dirs, l)).collect();
    println!("Downloading {} Fabric libraries...", jobs.len());
    download_all(client, jobs).await?;
    Ok(v.id)
}
