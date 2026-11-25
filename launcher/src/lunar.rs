//! Import a player's Lunar Client setup into Wabbit: game settings and
//! keybinds, the server list, Fabric mods, resource packs and shader packs.
//!
//! Lunar is only ever read, never changed. Everything is copied into Wabbit's
//! game folder, and anything Wabbit overwrites (options.txt, servers.dat, mod
//! configs) is backed up first.
//!
//! Lunar keeps one folder per profile under `%USERPROFILE%\.lunarclient\profiles`:
//! - version profiles ("1.21", "1.8", ...) keep Fabric mods per game version in
//!   `mods/fabric-<mc version>/`, and share resource packs, shader packs and mod
//!   configs with Lunar's game directory (`.minecraft` unless changed);
//! - modpack profiles ("fabulously-optimized", ...) keep everything inside the
//!   profile folder (`mods/`, `mods/dependencies/`, `resourcepacks/`, `shaderpacks/`).
//!
//! Mods built for another Minecraft version are swapped for their 1.21.11 build
//! from Modrinth when one exists, and skipped otherwise. Before every Play,
//! [`validate`] switches off imported mods whose dependencies are missing, so
//! one bad mod can't stop the game from starting.

use crate::install::{FABRIC_LOADER, MC_VERSION};
use crate::net::{self, Download};
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};

const API: &str = "https://api.modrinth.com/v2";

// ---------------------------------------------------------------- discovery ----

/// `%USERPROFILE%\.lunarclient`, when Lunar Client is installed.
pub fn root() -> Option<PathBuf> {
	let home = std::env::var_os("USERPROFILE").map(PathBuf::from)?;
	let root = home.join(".lunarclient");
	root.join("profiles").is_dir().then_some(root)
}

/// The game directory set in Lunar's launcher (packs, shaders and mod configs
/// of version profiles live here).
fn game_directory(root: &Path) -> PathBuf {
	std::fs::read(root.join("settings").join("launcher.json"))
		.ok()
		.and_then(|b| serde_json::from_slice::<Value>(&b).ok())
		.and_then(|v| v["settings"]["gameDirectory"].as_str().map(PathBuf::from))
		.filter(|p| p.is_dir())
		.unwrap_or_else(|| crate::config::appdata().join(".minecraft"))
}

/// The Minecraft version the player launched last in Lunar, e.g. "1.21.4".
fn last_played(root: &Path) -> Option<String> {
	let cache: Value = serde_json::from_slice(&std::fs::read(root.join("settings").join("cache.json")).ok()?).ok()?;
	let mut best: Option<(u64, String)> = None;
	for list in cache["profileSelectHistory"].as_object()?.values() {
		for e in list.as_array().into_iter().flatten() {
			if let (Some(t), Some(v)) = (e["time"].as_u64(), e["version"].as_str()) {
				if best.as_ref().is_none_or(|(bt, _)| t > *bt) {
					best = Some((t, v.to_string()));
				}
			}
		}
	}
	best.map(|(_, v)| v)
}

/// One Lunar profile and everything that can be imported from it.
#[derive(Clone)]
pub struct Profile {
	pub name: String,
	pub dir: PathBuf,
	/// "Fabric 1.21.4" for a version profile, "modpack" for a modpack profile.
	pub mods_from: Option<String>,
	pub mod_jars: Vec<PathBuf>,
	pub packs: Vec<PathBuf>,
	pub shaders: Vec<PathBuf>,
	pub config_dir: Option<PathBuf>,
	pub options: bool,
	pub servers: bool,
	/// The profile Lunar launched last.
	pub recent: bool,
}

impl Profile {
	pub fn label(&self) -> String {
		let pretty = if self.name.chars().next().is_some_and(|c| c.is_ascii_digit()) {
			format!("Lunar {}", self.name)
		} else {
			let s = self.name.replace(['-', '_'], " ");
			let mut c = s.chars();
			c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
		};
		if self.recent { format!("{pretty}  (last played)") } else { pretty }
	}

	pub fn summary(&self) -> String {
		let mods = match &self.mods_from {
			Some(from) if from != "modpack" => format!("{} mods (from {from})", self.mod_jars.len()),
			_ => format!("{} mods", self.mod_jars.len()),
		};
		format!("{mods} · {} resource packs · {} shader packs", self.packs.len(), self.shaders.len())
	}
}

fn entries(dir: &Path) -> Vec<PathBuf> {
	let mut v: Vec<PathBuf> = std::fs::read_dir(dir).into_iter().flatten().flatten().map(|e| e.path()).collect();
	v.sort();
	v
}

fn jars(dir: &Path) -> Vec<PathBuf> {
	entries(dir).into_iter().filter(|p| p.is_file() && p.extension().is_some_and(|e| e.eq_ignore_ascii_case("jar"))).collect()
}

/// Packs are zip files or folders; anything else in the folder is ignored.
fn packs_in(dirs: &[PathBuf]) -> Vec<PathBuf> {
	let mut seen = HashSet::new();
	let mut out = Vec::new();
	for d in dirs {
		for p in entries(d) {
			let is_pack = p.is_dir() || p.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip"));
			let name = p.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
			if is_pack && seen.insert(name) {
				out.push(p);
			}
		}
	}
	out
}

/// "fabric-1.21.4" -> [1, 21, 4], for picking the newest folder.
fn version_key(v: &str) -> Vec<u64> {
	v.split(['.', '-']).filter_map(|s| s.parse().ok()).collect()
}

