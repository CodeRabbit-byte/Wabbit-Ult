//! HTTP, hashing and parallel, hash-verified downloads.

use anyhow::{Context, Result, anyhow, bail};
use serde::de::DeserializeOwned;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

pub const USER_AGENT: &str = concat!("Wabbit-Launcher/", env!("CARGO_PKG_VERSION"));

pub fn agent() -> &'static ureq::Agent {
	static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
	AGENT.get_or_init(|| {
		ureq::AgentBuilder::new()
			.timeout_connect(Duration::from_secs(15))
			.timeout_read(Duration::from_secs(60))
			.user_agent(USER_AGENT)
			.build()
	})
}

pub fn get_json<T: DeserializeOwned>(url: &str) -> Result<T> {
	let resp = agent().get(url).call().with_context(|| format!("GET {url}"))?;
	resp.into_json::<T>().with_context(|| format!("bad JSON from {url}"))
}

pub fn get_bytes(url: &str) -> Result<Vec<u8>> {
	let resp = agent().get(url).call().with_context(|| format!("GET {url}"))?;
	let mut buf = Vec::new();
	resp.into_reader().take(512 * 1024 * 1024).read_to_end(&mut buf)?;
	Ok(buf)
}

pub fn sha1_hex(bytes: &[u8]) -> String {
	sha1_smol::Sha1::from(bytes).digest().to_string()
}

/// True when `path` exists and matches the expected SHA-1 (or size, when that is all we know).
pub fn file_ok(path: &Path, sha1: Option<&str>, size: Option<u64>) -> bool {
	let Ok(meta) = std::fs::metadata(path) else { return false };
	if let Some(sz) = size {
		if meta.len() != sz {
			return false;
		}
	}
	match sha1 {
		Some(h) => std::fs::read(path).map(|b| sha1_hex(&b).eq_ignore_ascii_case(h)).unwrap_or(false),
		None => true,
	}
}

/// One file to fetch. `reuse` lists files elsewhere on disk (another launcher's
/// copy of the same library or asset) that are copied instead of downloaded
/// when their hash matches.
#[derive(Clone)]
pub struct Download {
	pub url: String,
	pub dest: PathBuf,
	pub sha1: Option<String>,
	pub size: Option<u64>,
	pub reuse: Vec<PathBuf>,
}

impl Download {
	pub fn new(url: impl Into<String>, dest: PathBuf, sha1: Option<String>, size: Option<u64>) -> Self {
		Self { url: url.into(), dest, sha1, size, reuse: Vec::new() }
	}

	fn fetch(&self) -> Result<()> {
		let sha1 = self.sha1.as_deref();
		if file_ok(&self.dest, sha1, self.size) {
			return Ok(());
		}
		if let Some(dir) = self.dest.parent() {
			std::fs::create_dir_all(dir)?;
		}
		for other in &self.reuse {
			if sha1.is_some() && file_ok(other, sha1, self.size) && std::fs::copy(other, &self.dest).is_ok() {
				return Ok(());
			}
		}
		let mut last = None;
		for _ in 0..3 {
			match get_bytes(&self.url) {
				Ok(bytes) => {
					if let Some(h) = sha1 {
						if !sha1_hex(&bytes).eq_ignore_ascii_case(h) {
							last = Some(anyhow!("hash mismatch for {}", self.url));
							continue;
						}
					}
					let part = self.dest.with_extension("part");
					std::fs::write(&part, &bytes)?;
					std::fs::rename(&part, &self.dest)?;
					return Ok(());
				}
				Err(e) => last = Some(e),
			}
		}
		Err(last.unwrap_or_else(|| anyhow!("download failed: {}", self.url)))
	}
}

/// Fetch everything with `workers` threads; `progress(done, total)` after each file.
pub fn download_all(items: Vec<Download>, workers: usize, progress: &(dyn Fn(usize, usize) + Sync)) -> Result<()> {
	let total = items.len();
	if total == 0 {
		return Ok(());
	}
	let queue = Mutex::new(items);
	let done = AtomicUsize::new(0);
	let errors = Mutex::new(Vec::<String>::new());
	std::thread::scope(|s| {
		for _ in 0..workers.max(1) {
			s.spawn(|| loop {
				let next = queue.lock().unwrap().pop();
				let Some(item) = next else { break };
				if let Err(e) = item.fetch() {
					errors.lock().unwrap().push(format!("{e:#}"));
				}
				progress(done.fetch_add(1, Ordering::Relaxed) + 1, total);
			});
		}
	});
	let errors = errors.into_inner().unwrap();
	if !errors.is_empty() {
		bail!("{} download(s) failed, first: {}", errors.len(), errors[0]);
	}
	Ok(())
}
