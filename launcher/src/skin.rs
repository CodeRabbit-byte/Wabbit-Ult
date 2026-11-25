//! eqsual's skin: fetched from Mojang, cached on disk, drawn as a standing figure.
//!
//! The cached copy is shown instantly at startup (no network wait), and the app
//! re-checks Mojang when the user clicks a button, at most once per
//! `CHECK_EVERY`, so a skin change shows up without hammering the API.

use crate::{config, net};
use anyhow::{Context, Result, bail};
use base64::Engine;
use image::{Rgba, RgbaImage};
use serde::{Deserialize, Serialize};
use std::time::Duration;

/// The character on the launcher. UUIDs never change, even if the name does.
pub const NAME: &str = "eqsual";
pub const UUID: &str = "1f0af4ba70144850803dc55c714f2d3f";
/// Mojang rate-limits the session server; 15 s keeps a click-happy user well clear.
pub const CHECK_EVERY: Duration = Duration::from_secs(15);

/// Baked in at build time so the first launch shows eqsual even offline.
const BUILTIN_PNG: &[u8] = include_bytes!("../assets/eqsual-skin.png");
const BUILTIN_SLIM: bool = true;

#[derive(Clone, Serialize, Deserialize)]
pub struct SkinMeta {
	pub url: String,
	pub slim: bool,
}

pub struct Skin {
	pub png: Vec<u8>,
	pub meta: SkinMeta,
}

fn cache_paths() -> (std::path::PathBuf, std::path::PathBuf) {
	let d = config::data_dir();
	(d.join("skin.png"), d.join("skin.json"))
}

/// The last skin seen, or the built-in one.
pub fn cached() -> Skin {
	let (png_path, meta_path) = cache_paths();
	if let (Ok(png), Ok(meta)) = (std::fs::read(&png_path), std::fs::read(&meta_path)) {
		if let Ok(meta) = serde_json::from_slice::<SkinMeta>(&meta) {
			if image::load_from_memory(&png).is_ok() {
				return Skin { png, meta };
			}
		}
	}
	Skin { png: BUILTIN_PNG.to_vec(), meta: SkinMeta { url: String::new(), slim: BUILTIN_SLIM } }
}

#[derive(Deserialize)]
struct Profile {
	properties: Vec<Property>,
}
#[derive(Deserialize)]
struct Property {
	name: String,
	value: String,
}

/// The skin URL and model a player wears now, from Mojang's session server.
fn current(uuid: &str) -> Result<SkinMeta> {
	let profile: Profile = net::get_json(&format!("https://sessionserver.mojang.com/session/minecraft/profile/{uuid}"))?;
	let prop = profile.properties.iter().find(|p| p.name == "textures").context("profile has no textures")?;
	let decoded = base64::engine::general_purpose::STANDARD.decode(&prop.value)?;
	let textures: serde_json::Value = serde_json::from_slice(&decoded)?;
	let skin = &textures["textures"]["SKIN"];
	let url = skin["url"].as_str().context("profile has no skin")?.to_string();
	Ok(SkinMeta { url, slim: skin["metadata"]["model"].as_str() == Some("slim") })
}

fn download(url: &str) -> Result<Vec<u8>> {
	// Only ever fetch from Mojang's texture host, over https.
	let https = url.replacen("http://", "https://", 1);
	if !https.starts_with("https://textures.minecraft.net/") {
		bail!("unexpected skin host: {url}");
	}
	let png = net::get_bytes(&https)?;
	image::load_from_memory(&png).context("skin is not a PNG")?;
	Ok(png)
}

/// Any player's current skin (used for the faces of signed-in accounts).
pub fn fetch(uuid: &str) -> Result<Skin> {
	let meta = current(uuid)?;
	let png = download(&meta.url)?;
	Ok(Skin { png, meta })
}

/// The 8x8 face, hat layer on top.
pub fn face(png: &[u8]) -> Result<RgbaImage> {
	let skin = image::load_from_memory(png)?.to_rgba8();
	let mut out = RgbaImage::from_pixel(8, 8, Rgba([0, 0, 0, 255]));
	for (sx, sy) in [(8, 8), (40, 8)] {
		for y in 0..8 {
			for x in 0..8 {
				let p = skin.get_pixel(sx + x, sy + y);
				if p[3] > 0 {
					out.put_pixel(x, y, Rgba([p[0], p[1], p[2], 255]));
				}
			}
		}
	}
	Ok(out)
}

/// Ask Mojang which skin eqsual wears now. Returns `Some(new skin)` only when it
/// differs from `known_url`; downloads and caches the new texture.
pub fn check(known_url: &str) -> Result<Option<Skin>> {
	let meta = current(UUID)?;
	if meta.url == known_url {
		return Ok(None);
	}
	let png = download(&meta.url)?;
	let (png_path, meta_path) = cache_paths();
	let _ = std::fs::write(png_path, &png);
	let _ = std::fs::write(meta_path, serde_json::to_vec(&meta).unwrap_or_default());
	Ok(Some(Skin { png, meta }))
}