/// Every Lunar profile with something worth importing, best guess first.
pub fn profiles() -> Vec<Profile> {
	let Some(root) = root() else { return Vec::new() };
	let game_dir = game_directory(&root);
	let last = last_played(&root);
	let mut out = Vec::new();
	for dir in entries(&root.join("profiles")) {
		if !dir.is_dir() {
			continue;
		}
		let name = dir.file_name().unwrap_or_default().to_string_lossy().to_string();
		let mods = dir.join("mods");
		let modpack = dir.join("versions").is_dir() || !jars(&mods).is_empty();
		let (mods_from, mod_jars, pack_dirs, shader_dirs, config_dir) = if modpack {
			// `mods/dependencies` holds Lunar's unpacked copies of libraries the
			// mods already carry inside their jars, so it's left out.
			let j = jars(&mods);
			let packs = vec![dir.join("resourcepacks")];
			let shaders = vec![dir.join("shaderpacks"), dir.join("shaders")];
			let config = dir.join("config");
			(Some("modpack".to_string()), j, packs, shaders, config.is_dir().then_some(config))
		} else {
			// One mods folder per game version: 1.21.11 itself if present, else the
			// version Lunar launched last, else the newest one that has mods.
			let mut folders: Vec<(String, PathBuf)> = entries(&mods)
				.into_iter()
				.filter(|p| p.is_dir())
				.filter_map(|p| {
					let n = p.file_name()?.to_string_lossy().to_string();
					let v = n.strip_prefix("fabric-")?.to_string();
					(!jars(&p).is_empty()).then_some((v, p))
				})
				.collect();
			folders.sort_by_key(|(v, _)| version_key(v));
			let pick = folders
				.iter()
				.find(|(v, _)| v == MC_VERSION)
				.or_else(|| folders.iter().find(|(v, _)| Some(v) == last.as_ref()))
				.or(folders.last());
			let (from, j) = match pick {
				Some((v, p)) => (Some(format!("Fabric {v}")), jars(p)),
				None => (None, Vec::new()),
			};
			let packs = vec![dir.join("resourcepacks"), game_dir.join("resourcepacks")];
			let shaders = vec![dir.join("shaders"), dir.join("shaderpacks"), game_dir.join("shaderpacks")];
			let config = game_dir.join("config");
			(from, j, packs, shaders, config.is_dir().then_some(config))
		};
		let p = Profile {
			recent: !modpack && last.as_deref().is_some_and(|v| v == name || v.starts_with(&format!("{name}."))),
			name,
			mods_from,
			mod_jars,
			packs: packs_in(&pack_dirs),
			shaders: packs_in(&shader_dirs),
			config_dir,
			options: dir.join("options.txt").is_file(),
			servers: dir.join("servers.dat").is_file(),
			dir,
		};
		if p.options || p.servers || !p.mod_jars.is_empty() || !p.packs.is_empty() {
			out.push(p);
		}
	}
	// Last played first, then the ones with the most mods.
	out.sort_by_key(|p| (!p.recent, std::cmp::Reverse(p.mod_jars.len())));
	out
}

// ------------------------------------------------------------- fabric jars ----

/// What a Fabric mod jar says about itself (`fabric.mod.json`).
#[derive(Clone, Default)]
pub struct Meta {
	pub id: String,
	pub name: String,
	pub version: String,
	depends: Vec<(String, Vec<String>)>,
	breaks: Vec<(String, Vec<String>)>,
	/// Ids this jar makes available: its own, `provides`, and nested jars'.
	all_ids: Vec<(String, String)>,
}

fn preds(v: &Value) -> Vec<String> {
	match v {
		Value::String(s) => vec![s.clone()],
		Value::Array(a) => a.iter().filter_map(|x| x.as_str().map(String::from)).collect(),
		_ => vec!["*".into()],
	}
}

fn meta_from_zip<R: Read + std::io::Seek>(zip: &mut zip::ZipArchive<R>, depth: u8) -> Option<Meta> {
	let mut text = String::new();
	zip.by_name("fabric.mod.json").ok()?.read_to_string(&mut text).ok()?;
	// Some mods put raw newlines/tabs inside strings, which strict JSON rejects.
	let text: String = text.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
	let j: Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
	let id = j["id"].as_str()?.to_string();
	let version = j["version"].as_str().unwrap_or("0").to_string();
	let list = |key: &str| -> Vec<(String, Vec<String>)> {
		j[key].as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), preds(v))).collect()).unwrap_or_default()
	};
	let mut all_ids = vec![(id.clone(), version.clone())];
	for p in j["provides"].as_array().into_iter().flatten().filter_map(|p| p.as_str()) {
		all_ids.push((p.to_string(), version.clone()));
	}
	if depth < 3 {
		let nested: Vec<String> = j["jars"].as_array().into_iter().flatten().filter_map(|e| e["file"].as_str().map(String::from)).collect();
		for file in nested {
			let mut bytes = Vec::new();
			if zip.by_name(&file).ok().and_then(|mut f| f.read_to_end(&mut bytes).ok()).is_none() {
				continue;
			}
			if let Some(m) = zip::ZipArchive::new(std::io::Cursor::new(bytes)).ok().and_then(|mut z| meta_from_zip(&mut z, depth + 1)) {
				all_ids.extend(m.all_ids);
			}
		}
	}
	Some(Meta {
		name: j["name"].as_str().unwrap_or(&id).to_string(),
		id,
		version,
		depends: list("depends"),
		breaks: list("breaks"),
		all_ids,
	})
}

/// `None` when the file isn't a Fabric mod.
pub fn read_meta(path: &Path) -> Option<Meta> {
	let f = std::fs::File::open(path).ok()?;
	meta_from_zip(&mut zip::ZipArchive::new(std::io::BufReader::new(f)).ok()?, 0)
}

// --------------------------------------------------------- version ranges ----

/// "1.21.4", "0.6.13+mc1.21.11", "1.2.0-beta.3" -> ([1,2,0], prerelease?).
fn parse_version(v: &str) -> Option<(Vec<u64>, bool)> {
	let v = v.trim().trim_start_matches(['v', 'V']);
	let v = v.split('+').next()?;
	let (core, pre) = match v.split_once('-') {
		Some((c, p)) => (c, !p.is_empty()),
		None => (v, false),
	};
	let nums: Option<Vec<u64>> = core.split('.').map(|s| s.parse().ok()).collect();
	nums.filter(|n| !n.is_empty()).map(|n| (n, pre))
}

fn cmp_versions(a: &(Vec<u64>, bool), b: &(Vec<u64>, bool)) -> std::cmp::Ordering {
	let len = a.0.len().max(b.0.len());
	for i in 0..len {
		let (x, y) = (a.0.get(i).copied().unwrap_or(0), b.0.get(i).copied().unwrap_or(0));
		if x != y {
			return x.cmp(&y);
		}
	}
	// 1.2.0-beta < 1.2.0
	b.1.cmp(&a.1)
}

