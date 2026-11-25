use crate::download::{download_all, sha1_hex, Job};
use crate::model::*;
use crate::rules::rules_allow;
use anyhow::{bail, Context, Result};
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
pub fn maven_path(name: &str) -> Option<String> {
    let (coords, ext) = match name.split_once('@') {
        Some((c, e)) => (c, e),
        None => (name, "jar"),
    };
    let p: Vec<&str> = coords.split(':').collect();
    if p.len() < 3 || p.iter().any(|s| s.is_empty()) {
        return None;
    }
    let (group, artifact, version) = (p[0].replace('.', "/"), p[1], p[2]);
    Some(match p.get(3) {
        Some(cls) => format!("{group}/{artifact}/{version}/{artifact}-{version}-{cls}.{ext}"),
        None => format!("{group}/{artifact}/{version}/{artifact}-{version}.{ext}"),
    })
}

/// Relative path of a library jar under libraries/, if it has one.
pub fn lib_rel_path(lib: &Library) -> Option<String> {
    if let Some(a) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
        return a.path.clone().or_else(|| maven_path(&lib.name));
    }
    lib.url.as_ref().and_then(|_| maven_path(&lib.name))
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
        return Some(Job { url: a.url.clone(), dest, sha1: a.sha1.clone(), size: a.size, content_addressed: false });
    }
    // Fabric-style library: Maven base URL plus a checksum at the top level (when Fabric provides one)
    let base = lib.url.as_ref()?.trim_end_matches('/');
    Some(Job { url: format!("{base}/{rel}"), dest, sha1: lib.sha1.clone(), size: lib.size, content_addressed: false })
}

/// Downloads the client jar, libraries and assets for a vanilla version.
pub async fn install_vanilla(client: &reqwest::Client, dirs: &Dirs, id: &str) -> Result<()> {
    let manifest: Manifest = fetch_json(client, MANIFEST_URL).await?;
    let entry = manifest.versions.iter().find(|v| v.id == id)
        .with_context(|| format!("unknown Minecraft version {id}"))?;
    let text = fetch_text(client, &entry.url).await?;
    if let Some(expected) = &entry.sha1 {
        if !sha1_hex(text.as_bytes()).eq_ignore_ascii_case(expected) {
            bail!("the version file for {id} was damaged in transit (checksum mismatch) - try again");
        }
    }
    let v: VersionJson = serde_json::from_str(&text)?;

    let json_path = dirs.version_json(id);
    tokio::fs::create_dir_all(json_path.parent().unwrap()).await?;
    tokio::fs::write(&json_path, &text).await?;

    let mut jobs = Vec::new();
    let client_dl = v.downloads.as_ref().and_then(|d| d.client.as_ref())
        .context("version has no client download")?;
    jobs.push(Job {
        url: client_dl.url.clone(),
        dest: dirs.version_jar(id),
        sha1: client_dl.sha1.clone(),
        size: client_dl.size,
        content_addressed: false,
    });
    jobs.extend(v.libraries.iter().filter_map(|l| lib_job(dirs, l)));

    let ai = v.asset_index.as_ref().context("version has no asset index")?;
    let index_path = dirs.assets().join("indexes").join(format!("{}.json", ai.id));
    download_all(client, vec![Job {
        url: ai.url.clone(),
        dest: index_path.clone(),
        sha1: ai.sha1.clone(),
        size: None,
        content_addressed: false,
    }]).await?;
    let index: AssetIndex = serde_json::from_slice(&tokio::fs::read(&index_path).await?)?;
    for obj in index.objects.values() {
        if obj.hash.len() != 40 || !obj.hash.bytes().all(|b| b.is_ascii_hexdigit()) {
            bail!("the asset index for {id} has a bad entry ({})", obj.hash);
        }
        let prefix = &obj.hash[..2];
        jobs.push(Job {
            url: format!("{ASSET_URL}/{prefix}/{}", obj.hash),
            dest: dirs.assets().join("objects").join(prefix).join(&obj.hash),
            sha1: Some(obj.hash.clone()),
            size: obj.size,
            content_addressed: true,
        });
    }

    println!("Checking {} files for {id}...", jobs.len());
    download_all(client, jobs).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maven_paths() {
        assert_eq!(
            maven_path("net.fabricmc:fabric-loader:0.16.9").unwrap(),
            "net/fabricmc/fabric-loader/0.16.9/fabric-loader-0.16.9.jar"
        );
        assert_eq!(
            maven_path("org.lwjgl:lwjgl:3.3.3:natives-windows").unwrap(),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"
        );
        assert_eq!(maven_path("not-a-coordinate"), None);
    }

    #[test]
    fn fabric_library_checksum_is_used() {
        let lib: Library = serde_json::from_str(
            r#"{"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net/","sha1":"ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236","size":126151}"#,
        ).unwrap();
        let job = lib_job(&Dirs { root: PathBuf::from("root") }, &lib).unwrap();
        assert_eq!(job.url, "https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar");
        assert_eq!(job.sha1.as_deref(), Some("ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236"));
        assert_eq!(job.size, Some(126151));
    }
}
