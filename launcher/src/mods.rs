//! Performance mods for 1.21.11 from Modrinth. Only the files Wabbit installed
//! are ever replaced or removed; anything else in the mods folder is the user's.

use crate::install::MC_VERSION;
use crate::net::{self, Download};
use anyhow::Result;
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::Path;

/// Fabric API plus client-side performance mods with release builds for 1.21.11
/// (checked on Modrinth 2026-11-25). All visual/performance only — fine on any server.
pub const MODS: &[(&str, &str)] = &[
	("fabric-api", "Fabric API"),
	("sodium", "Sodium — rendering engine"),
	("sodium-extra", "Sodium Extra — more video settings"),
	("lithium", "Lithium — game logic"),
	("ferrite-core", "FerriteCore — memory"),
	("entityculling", "EntityCulling — skip hidden entities"),
	("immediatelyfast", "ImmediatelyFast — faster HUD/text drawing"),
	("krypton", "Krypton — networking"),
	("dynamic-fps", "Dynamic FPS — idle/unfocused FPS cap"),
	("badoptimizations", "BadOptimizations — cached per-frame work"),
	("cull-leaves", "Cull Leaves — hidden leaf faces"),
	("debugify", "Debugify — vanilla bug fixes"),
	("fastquit", "FastQuit — no freeze when leaving a world"),
	("cloth-config", "Cloth Config — needed by FastQuit"),
	("fast-ip-ping", "Fast IP Ping — faster server list"),
	("language-reload", "Language Reload — faster reloads"),
	("modmenu", "Mod Menu — settings for all of the above"),
];

const API: &str = "https://api.modrinth.com/v2";

fn record_path(game_dir: &Path) -> std::path::PathBuf {
	game_dir.join("mods").join(".wabbit-installed.json")
}

/// Mods that can't be switched off: everything else depends on Fabric API.
pub const LOCKED: &[&str] = &["fabric-api"];

/// The effective "off" list: what the user switched off, minus locked mods, and
/// Cloth Config is kept on while FastQuit (which needs it) is on.
pub fn effective_disabled(user_disabled: &[String]) -> Vec<String> {
	let mut off: Vec<String> = user_disabled.iter().filter(|s| !LOCKED.contains(&s.as_str())).cloned().collect();
	if !off.iter().any(|s| s == "fastquit") {
		off.retain(|s| s != "cloth-config");
	}
	off
}

/// Slug -> installed file name, for the Mods tab.
pub fn installed(game_dir: &Path) -> BTreeMap<String, String> {
	let mut rec: BTreeMap<String, String> = std::fs::read(record_path(game_dir))
		.ok()
		.and_then(|b| serde_json::from_slice(&b).ok())
		.unwrap_or_default();
	rec.retain(|_, f| game_dir.join("mods").join(f).exists());
	rec
}

/// Title, one-line description and icon for each mod, from Modrinth (one request).
pub struct Info {
	pub slug: String,
	pub title: String,
	pub description: String,
	pub icon: Option<Vec<u8>>,
}

pub fn info() -> Result<Vec<Info>> {
	let ids: Vec<String> = MODS.iter().map(|(s, _)| format!("\"{s}\"")).collect();
	let url = format!("{API}/projects?ids={}", urlenc(&format!("[{}]", ids.join(","))));
	let projects: Value = net::get_json(&url)?;
	let mut out = Vec::new();
	for p in projects.as_array().into_iter().flatten() {
		let slug = p["slug"].as_str().unwrap_or_default().to_string();
		// Only Modrinth's CDN, and skip anything that isn't a reasonable image.
		let icon = p["icon_url"]
			.as_str()
			.filter(|u| u.starts_with("https://cdn.modrinth.com/"))
			.and_then(|u| net::get_bytes(u).ok())
			.filter(|b| b.len() < 2_000_000);
		out.push(Info {
			slug,
			title: p["title"].as_str().unwrap_or_default().to_string(),
			description: p["description"].as_str().unwrap_or_default().to_string(),
			icon,
		});
	}
	Ok(out)
}

/// Resolve every enabled mod's newest release for 1.21.11 and download what's
/// missing; remove the files of mods the user switched off.
pub fn ensure(game_dir: &Path, disabled: &[String], progress: &(dyn Fn(&str, f32) + Sync)) -> Result<Vec<String>> {
	let disabled = effective_disabled(disabled);
	let mods_dir = game_dir.join("mods");
	std::fs::create_dir_all(&mods_dir)?;
	let mut record: BTreeMap<String, String> = std::fs::read(record_path(game_dir))
		.ok()
		.and_then(|b| serde_json::from_slice(&b).ok())
		.unwrap_or_default();

	let query = |slug: &str| {
		format!(
			"{API}/project/{slug}/version?game_versions={}&loaders={}",
			urlenc(&format!("[\"{MC_VERSION}\"]")),
			urlenc("[\"fabric\"]")
		)
	};

	// Switched-off mods leave the folder (only files Wabbit installed).
	for slug in &disabled {
		if let Some(file) = record.remove(slug) {
			let _ = std::fs::remove_file(mods_dir.join(file));
		}
	}

	// Resolve in parallel (one small API call per mod).
	let results: Vec<(String, Result<Option<(String, String, Option<String>)>>)> = std::thread::scope(|s| {
		let handles: Vec<_> = MODS
			.iter()
			.filter(|(slug, _)| !disabled.iter().any(|d| d == slug))
			.map(|(slug, _)| {
				let url = query(slug);
				let slug = slug.to_string();
				s.spawn(move || {
					let r = (|| -> Result<Option<(String, String, Option<String>)>> {
						let versions: Value = net::get_json(&url)?;
						let list = versions.as_array().cloned().unwrap_or_default();
						let best = list.iter().find(|v| v["version_type"] == "release").or(list.first());
						Ok(best.and_then(|v| {
							let files = v["files"].as_array()?;
							let f = files.iter().find(|f| f["primary"] == true).or(files.first())?;
							Some((
								f["filename"].as_str()?.to_string(),
								f["url"].as_str()?.to_string(),
								f["hashes"]["sha1"].as_str().map(String::from),
							))
						}))
					})();
					(slug, r)
				})
			})
			.collect();
		handles.into_iter().map(|h| h.join().expect("resolver thread")).collect()
	});

	let mut warnings = Vec::new();
	let mut downloads = Vec::new();
	for (slug, r) in results {
		match r {
			Ok(Some((file, url, sha1))) => {
				// A newer build replaces the copy Wabbit installed before.
				if let Some(old) = record.get(&slug) {
					if old != &file {
						let _ = std::fs::remove_file(mods_dir.join(old));
					}
				}
				record.insert(slug, file.clone());
				downloads.push(Download::new(url, mods_dir.join(file), sha1, None));
			}
			Ok(None) => warnings.push(format!("{slug}: no {MC_VERSION} build")),
			Err(e) => warnings.push(format!("{slug}: {e:#}")),
		}
	}
	net::download_all(downloads, 6, &|done, total| progress(&format!("Mods {done}/{total}…"), done as f32 / total as f32))?;
	let _ = std::fs::write(record_path(game_dir), serde_json::to_vec_pretty(&record)?);
	Ok(warnings)
}

fn urlenc(s: &str) -> String {
	s.bytes()
		.map(|b| match b {
			b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
			_ => format!("%{b:02X}"),
		})
		.collect()
}