/// Fabric's version predicates: `*`, `1.21.4`, `>=1.21`, `<1.22`, `~1.21.4`,
/// `^1.2`, `1.21.x`, space-separated (all must hold), `||` (any). A version or
/// predicate that can't be parsed counts as a match, so odd version strings
/// never block a mod on their own.
pub fn matches(pred: &str, version: &str) -> bool {
	let Some(have) = parse_version(version) else { return true };
	pred.split("||").any(|alt| {
		alt.split_whitespace().all(|term| {
			let term = term.trim();
			if term.is_empty() || term == "*" {
				return true;
			}
			let (op, rest) = ["<=", ">=", "<", ">", "=", "~", "^"]
				.iter()
				.find_map(|op| term.strip_prefix(op).map(|r| (*op, r.trim())))
				.unwrap_or(("", term));
			let rest = rest.trim_end_matches('-');
			// x-ranges: 1.21.x / 1.21.*
			if let Some(prefix) = rest.split('.').position(|s| s == "x" || s == "X" || s == "*") {
				let want: Option<Vec<u64>> = rest.split('.').take(prefix).map(|s| s.parse().ok()).collect();
				let Some(want) = want else { return true };
				return match op {
					"" | "=" | "~" | "^" => have.0.iter().zip(&want).all(|(a, b)| a == b) && have.0.len() >= want.len(),
					_ => {
						let w = (want, false);
						let o = cmp_versions(&have, &w);
						match op {
							">=" => o.is_ge(),
							">" => o.is_gt(),
							"<" => o.is_lt(),
							_ => o.is_le() || have.0.starts_with(&w.0),
						}
					}
				};
			}
			let Some(want) = parse_version(rest) else { return true };
			let o = cmp_versions(&have, &want);
			match op {
				">=" => o.is_ge(),
				">" => o.is_gt(),
				"<=" => o.is_le(),
				"<" => o.is_lt(),
				"~" | "^" => {
					let mut upper = want.0.clone();
					if op == "~" && upper.len() >= 2 {
						upper.truncate(2);
						upper[1] += 1;
					} else if op == "^" && upper[0] == 0 && upper.len() >= 2 {
						upper.truncate(2);
						upper[1] += 1;
					} else {
						upper.truncate(1);
						upper[0] += 1;
					}
					// A lower bound of 1.21.4 also accepts 1.21.4-rc builds' release.
					o.is_ge() && cmp_versions(&have, &(upper, true)).is_lt()
				}
				_ => o.is_eq(),
			}
		})
	})
}

/// True when a jar's `depends.minecraft` allows 1.21.11 (or doesn't say).
fn runs_on_target(m: &Meta) -> bool {
	m.depends.iter().filter(|(k, _)| k == "minecraft").all(|(_, p)| p.iter().any(|p| matches(p, MC_VERSION)))
}

// ------------------------------------------------------------------ record ----

/// One mod Wabbit imported. Switched off = the file was renamed to `<file>.disabled`.
#[derive(Clone, Serialize, Deserialize)]
pub struct Imported {
	pub file: String,
	pub id: String,
	pub name: String,
	pub version: String,
	/// "copied", "updated" (swapped for its 1.21.11 build) or "added" (a dependency or Iris).
	pub how: String,
	/// Why [`validate`] switched it off, if it did.
	#[serde(default)]
	pub problem: Option<String>,
}

fn record_path(game_dir: &Path) -> PathBuf {
	game_dir.join("mods").join(".wabbit-imported.json")
}

pub fn imported(game_dir: &Path) -> Vec<Imported> {
	let mut list: Vec<Imported> =
		std::fs::read(record_path(game_dir)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
	let mods = game_dir.join("mods");
	list.retain(|m| mods.join(&m.file).exists() || mods.join(format!("{}.disabled", m.file)).exists());
	list
}

fn save_record(game_dir: &Path, list: &[Imported]) {
	let _ = std::fs::write(record_path(game_dir), serde_json::to_vec_pretty(list).unwrap_or_default());
}

pub fn is_enabled(game_dir: &Path, m: &Imported) -> bool {
	game_dir.join("mods").join(&m.file).exists()
}

/// Switch an imported mod on or off (renames `<file>` <-> `<file>.disabled`).
pub fn set_enabled(game_dir: &Path, file: &str, on: bool) {
	let mods = game_dir.join("mods");
	let (jar, off) = (mods.join(file), mods.join(format!("{file}.disabled")));
	let _ = if on { std::fs::rename(&off, &jar) } else { std::fs::rename(&jar, &off) };
	let mut list = imported(game_dir);
	if let Some(m) = list.iter_mut().find(|m| m.file == file) {
		m.problem = None;
	}
	save_record(game_dir, &list);
}

// ------------------------------------------------------------------ import ----

#[derive(Clone, Copy)]
pub struct Choices {
	pub settings: bool,
	pub servers: bool,
	pub mods: bool,
	pub packs: bool,
	pub shaders: bool,
}

impl Default for Choices {
	fn default() -> Self {
		Self { settings: true, servers: true, mods: true, packs: true, shaders: true }
	}
}

pub struct Report {
	pub lines: Vec<String>,
	pub skipped: Vec<String>,
}

/// Overwritten files go to `<game dir>\backups\lunar-import-<time>\`.
struct Backup {
	dir: PathBuf,
}

impl Backup {
	fn new(game_dir: &Path) -> Self {
		Self { dir: game_dir.join("backups").join(format!("lunar-import-{}", chrono::Local::now().format("%Y%m%d-%H%M%S"))) }
	}
	/// Move `path` (relative name `rel`) into the backup, if it exists.
	fn save(&self, path: &Path, rel: &Path) {
		if path.exists() {
			let dest = self.dir.join(rel);
			if let Some(d) = dest.parent() {
				let _ = std::fs::create_dir_all(d);
			}
			if path.is_dir() {
				let _ = copy_dir(path, &dest);
			} else {
				let _ = std::fs::copy(path, &dest);
			}
		}
	}
}

fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
	std::fs::create_dir_all(to)?;
	for e in std::fs::read_dir(from)? {
		let e = e?;
		let dest = to.join(e.file_name());
		if e.file_type()?.is_dir() {
			copy_dir(&e.path(), &dest)?;
		} else {
			std::fs::copy(e.path(), dest)?;
		}
	}
	Ok(())
}

