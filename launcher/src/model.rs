use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Deserialize)]
pub struct Manifest {
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Deserialize)]
pub struct ManifestEntry {
    pub id: String,
    pub url: String,
    /// SHA-1 of the version JSON (present in version_manifest_v2).
    pub sha1: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    pub inherits_from: Option<String>,
    #[serde(default)]
    pub main_class: String,
    pub arguments: Option<Arguments>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    pub downloads: Option<VersionDownloads>,
    pub asset_index: Option<AssetIndexRef>,
    pub assets: Option<String>,
    pub java_version: Option<JavaVersion>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    pub major_version: u32,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Arg>,
    #[serde(default)]
    pub jvm: Vec<Arg>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum Arg {
    Plain(String),
    Ruled { rules: Vec<Rule>, value: OneOrMany },
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum OneOrMany {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone, Deserialize)]
pub struct Rule {
    pub action: String,
    pub os: Option<OsRule>,
    pub features: Option<HashMap<String, bool>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Library {
    pub name: String,
    pub downloads: Option<LibDownloads>,
    pub rules: Option<Vec<Rule>>,
    /// Maven base URL (Fabric-style libraries have this instead of `downloads`).
    pub url: Option<String>,
    /// Fabric-style libraries carry their checksum and size at the top level.
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LibDownloads {
    pub artifact: Option<FileDownload>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct FileDownload {
    pub path: Option<String>,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionDownloads {
    pub client: Option<FileDownload>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndexRef {
    pub id: String,
    pub url: String,
    pub sha1: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
}

#[derive(Debug, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: Option<u64>,
}

#[derive(Debug, Deserialize)]
pub struct LoaderEntry {
    pub loader: LoaderInfo,
}

#[derive(Debug, Deserialize)]
pub struct LoaderInfo {
    pub version: String,
    pub stable: bool,
}
