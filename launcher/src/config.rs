//! Settings and the signed-in account, kept in %APPDATA%\wabbit\settings.json.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub fn appdata() -> PathBuf {
	std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

/// Launcher data: settings, cached skin, logs.
pub fn data_dir() -> PathBuf {
	let d = appdata().join("wabbit");
	let _ = std::fs::create_dir_all(&d);
	d
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Account {
	pub name: String,
	pub uuid: String,
	/// Microsoft refresh token (live.com). Exchanged for a fresh Minecraft token on every Play.
	pub refresh_token: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
	/// Where Minecraft lives: versions, libraries, assets, mods, saves.
	pub game_dir: PathBuf,
	pub ram_mb: u32,
	pub close_on_launch: bool,
	/// Every signed-in Microsoft account; `active` indexes the one Play uses.
	pub accounts: Vec<Account>,
	pub active: usize,
	/// Modrinth slugs the user switched off in the Mods tab.
	pub disabled_mods: Vec<String>,
	/// Server for the Play tab's quick-join button.
	pub quick_server: String,
	/// Single-account field from 0.1 settings files; moved into `accounts` on load.
	#[serde(skip_serializing)]
	account: Option<Account>,
}

impl Default for Settings {
	fn default() -> Self {
		Self {
			game_dir: appdata().join(".wabbit"),
			ram_mb: 4096,
			close_on_launch: false,
			accounts: Vec::new(),
			active: 0,
			disabled_mods: Vec::new(),
			quick_server: "donutsmp.net".into(),
			account: None,
		}
	}
}

impl Settings {
	fn path() -> PathBuf {
		data_dir().join("settings.json")
	}

	pub fn load() -> Self {
		let mut s: Self = std::fs::read(Self::path())
			.ok()
			.and_then(|b| serde_json::from_slice(&b).ok())
			.unwrap_or_default();
		if let Some(old) = s.account.take() {
			if !s.accounts.iter().any(|a| a.uuid == old.uuid) {
				s.accounts.push(old);
			}
		}
		if s.active >= s.accounts.len() {
			s.active = 0;
		}
		s
	}

	pub fn current(&self) -> Option<&Account> {
		self.accounts.get(self.active)
	}

	/// Add (or refresh) an account and make it the active one.
	pub fn upsert_account(&mut self, acc: Account) {
		match self.accounts.iter().position(|a| a.uuid == acc.uuid) {
			Some(i) => {
				self.accounts[i] = acc;
				self.active = i;
			}
			None => {
				self.accounts.push(acc);
				self.active = self.accounts.len() - 1;
			}
		}
	}

	pub fn remove_active(&mut self) {
		if self.active < self.accounts.len() {
			self.accounts.remove(self.active);
		}
		self.active = 0;
	}

	pub fn save(&self) {
		if let Ok(json) = serde_json::to_vec_pretty(self) {
			// Write-then-rename so a crash mid-save never leaves a truncated file.
			let tmp = Self::path().with_extension("json.tmp");
			if std::fs::write(&tmp, json).is_ok() {
				let _ = std::fs::rename(&tmp, Self::path());
			}
		}
	}
}