/// Copy packs (zip or folder) into `dest`, skipping ones already there with the same size.
fn copy_packs(list: &[PathBuf], dest: &Path, progress: &dyn Fn(usize)) -> Result<usize> {
	std::fs::create_dir_all(dest)?;
	let mut n = 0;
	for (i, p) in list.iter().enumerate() {
		let target = dest.join(p.file_name().unwrap_or_default());
		let same = p.is_file() && std::fs::metadata(&target).ok().map(|m| m.len()) == std::fs::metadata(p).ok().map(|m| m.len());
		if !same {
			if p.is_dir() {
				copy_dir(p, &target)?;
			} else {
				std::fs::copy(p, &target).with_context(|| format!("copy {}", p.display()))?;
			}
		}
		n += 1;
		progress(i + 1);
	}
	Ok(n)
}

fn post_json(url: &str, body: Value) -> Result<Value> {
	let resp = net::agent().post(url).send_json(body).with_context(|| format!("POST {url}"))?;
	Ok(resp.into_json()?)
}

fn urlenc(s: &str) -> String {
	s.bytes()
		.map(|b| match b {
			b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
			_ => format!("%{b:02X}"),
		})
		.collect()
}

/// The primary file of a Modrinth version: (file name, url, sha1).
fn primary_file(v: &Value) -> Option<(String, String, Option<String>)> {
	let files = v["files"].as_array()?;
	let f = files.iter().find(|f| f["primary"] == true).or(files.first())?;
	let url = f["url"].as_str()?;
	if !url.starts_with("https://cdn.modrinth.com/") {
		return None;
	}
	Some((f["filename"].as_str()?.to_string(), url.to_string(), f["hashes"]["sha1"].as_str().map(String::from)))
}

/// The newest 1.21.11 Fabric build of a Modrinth project (slug or id), and its project id.
fn latest_for_target(project: &str) -> Option<(String, Value)> {
	let url = format!(
		"{API}/project/{}/version?game_versions={}&loaders={}",
		urlenc(project),
		urlenc(&format!("[\"{MC_VERSION}\"]")),
		urlenc("[\"fabric\"]")
	);
	let list: Value = net::get_json(&url).ok()?;
	let list = list.as_array()?;
	let v = list.iter().find(|v| v["version_type"] == "release").or(list.first())?;
	Some((v["project_id"].as_str()?.to_string(), v.clone()))
}

/// Modrinth project ids of the mods Wabbit installs itself (and Iris).
fn managed_project_ids() -> Result<HashMap<String, String>> {
	let ids: Vec<String> = crate::mods::MODS.iter().map(|(s, _)| format!("\"{s}\"")).collect();
	let url = format!("{API}/projects?ids={}", urlenc(&format!("[{}]", ids.join(","))));
	let list: Value = net::get_json(&url)?;
	Ok(list
		.as_array()
		.into_iter()
		.flatten()
		.filter_map(|p| Some((p["id"].as_str()?.to_string(), p["slug"].as_str()?.to_string())))
		.collect())
}

/// Ids every mod can rely on without a jar: the game, the loader, Java.
fn builtins() -> Vec<(&'static str, &'static str)> {
	vec![("minecraft", MC_VERSION), ("fabricloader", FABRIC_LOADER), ("fabric-loader", FABRIC_LOADER), ("java", "21"), ("mixinextras", "0.5.0")]
}

pub fn import(p: &Profile, c: Choices, game_dir: &Path, progress: &(dyn Fn(&str, f32) + Sync)) -> Result<Report> {
	std::fs::create_dir_all(game_dir)?;
	let backup = Backup::new(game_dir);
	let mut lines = Vec::new();
	let mut skipped = Vec::new();

	if c.settings && p.options {
		let dest = game_dir.join("options.txt");
		backup.save(&dest, Path::new("options.txt"));
		std::fs::copy(p.dir.join("options.txt"), &dest)?;
		lines.push("Game settings and keybinds".to_string());
	}
	if c.servers && p.servers {
		let dest = game_dir.join("servers.dat");
		backup.save(&dest, Path::new("servers.dat"));
		std::fs::copy(p.dir.join("servers.dat"), &dest)?;
		lines.push("Server list".to_string());
	}
	if c.packs && !p.packs.is_empty() {
		let total = p.packs.len();
		let n = copy_packs(&p.packs, &game_dir.join("resourcepacks"), &|i| {
			progress(&format!("Resource packs {i}/{total}…"), 0.05 + 0.25 * i as f32 / total as f32)
		})?;
		lines.push(format!("{n} resource packs"));
	}
	let mut shaders_done = 0;
	if c.shaders && !p.shaders.is_empty() {
		let total = p.shaders.len();
		shaders_done = copy_packs(&p.shaders, &game_dir.join("shaderpacks"), &|i| {
			progress(&format!("Shader packs {i}/{total}…"), 0.30 + 0.10 * i as f32 / total as f32)
		})?;
		lines.push(format!("{shaders_done} shader packs"));
	}

	if c.mods && (!p.mod_jars.is_empty() || shaders_done > 0) {
		progress("Checking mods…", 0.42);
		let (n, mut skip, notes) = import_mods(p, game_dir, shaders_done > 0, &backup, progress)?;
		if n > 0 {
			lines.push(format!("{n} mods"));
		}
		lines.extend(notes);
		skipped.append(&mut skip);
	}

	// Turn the shader pack Lunar had selected on in Iris.
	if c.shaders && shaders_done > 0 {
		let selected = std::fs::read_to_string(p.dir.join("optionsshaders.txt")).ok().and_then(|t| {
			t.lines().find_map(|l| l.strip_prefix("shaderPack").map(|r| r.trim_start_matches([':', '=']).trim().to_string()))
		});
		if let Some(pack) = selected.filter(|s| !s.is_empty() && s != "OFF" && game_dir.join("shaderpacks").join(s).exists()) {
			let path = game_dir.join("config").join("iris.properties");
			let _ = std::fs::create_dir_all(path.parent().unwrap());
			let mut kept: Vec<String> = std::fs::read_to_string(&path)
				.unwrap_or_default()
				.lines()
				.filter(|l| !l.starts_with("shaderPack=") && !l.starts_with("enableShaders="))
				.map(String::from)
				.collect();
			kept.push(format!("shaderPack={pack}"));
			kept.push("enableShaders=true".into());
			let _ = std::fs::write(&path, kept.join("\n") + "\n");
			lines.push(format!("Shader \"{pack}\" turned on"));
		}
	}

	if backup.dir.exists() {
		lines.push(format!("Wabbit's old files were backed up to backups\\{}", backup.dir.file_name().unwrap_or_default().to_string_lossy()));
	}
	progress("Import finished.", 1.0);
	Ok(Report { lines, skipped })
}

