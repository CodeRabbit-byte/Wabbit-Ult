use crate::download::{download_all, Job};
use crate::model::*;
use crate::rules::rules_allow;
use anyhow::{Context, Result};
use serde::de::DeserializeOwned;
use std::path::PathBuf;

const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ASSET_URL: &str = "https://resources.download.minecraft.net";

#[derive(Clone)]
pub struct Dirs {
    pub root: PathBuf,
}

impl Dirs {
    pub fn versions(&self) -> PathBuf { self.root.join("versions") }
    pub fn libraries(&self) -> PathBuf { self.root.join("libraries") }
    pub fn assets(&self) -> PathBuf { self.root.join("assets") }
    pub fn version_json(&self, id: &str) -> PathBuf { self.versions().join(id).join(format!("{id}.json")) }
    pub fn version_jar(&self, id: &str) -> PathBuf { self.versions().join(id).join(format!("{id}.jar")) }
}

pub async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String> {
    Ok(client.get(url).send().await?.error_for_status()?.text().await?)
}

pub async fn fetch_json<T: DeserializeOwned>(client: &reqwest::Client, url: &str) -> Result<T> {
    let text = fetch_text(client, url).await?;
    serde_json::from_str(&text).with_context(|| format!("parsing {url}"))
}

/// "group:artifact:version[:classifier][@ext]" -> "group/path/artifact/version/artifact-version[-classifier].ext"
pub fn maven_path(name: &str) -> String {
    let (coords, ext) = match name.split_once('@') {
        Some((c, e)) => (c, e),
        None => (name, "jar"),
    };
    let p: Vec<&str> = coords.split(':').collect();
    let (group, artifact, version) = (p[0].replace('.', "/"), p[1], p[2]);
    match p.get(3) {
        Some(cls) => format!("{group}/{artifact}/{version}/{artifact}-{version}-{cls}.{ext}"),
        None => format!("{group}/{artifact}/{version}/{artifact}-{version}.{ext}"),
    }
}

/// Relative path of a library jar under libraries/, if it has one.
pub fn lib_rel_path(lib: &Library) -> Option<String> {
    if let Some(a) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
        return Some(a.path.clone().unwrap_or_else(|| maven_path(&lib.name)));
    }
    lib.url.as_ref().map(|_| maven_path(&lib.name))
}

pub fn lib_enabled(lib: &Library) -> bool {
    lib.rules.as_deref().map_or(true, rules_allow)
}

pub fn lib_job(dirs: &Dirs, lib: &Library) -> Option<Job> {
    if !lib_enabled(lib) {
        return None;
    }
    let rel = lib_rel_path(lib)?;
    let dest = dirs.libraries().join(&rel);
    if let Some(a) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
        return Some(Job { url: a.url.clone(), dest, sha1: a.sha1.clone() });
    }
    let base = lib.url.as_ref()?.trim_end_matches('/');
    Some(Job { url: format!("{base}/{rel}"), dest, sha1: None })
}

/// Downloads the client jar, libraries and assets for a vanilla version.
pub async fn install_vanilla(client: &reqwest::Client, dirs: &Dirs, id: &str) -> Result<()> {
    let manifest: Manifest = fetch_json(client, MANIFEST_URL).await?;
    let entry = manifest.versions.iter().find(|v| v.id == id)
        .with_context(|| format!("unknown Minecraft version {id}"))?;
    let text = fetch_text(client, &entry.url).await?;
    let v: VersionJson = serde_json::from_str(&text)?;

    let json_path = dirs.version_json(id);
    tokio::fs::create_dir_all(json_path.parent().unwrap()).await?;
    tokio::fs::write(&json_path, &text).await?;

    let mut jobs = Vec::new();
    let client_dl = v.downloads.as_ref().and_then(|d| d.client.as_ref())
        .context("version has no client download")?;
    jobs.push(Job { url: client_dl.url.clone(), dest: dirs.version_jar(id), sha1: client_dl.sha1.clone() });
    jobs.extend(v.libraries.iter().filter_map(|l| lib_job(dirs, l)));

    let ai = v.asset_index.as_ref().context("version has no asset index")?;
    let index_path = dirs.assets().join("indexes").join(format!("{}.json", ai.id));
    download_all(client, vec![Job { url: ai.url.clone(), dest: index_path.clone(), sha1: ai.sha1.clone() }]).await?;
    let index: AssetIndex = serde_json::from_slice(&tokio::fs::read(&index_path).await?)?;
    for obj in index.objects.values() {
        let prefix = &obj.hash[..2];
        jobs.push(Job {
            url: format!("{ASSET_URL}/{prefix}/{}", obj.hash),
            dest: dirs.assets().join("objects").join(prefix).join(&obj.hash),
            sha1: Some(obj.hash.clone()),
        });
    }

    println!("Downloading {} files for {id}...", jobs.len());
    download_all(client, jobs).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven_paths() {
        assert_eq!(
            maven_path("net.fabricmc:fabric-loader:0.16.9"),
            "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar"
        );
        assert_eq!(
            maven_path("org.lwjgl:lwjgl:3.3.3:natives-windows"),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"
        );
    }
}
