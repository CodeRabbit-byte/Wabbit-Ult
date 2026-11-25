//! Installs Minecraft 1.21.11 + Fabric + Mojang's Java runtime, following the
//! official version manifest: libraries (OS rules, x64 natives), client jar,
//! asset index and objects, logging config. Everything is SHA-1 checked, and
//! files that another launcher on this PC already downloaded (.minecraft,
//! .pikaclient) are copied instead of fetched again.

use crate::net::{self, Download};
use anyhow::{Context, Result, bail};
use serde_json::Value;
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub const MC_VERSION: &str = "1.21.11";
pub const FABRIC_LOADER: &str = "0.20.1";
const VERSION_MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const JAVA_RUNTIMES: &str =
	"https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
const ASSET_HOST: &str = "https://resources.download.minecraft.net";

/// Everything `launch` needs, all on disk.
pub struct LaunchPlan {
	pub java: PathBuf,
	pub main_class: String,
	pub classpath: Vec<PathBuf>,
	/// Argument templates with ${placeholders}, rules already applied.
	pub jvm_args: Vec<String>,
	pub game_args: Vec<String>,
	pub version_name: String,
	pub asset_index: String,
	pub natives_dir: PathBuf,
	/// Mojang's XML log layout argument. Kept for a future log viewer; `launch`
	/// leaves it out so the game writes plain-text logs.
	#[allow(dead_code)]
	pub logging_arg: Option<String>,
}

/// Other launchers' folders whose identical files can be copied instead of downloaded.
fn reuse_roots() -> Vec<PathBuf> {
	let appdata = crate::config::appdata();
	vec![appdata.join(".minecraft"), appdata.join(".pikaclient")]
}

/// Does a Mojang rules list allow this machine (Windows, x86_64, no special features)?
fn rules_allow(rules: Option<&Value>) -> bool {
	let Some(rules) = rules.and_then(Value::as_array) else { return true };
	let mut allowed = false;
	for rule in rules {
		let mut matches = true;
		if let Some(os) = rule.get("os") {
			if let Some(name) = os.get("name").and_then(Value::as_str) {
				matches &= name == "windows";
			}
			if let Some(arch) = os.get("arch").and_then(Value::as_str) {
				matches &= arch == "x86_64" || arch == "x64";
			}
		}
		// Feature-gated arguments (demo, custom resolution, quick play) are all off.
		if let Some(features) = rule.get("features").and_then(Value::as_object) {
			matches &= features.values().all(|v| v == &Value::Bool(false));
		}
		if matches {
			allowed = rule.get("action").and_then(Value::as_str) == Some("allow");
		}
	}
	allowed
}

/// Flatten a version's "arguments.game"/"arguments.jvm" list, applying rules.
fn collect_args(list: Option<&Value>, out: &mut Vec<String>) {
	for arg in list.and_then(Value::as_array).into_iter().flatten() {
		match arg {
			Value::String(s) => out.push(s.clone()),
			Value::Object(o) if rules_allow(o.get("rules")) => match o.get("value") {
				Some(Value::String(s)) => out.push(s.clone()),
				Some(Value::Array(a)) => out.extend(a.iter().filter_map(|v| v.as_str().map(String::from))),
				_ => {}
			},
			_ => {}
		}
	}
}

/// Maven coordinate → relative path ("g.r.p:art:ver[:classifier]").
fn maven_path(name: &str) -> Option<String> {
	let parts: Vec<&str> = name.split(':').collect();
	if parts.len() < 3 {
		return None;
	}
	let (group, artifact, version) = (parts[0].replace('.', "/"), parts[1], parts[2]);
	let classifier = parts.get(3).map(|c| format!("-{c}")).unwrap_or_default();
	Some(format!("{group}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"))
}

/// "group:artifact[:classifier]" — two versions of one library must not both load.
fn lib_key(name: &str) -> String {
	let p: Vec<&str> = name.split(':').collect();
	match p.len() {
		0..=2 => name.to_string(),
		3 => format!("{}:{}", p[0], p[1]),
		_ => format!("{}:{}:{}", p[0], p[1], p[3]),
	}
}

fn read_or_fetch_json(path: &Path, url: &str) -> Result<Value> {
	if let Some(v) = std::fs::read(path).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) {
		return Ok(v);
	}
	let v: Value = net::get_json(url)?;
	if let Some(dir) = path.parent() {
		std::fs::create_dir_all(dir)?;
	}
	std::fs::write(path, serde_json::to_vec(&v)?)?;
	Ok(v)
}