/// Copy or update mods. Returns (mods imported, skipped "name: why", notes).
fn import_mods(
	p: &Profile,
	game_dir: &Path,
	want_iris: bool,
	backup: &Backup,
	progress: &(dyn Fn(&str, f32) + Sync),
) -> Result<(usize, Vec<String>, Vec<String>)> {
	let mods_dir = game_dir.join("mods");
	std::fs::create_dir_all(&mods_dir)?;
	let mut skipped = Vec::new();
	let mut notes = Vec::new();

	// A new import replaces the previous one (only files Wabbit imported).
	for old in imported(game_dir) {
		let _ = std::fs::remove_file(mods_dir.join(&old.file));
		let _ = std::fs::remove_file(mods_dir.join(format!("{}.disabled", old.file)));
	}
	save_record(game_dir, &[]);

	// Read every jar; non-Fabric files are skipped.
	struct Cand {
		path: PathBuf,
		meta: Meta,
		sha1: String,
	}
	let mut cands = Vec::new();
	for path in &p.mod_jars {
		let name = path.file_name().unwrap_or_default().to_string_lossy().to_string();
		match (read_meta(path), std::fs::read(path)) {
			(Some(meta), Ok(bytes)) => cands.push(Cand { path: path.clone(), meta, sha1: net::sha1_hex(&bytes) }),
			_ => skipped.push(format!("{name}: not a Fabric mod")),
		}
	}

	// Modrinth: which project each jar is, and its 1.21.11 build. Offline, only
	// mods that already run on 1.21.11 are copied.
	let hashes: Vec<&str> = cands.iter().map(|c| c.sha1.as_str()).collect();
	let online = (|| -> Result<(Value, Value, HashMap<String, String>)> {
		let known = post_json(&format!("{API}/version_files"), json!({ "hashes": hashes, "algorithm": "sha1" }))?;
		let update = post_json(
			&format!("{API}/version_files/update"),
			json!({ "hashes": hashes, "algorithm": "sha1", "loaders": ["fabric"], "game_versions": [MC_VERSION] }),
		)?;
		Ok((known, update, managed_project_ids()?))
	})();
	let (known, update, managed) = match online {
		Ok(x) => x,
		Err(_) => {
			notes.push("Offline: mods for other Minecraft versions weren't updated".into());
			(Value::Null, Value::Null, HashMap::new())
		}
	};
	let managed_ids: HashSet<&str> = managed.keys().map(String::as_str).collect();
	// Mod ids of the mods Wabbit itself has installed, when it has.
	let wabbit_ids: HashSet<String> =
		crate::mods::installed(game_dir).values().filter_map(|f| read_meta(&mods_dir.join(f))).map(|m| m.id).collect();

	let mut downloads: Vec<(Download, String)> = Vec::new();
	let mut copied: Vec<(PathBuf, String)> = Vec::new();
	let mut already = 0;
	let mut planned: HashSet<String> = HashSet::new();
	let mut to_resolve: Vec<(String, Option<(String, String, Option<String>)>)> = Vec::new();
	let mut to_resolve_or_keep: Vec<(String, (PathBuf, String))> = Vec::new();
	for c in &cands {
		let project = known[&c.sha1]["project_id"].as_str();
		if project.is_some_and(|id| managed_ids.contains(id)) || wabbit_ids.contains(&c.meta.id) || c.meta.id.starts_with("fabric-api") {
			already += 1; // Wabbit installs its own copy of this one.
			continue;
		}
		// A jar Modrinth lists for 1.21.11 is kept as it is. Otherwise Modrinth's
		// 1.21.11 build wins: many mods claim ">=1.21" but only really work on the
		// version they were built for. Copy as-is only when Modrinth has no build
		// and the jar says it runs on 1.21.11.
		let built_for_target = known[&c.sha1]["game_versions"].as_array().is_some_and(|v| v.iter().any(|g| g == MC_VERSION));
		// Known to Modrinth: use the newest 1.21.11 release, like Wabbit's own
		// mods (which are always the newest) — an older build can be refused by
		// them (Sodium 0.8.14 won't load with Iris 1.10.5). The jar itself is the
		// fallback when it's already built for 1.21.11.
		let update_project = update[&c.sha1]["project_id"].as_str();
		if let (Some(project), true) = (project, built_for_target) {
			let own = (c.path.clone(), c.sha1.clone());
			to_resolve_or_keep.push((project.to_string(), own));
		} else if built_for_target {
			copied.push((c.path.clone(), "copied".into()));
		} else if let Some(project) = update_project {
			to_resolve.push((project.to_string(), update.get(&c.sha1).and_then(primary_file)));
		} else if project.is_none() && runs_on_target(&c.meta) {
			// Not on Modrinth at all: trust the jar. (A Modrinth mod with no
			// 1.21.11 build is skipped even if its jar claims ">=1.21" — e.g.
			// ModernFix for 1.21.4 crashes 1.21.11 on start.)
			copied.push((c.path.clone(), "copied".into()));
		} else {
			skipped.push(format!("{} {}: no version for Minecraft {MC_VERSION}", c.meta.name, c.meta.version));
			continue;
		}
		planned.insert(c.meta.id.clone());
	}
	// Newest *release* for 1.21.11 (Modrinth's update lookup also returns betas,
	// which often need beta versions of Sodium & co.), looked up in parallel.
	let resolved: Vec<Option<(String, String, Option<String>)>> = std::thread::scope(|s| {
		let hs: Vec<_> = to_resolve
			.iter()
			.map(|(project, fallback)| s.spawn(move || latest_for_target(project).and_then(|(_, v)| primary_file(&v)).or_else(|| fallback.clone())))
			.collect();
		hs.into_iter().map(|h| h.join().ok().flatten()).collect()
	});
	for (file, url, sha1) in resolved.into_iter().flatten() {
		downloads.push((Download::new(url, mods_dir.join(&file), sha1, None), "updated".into()));
	}
	let newest: Vec<Option<(String, String, Option<String>)>> = std::thread::scope(|s| {
		let hs: Vec<_> = to_resolve_or_keep
			.iter()
			.map(|(project, _)| s.spawn(move || latest_for_target(project).and_then(|(_, v)| primary_file(&v))))
			.collect();
		hs.into_iter().map(|h| h.join().ok().flatten()).collect()
	});
	for ((_, (path, own_sha1)), new) in to_resolve_or_keep.iter().zip(newest) {
		match new {
			Some((file, url, sha1)) if !sha1.as_deref().is_some_and(|h| h.eq_ignore_ascii_case(own_sha1)) => {
				downloads.push((Download::new(url, mods_dir.join(&file), sha1, None), "updated".into()))
			}
			_ => copied.push((path.clone(), "copied".into())),
		}
	}

	// Shaders need Iris.
	if want_iris && !planned.contains("iris") {
		match latest_for_target("iris").and_then(|(_, v)| primary_file(&v)) {
			Some((file, url, sha1)) => {
				downloads.retain(|(d, _)| !d.dest.file_name().is_some_and(|n| n.to_string_lossy().starts_with("iris")));
				downloads.push((Download::new(url, mods_dir.join(&file), sha1, None), "added".into()));
				notes.push("Iris added so shader packs work".into());
			}
			None => notes.push("Couldn't get Iris (needed for shaders)".into()),
		}
	}

	// Copy, download, then read what we ended up with.
	let mut placed: Vec<(PathBuf, String)> = Vec::new();
	let total = copied.len() + downloads.len();
	for (i, (src, how)) in copied.iter().enumerate() {
		let dest = mods_dir.join(src.file_name().unwrap_or_default());
		if !dest.exists() {
			std::fs::copy(src, &dest)?;
			placed.push((dest, how.clone()));
		}
		progress(&format!("Mods {}/{total}…", i + 1), 0.45 + 0.4 * (i + 1) as f32 / total.max(1) as f32);
	}
	let base = copied.len();
	let dl: Vec<Download> = downloads.iter().map(|(d, _)| d.clone()).collect();
	let hows: HashMap<PathBuf, String> = downloads.iter().map(|(d, h)| (d.dest.clone(), h.clone())).collect();
	let failed = net::download_all(dl.clone(), 6, &|done, _| {
		progress(&format!("Mods {}/{total}…", base + done), 0.45 + 0.4 * (base + done) as f32 / total.max(1) as f32)
	});
	if let Err(e) = failed {
		notes.push(format!("Some mod downloads failed: {e:#}"));
	}
	for d in dl {
		if d.dest.exists() {
			let how = hows.get(&d.dest).cloned().unwrap_or_default();
			placed.push((d.dest, how));
		}
	}

	// Missing dependencies: fetch their 1.21.11 builds from Modrinth (two rounds).
	progress("Checking dependencies…", 0.88);
	for _ in 0..2 {
		let metas: Vec<Meta> = placed.iter().filter_map(|(p, _)| read_meta(p)).collect();
		let mut have: HashSet<String> = metas.iter().flat_map(|m| m.all_ids.iter().map(|(i, _)| i.clone())).collect();
		have.extend(wabbit_ids.iter().cloned());
		have.extend(builtins().iter().map(|(i, _)| i.to_string()));
		let missing: BTreeMap<String, ()> = metas
			.iter()
			.flat_map(|m| m.depends.iter().map(|(d, _)| d.clone()))
			.filter(|d| !have.contains(d) && !d.starts_with("fabric"))
			.map(|d| (d, ()))
			.collect();
		if missing.is_empty() {
			break;
		}
		let mut fetched = Vec::new();
		for dep in missing.keys() {
			let found = latest_for_target(dep).or_else(|| latest_for_target(&dep.replace('_', "-")));
			let Some((project, v)) = found else { continue };
			if managed.contains_key(&project) {
				continue; // Wabbit installs it on Play.
			}
			if let Some((file, url, sha1)) = primary_file(&v) {
				fetched.push(Download::new(url, mods_dir.join(file), sha1, None));
			}
		}
		if fetched.is_empty() {
			break;
		}
		let _ = net::download_all(fetched.clone(), 6, &|_, _| {});
		for d in fetched {
			if d.dest.exists() && !placed.iter().any(|(p, _)| p == &d.dest) {
				placed.push((d.dest, "added".into()));
			}
		}
	}

	// One copy per mod id: keep the newest.
	let mut by_id: BTreeMap<String, (PathBuf, Meta, String)> = BTreeMap::new();
	for (path, how) in placed {
		let Some(meta) = read_meta(&path) else {
			let _ = std::fs::remove_file(&path);
			continue;
		};
		match by_id.get(&meta.id) {
			Some((old, old_meta, _)) => {
				let newer = match (parse_version(&meta.version), parse_version(&old_meta.version)) {
					(Some(a), Some(b)) => cmp_versions(&a, &b).is_gt(),
					_ => false,
				};
				if newer {
					let _ = std::fs::remove_file(old);
					by_id.insert(meta.id.clone(), (path, meta, how));
				} else if old != &path {
					let _ = std::fs::remove_file(&path);
				}
			}
			None => {
				by_id.insert(meta.id.clone(), (path, meta, how));
			}
		}
	}

	let record: Vec<Imported> = by_id
		.values()
		.map(|(path, m, how)| Imported {
			file: path.file_name().unwrap_or_default().to_string_lossy().to_string(),
			id: m.id.clone(),
			name: m.name.clone(),
			version: m.version.clone(),
			how: how.clone(),
			problem: None,
		})
		.collect();
	save_record(game_dir, &record);

	// Their mods' settings (config files named after the mod).
	if let Some(src) = &p.config_dir {
		let ids: Vec<String> = record.iter().map(|m| m.id.to_lowercase()).filter(|i| i.len() >= 3).collect();
		let dest = game_dir.join("config");
		let mut n = 0;
		for e in entries(src) {
			let name = e.file_name().unwrap_or_default().to_string_lossy().to_lowercase();
			let squash = |s: &str| s.replace(['-', '_'], "");
			let ours = ids.iter().any(|id| name.starts_with(id.as_str()) || squash(&name).starts_with(&squash(id)));
			if !ours {
				continue;
			}
			let target = dest.join(e.file_name().unwrap_or_default());
			backup.save(&target, &Path::new("config").join(e.file_name().unwrap_or_default()));
			let _ = std::fs::create_dir_all(&dest);
			let ok = if e.is_dir() { copy_dir(&e, &target).is_ok() } else { std::fs::copy(&e, &target).is_ok() };
			n += ok as usize;
		}
		if n > 0 {
			notes.push(format!("{n} mod settings files"));
		}
	}
	let updated = record.iter().filter(|m| m.how == "updated").count();
	if updated > 0 {
		notes.push(format!("{updated} of those were updated to their newest Minecraft {MC_VERSION} release"));
	}
	if already > 0 {
		notes.push(format!("{already} mods Wabbit already includes (Sodium, Fabric API, …) were left to Wabbit"));
	}
	Ok((record.len(), skipped, notes))
}

// ---------------------------------------------------------------- validate ----

/// Before Play: switch off imported mods that would stop the game from starting
/// (missing or wrong-version dependency, a conflict, a duplicate of a mod
/// Wabbit installs). Returns "name: why" for each one switched off.
pub fn validate(game_dir: &Path) -> Vec<String> {
	let mut list = imported(game_dir);
	if list.is_empty() {
		return Vec::new();
	}
	let mods_dir = game_dir.join("mods");
	// Every enabled jar in the folder, imported or not.
	let mut jars_meta: Vec<(String, Meta)> =
		jars(&mods_dir).into_iter().filter_map(|p| Some((p.file_name()?.to_string_lossy().to_string(), read_meta(&p)?))).collect();
	let imported_files: HashSet<String> = list.iter().map(|m| m.file.clone()).collect();
	let mut out = Vec::new();
	loop {
		let mut have: HashMap<String, String> = builtins().iter().map(|(i, v)| (i.to_string(), v.to_string())).collect();
		for (_, m) in &jars_meta {
			for (id, v) in &m.all_ids {
				have.entry(id.clone()).or_insert_with(|| v.clone());
			}
		}
		let problem = jars_meta.iter().filter(|(f, _)| imported_files.contains(f)).find_map(|(file, m)| {
			if jars_meta.iter().any(|(f, o)| f != file && o.id == m.id && !imported_files.contains(f)) {
				return Some((file.clone(), "Wabbit already includes it".to_string()));
			}
			for (dep, ps) in &m.depends {
				match have.get(dep) {
					None => return Some((file.clone(), format!("needs {dep}"))),
					Some(v) if !ps.iter().any(|p| matches(p, v)) => {
						let want = ps.join(" or ");
						return Some((file.clone(), if dep == "minecraft" { format!("made for Minecraft {want}") } else { format!("needs {dep} {want} (have {v})") }));
					}
					_ => {}
				}
			}
			for (other, ps) in &m.breaks {
				if let Some(v) = have.get(other) {
					if ps.iter().any(|p| matches(p, v)) {
						return Some((file.clone(), format!("doesn't work with {other}")));
					}
				}
			}
			// Another mod refusing this one (Sodium "breaks" old Iris builds).
			for (f, o) in &jars_meta {
				if f == file {
					continue;
				}
				for (target, ps) in &o.breaks {
					if m.all_ids.iter().any(|(id, v)| id == target && ps.iter().any(|p| matches(p, v))) {
						return Some((file.clone(), format!("{} {} doesn't allow this version", o.name, o.version)));
					}
				}
			}
			None
		});
		let Some((file, why)) = problem else { break };
		let _ = std::fs::rename(mods_dir.join(&file), mods_dir.join(format!("{file}.disabled")));
		jars_meta.retain(|(f, _)| f != &file);
		if let Some(m) = list.iter_mut().find(|m| m.file == file) {
			out.push(format!("{}: {why}", m.name));
			m.problem = Some(why);
		}
	}
	if !out.is_empty() {
		save_record(game_dir, &list);
	}
	out
}