/// Make sure the game, Fabric and Java are installed; return how to start them.
pub fn prepare(root: &Path, progress: &(dyn Fn(&str, f32) + Sync)) -> Result<LaunchPlan> {
	progress("Checking Minecraft versions…", 0.0);
	let versions = root.join("versions");

	// --- vanilla version JSON
	let vanilla_path = versions.join(MC_VERSION).join(format!("{MC_VERSION}.json"));
	let vanilla = match std::fs::read(&vanilla_path).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) {
		Some(v) => v,
		None => {
			let manifest: Value = net::get_json(VERSION_MANIFEST)?;
			let url = manifest["versions"]
				.as_array()
				.and_then(|vs| vs.iter().find(|v| v["id"] == MC_VERSION))
				.and_then(|v| v["url"].as_str())
				.with_context(|| format!("Minecraft {MC_VERSION} is not in Mojang's manifest"))?
				.to_string();
			read_or_fetch_json(&vanilla_path, &url)?
		}
	};

	// --- Fabric profile (inherits from vanilla)
	let fabric_id = format!("fabric-loader-{FABRIC_LOADER}-{MC_VERSION}");
	let fabric = read_or_fetch_json(
		&versions.join(&fabric_id).join(format!("{fabric_id}.json")),
		&format!("{FABRIC_META}/versions/loader/{MC_VERSION}/{FABRIC_LOADER}/profile/json"),
	)?;

	let mut downloads: Vec<Download> = Vec::new();
	let reuse = reuse_roots();
	let mut classpath: Vec<PathBuf> = Vec::new();
	let mut seen: HashSet<String> = HashSet::new();
	let libs = root.join("libraries");

	// --- Fabric libraries first: on a clash (e.g. ASM) Fabric's version wins.
	for lib in fabric["libraries"].as_array().into_iter().flatten() {
		let Some(name) = lib["name"].as_str() else { continue };
		let Some(rel) = maven_path(name) else { continue };
		if !seen.insert(lib_key(name)) {
			continue;
		}
		let base = lib["url"].as_str().unwrap_or("https://maven.fabricmc.net/");
		let dest = libs.join(&rel);
		let mut d = Download::new(format!("{}{rel}", base.trim_end_matches('/').to_string() + "/"), dest.clone(),
			lib["sha1"].as_str().map(String::from), lib["size"].as_u64());
		d.reuse = reuse.iter().map(|r| r.join("libraries").join(&rel)).collect();
		downloads.push(d);
		classpath.push(dest);
	}

	// --- vanilla libraries (OS rules; only the x64 build of each Windows native)
	for lib in vanilla["libraries"].as_array().into_iter().flatten() {
		let Some(name) = lib["name"].as_str() else { continue };
		if !rules_allow(lib.get("rules")) {
			continue;
		}
		if let Some(classifier) = name.split(':').nth(3) {
			if classifier.starts_with("natives-windows") && classifier != "natives-windows" {
				continue; // natives-windows-arm64 / -x86
			}
		}
		let Some(art) = lib["downloads"].get("artifact") else { continue };
		if !seen.insert(lib_key(name)) {
			continue;
		}
		let rel = art["path"].as_str().context("library without path")?.to_string();
		let dest = libs.join(&rel);
		let mut d = Download::new(art["url"].as_str().unwrap_or_default(), dest.clone(),
			art["sha1"].as_str().map(String::from), art["size"].as_u64());
		d.reuse = reuse.iter().map(|r| r.join("libraries").join(&rel)).collect();
		downloads.push(d);
		classpath.push(dest);
	}

	// --- client jar
	let client = &vanilla["downloads"]["client"];
	let client_jar = versions.join(MC_VERSION).join(format!("{MC_VERSION}.jar"));
	let mut d = Download::new(client["url"].as_str().context("no client download")?, client_jar.clone(),
		client["sha1"].as_str().map(String::from), client["size"].as_u64());
	d.reuse = reuse.iter().map(|r| r.join("versions").join(MC_VERSION).join(format!("{MC_VERSION}.jar"))).collect();
	downloads.push(d);
	classpath.push(client_jar);

	// --- logging config
	let mut logging_arg = None;
	if let Some(log) = vanilla["logging"]["client"].as_object() {
		let file = &log["file"];
		if let (Some(id), Some(url)) = (file["id"].as_str(), file["url"].as_str()) {
			let dest = root.join("assets").join("log_configs").join(id);
			downloads.push(Download::new(url, dest.clone(), file["sha1"].as_str().map(String::from), file["size"].as_u64()));
			if let Some(arg) = log.get("argument").and_then(Value::as_str) {
				logging_arg = Some(arg.replace("${path}", &dest.to_string_lossy()));
			}
		}
	}

	// --- assets
	progress("Checking game assets…", 0.02);
	let index = &vanilla["assetIndex"];
	let asset_index = index["id"].as_str().context("no asset index")?.to_string();
	let index_path = root.join("assets").join("indexes").join(format!("{asset_index}.json"));
	let index_json = {
		let ok = net::file_ok(&index_path, index["sha1"].as_str(), None);
		if !ok {
			let bytes = net::get_bytes(index["url"].as_str().context("no asset index url")?)?;
			std::fs::create_dir_all(index_path.parent().unwrap())?;
			std::fs::write(&index_path, &bytes)?;
		}
		serde_json::from_slice::<Value>(&std::fs::read(&index_path)?)?
	};
	let mut hashes = HashSet::new();
	for obj in index_json["objects"].as_object().into_iter().flatten().map(|(_, v)| v) {
		let (Some(hash), size) = (obj["hash"].as_str(), obj["size"].as_u64()) else { continue };
		if !hashes.insert(hash.to_string()) {
			continue;
		}
		let rel = format!("{}/{hash}", &hash[..2]);
		let mut d = Download::new(format!("{ASSET_HOST}/{rel}"), root.join("assets").join("objects").join(&rel),
			Some(hash.to_string()), size);
		d.reuse = reuse.iter().map(|r| r.join("assets").join("objects").join(&rel)).collect();
		downloads.push(d);
	}

	// --- Java runtime from Mojang (the version this release asks for)
	let component = vanilla["javaVersion"]["component"].as_str().unwrap_or("java-runtime-delta").to_string();
	let runtime_dir = root.join("runtime").join(&component);
	let java = runtime_dir.join("bin").join("javaw.exe");
	if !java.exists() || !runtime_dir.join(".installed").exists() {
		progress("Getting Java…", 0.03);
		let all: Value = net::get_json(JAVA_RUNTIMES)?;
		let manifest_url = all["windows-x64"][&component][0]["manifest"]["url"]
			.as_str()
			.with_context(|| format!("no {component} runtime for windows-x64"))?
			.to_string();
		let manifest: Value = net::get_json(&manifest_url)?;
		let mut rt = Vec::new();
		for (path, entry) in manifest["files"].as_object().into_iter().flatten() {
			match entry["type"].as_str() {
				Some("directory") => std::fs::create_dir_all(runtime_dir.join(path))?,
				Some("file") => {
					let raw = &entry["downloads"]["raw"];
					rt.push(Download::new(raw["url"].as_str().unwrap_or_default(), runtime_dir.join(path),
						raw["sha1"].as_str().map(String::from), raw["size"].as_u64()));
				}
				_ => {}
			}
		}
		let n = rt.len();
		net::download_all(rt, 8, &|done, total| progress(&format!("Getting Java… {done}/{total}"), 0.03 + 0.12 * done as f32 / total as f32))?;
		if n > 0 {
			std::fs::write(runtime_dir.join(".installed"), b"ok")?;
		}
	}
	if !java.exists() {
		bail!("Java runtime is incomplete: {} is missing", java.display());
	}

	// --- fetch everything (skips files already present and correct)
	let total = downloads.len();
	net::download_all(downloads, 12, &|done, all| {
		progress(&format!("Installing Minecraft {MC_VERSION}… {done}/{all}"), 0.15 + 0.80 * done as f32 / all as f32)
	})
	.with_context(|| format!("installing {total} game files"))?;

	// --- arguments: vanilla then Fabric's additions
	let mut jvm_args = Vec::new();
	collect_args(vanilla["arguments"].get("jvm"), &mut jvm_args);
	collect_args(fabric["arguments"].get("jvm"), &mut jvm_args);
	let mut game_args = Vec::new();
	collect_args(vanilla["arguments"].get("game"), &mut game_args);
	collect_args(fabric["arguments"].get("game"), &mut game_args);

	let natives_dir = versions.join(&fabric_id).join("natives");
	std::fs::create_dir_all(&natives_dir)?;
	progress("Minecraft is ready.", 0.95);
	Ok(LaunchPlan {
		java,
		main_class: fabric["mainClass"].as_str().unwrap_or("net.fabricmc.loader.impl.launch.knot.KnotClient").to_string(),
		classpath,
		jvm_args,
		game_args,
		version_name: fabric_id,
		asset_index,
		natives_dir,
		logging_arg,
	})
}