/// The mod id that follows `key` on a line, e.g. "from mod krypton]" -> "krypton".
fn id_after(line: &str, key: &str) -> Vec<String> {
	line.match_indices(key)
		.map(|(pos, _)| line[pos + key.len()..].chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect::<String>())
		.filter(|s| !s.is_empty())
		.collect()
}

/// Imported mod ids a crash points at. Only the error itself counts (the
/// first exception line, its "Caused by" chain, ERROR lines), never warnings,
/// and the deepest cause wins. When the failing mod is one Wabbit installs
/// (Krypton, Sodium, ...), the imported mod it clashed with is blamed instead
/// ("... from mod krypton ... already redirected by e4mc.mixins.json ...").
fn crash_culprits(log: &str, report: &str, imported_ids: &HashSet<String>) -> Vec<String> {
	let mut out: Vec<String> = Vec::new();
	let is_imported = |id: &str| imported_ids.contains(id);
	// 1. Fabric refusing to start: "Incompatible mods found!" lists the mods involved.
	if log.contains("Incompatible mods found") {
		for line in log.lines().filter(|l| l.contains("Mod '") || l.contains("mod '")) {
			out.extend(id_after(line, "' (").into_iter().filter(|i| is_imported(i)));
		}
		if !out.is_empty() {
			return out;
		}
	}
	// 2. The deepest mod named in the error chain.
	let source = if report.is_empty() { log } else { report };
	let error_lines: Vec<&str> = source
		.lines()
		.filter(|l| l.starts_with("Caused by") || l.contains("/ERROR]") || l.starts_with("java.") || l.starts_with("net.fabricmc"))
		.collect();
	let deepest = error_lines
		.iter()
		.rev()
		.find_map(|l| id_after(l, "Mixin apply for mod ").into_iter().chain(id_after(l, "from mod ")).next());
	if let Some(culprit) = deepest {
		if is_imported(&culprit) {
			return vec![culprit];
		}
		// A Wabbit mod failed: blame the imported mod that got there first.
		let prefix = format!("from mod {culprit}");
		for line in log.lines().chain(report.lines()).filter(|l| l.contains(&prefix)) {
			for partner in id_after(line, "already redirected by ").into_iter().chain(id_after(line, "conflicts with ")) {
				if let Some(id) = imported_ids.iter().find(|id| partner == **id || partner.starts_with(id.as_str())) {
					out.push(id.clone());
				}
			}
		}
		if !out.is_empty() {
			return out;
		}
	}
	// 3. Fallbacks: the entrypoint that threw, or the report's suspects.
	for line in &error_lines {
		out.extend(id_after(line, "provided by '").into_iter().filter(|i| is_imported(i)));
	}
	if out.is_empty() {
		let mut lines = report.lines();
		while let Some(l) = lines.next() {
			if l.contains("Suspected Mod") {
				for next in lines.by_ref().take(6) {
					if let (Some(a), Some(b)) = (next.find('('), next.find(')')) {
						if a < b && is_imported(&next[a + 1..b]) {
							out.push(next[a + 1..b].to_string());
						}
					}
				}
				break;
			}
		}
	}
	out
}

/// After the game exits with an error: switch off the imported mods the crash
/// points at (see [`crash_culprits`]) and return their names.
pub fn blame_crash(game_dir: &Path) -> Vec<String> {
	let mut list = imported(game_dir);
	if list.is_empty() {
		return Vec::new();
	}
	let log = std::fs::read_to_string(game_dir.join("logs").join("latest.log")).unwrap_or_default();
	// The newest crash report, if it was written in the last few minutes.
	let newest = entries(&game_dir.join("crash-reports")).into_iter().max_by_key(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok());
	let report = newest
		.filter(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok().and_then(|t| t.elapsed().ok()).is_some_and(|e| e.as_secs() < 600))
		.and_then(|p| std::fs::read_to_string(p).ok())
		.unwrap_or_default();
	let mods_dir = game_dir.join("mods");
	let enabled: HashSet<String> = list.iter().filter(|m| mods_dir.join(&m.file).exists()).map(|m| m.id.clone()).collect();
	let ids = crash_culprits(&log, &report, &enabled);
	let mut out = Vec::new();
	for m in list.iter_mut().filter(|m| ids.contains(&m.id)) {
		if std::fs::rename(mods_dir.join(&m.file), mods_dir.join(format!("{}.disabled", m.file))).is_ok() {
			m.problem = Some(format!("crashed Minecraft {MC_VERSION}"));
			out.push(m.name.clone());
		}
	}
	if !out.is_empty() {
		save_record(game_dir, &list);
	}
	out
}

#[cfg(test)]
mod tests {
	use super::matches;

	#[test]
	fn predicates() {
		assert!(matches(">=1.21.4", "1.21.11"));
		assert!(matches("~1.21.4", "1.21.11"));
		assert!(matches("1.21.x", "1.21.11"));
		assert!(matches(">=1.21 <1.22", "1.21.11"));
		assert!(matches("*", "1.21.11"));
		assert!(matches(">=1.21.11-", "1.21.11"));
		assert!(!matches("1.21.4", "1.21.11"));
		assert!(!matches(">=26.1", "1.21.11"));
		assert!(!matches("~26.1-", "1.21.11"));
		assert!(!matches(">=1.21 <1.21.5", "1.21.11"));
		assert!(!matches("~1.21.4 <1.21.9", "1.21.11"));
		assert!(matches(">=0.6.0", "0.6.13+mc1.21.11"));
		assert!(!matches("<0.6", "0.6.13+mc1.21.11"));
		assert!(matches("^1.2", "1.9.0"));
		assert!(!matches("^1.2", "2.0.0"));
		assert!(matches(">=1.21.2 <=1.21.11", "1.21.11"));
		assert!(matches("1.21.4 || 1.21.11", "1.21.11"));
	}

	#[test]
	fn crash_blame() {
		use super::crash_culprits;
		use std::collections::HashSet;
		let ids: HashSet<String> = ["e4mc", "controlify", "optigui", "polytone", "modernfix", "iris"].iter().map(|s| s.to_string()).collect();
		// Wabbit's Krypton fails because imported e4mc redirected the same call first.
		let log = "[x] [Render thread/WARN]: @Redirect conflict. Skipping krypton.mixins.json:shared.X from mod krypton->@Redirect::onKey with priority 1000, already redirected by e4mc.mixins.json:Y with priority 1000\n\
		           [x] [main/WARN]: Mixin from mod optigui and from mod polytone skipped";
		let report = "java.lang.RuntimeException: Could not execute entrypoint stage 'main' due to errors, provided by 'controlify' at 'A'!\n\
		              Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError: Critical injection failure: Redirector onKey in krypton.mixins.json:shared.X from mod krypton failed injection check";
		assert_eq!(crash_culprits(log, report, &ids), vec!["e4mc".to_string()]);
		// An imported mod's own mixin failing.
		let log = "[x] [main/ERROR]: Mixin apply for mod modernfix failed modernfix-common.mixins.json";
		assert_eq!(crash_culprits(log, "", &ids), vec!["modernfix".to_string()]);
		// Fabric refusing to start.
		let log = "[x] [main/ERROR]: Incompatible mods found!\n\t - Mod 'Sodium' (sodium) 0.8.14 is incompatible with version 1.10.7 or earlier of mod 'Iris' (iris), yet 1.10.5 is present!";
		assert_eq!(crash_culprits(log, "", &ids), vec!["iris".to_string()]);
	}
}
