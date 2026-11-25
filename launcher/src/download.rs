use anyhow::{Context, Result};
use futures::stream::{self, StreamExt};
use sha1::{Digest, Sha1};
use std::collections::HashSet;
use std::path::PathBuf;
use std::time::Duration;

pub struct Job {
    pub url: String,
    pub dest: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    /// The file name is its own checksum (game assets) and files are only ever written after their checksum
    /// was verified, so an existing file of the right size is trusted without hashing it again.
    pub content_addressed: bool,
}

pub fn new_client() -> Result<reqwest::Client> {
    // Without these a connection that stalls mid-download would hang the install forever,
    // and the retry loop would never get a chance to run.
    Ok(reqwest::Client::builder()
        .user_agent(concat!("WabbitLauncher/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(60))
        .build()?)
}

pub fn sha1_hex(bytes: &[u8]) -> String {
    let mut h = Sha1::new();
    h.update(bytes);
    h.finalize().iter().map(|b| format!("{:02x}", b)).collect()
}

async fn is_valid(job: &Job) -> bool {
    let Ok(meta) = tokio::fs::metadata(&job.dest).await else { return false };
    if let Some(size) = job.size {
        if meta.len() != size {
            return false;
        }
        if job.content_addressed {
            return true;
        }
    }
    match &job.sha1 {
        Some(h) => match tokio::fs::read(&job.dest).await {
            Ok(existing) => sha1_hex(&existing).eq_ignore_ascii_case(h),
            Err(_) => false,
        },
        None => true,
    }
}

async fn try_once(client: &reqwest::Client, job: &Job) -> Result<()> {
    let bytes = client.get(&job.url).send().await?.error_for_status()?.bytes().await?;
    if let Some(h) = &job.sha1 {
        if !sha1_hex(&bytes).eq_ignore_ascii_case(h) {
            anyhow::bail!("checksum mismatch");
        }
    }
    let mut part = job.dest.clone().into_os_string();
    part.push(".part");
    let part = PathBuf::from(part);
    tokio::fs::write(&part, &bytes).await?;
    tokio::fs::rename(&part, &job.dest).await?;
    Ok(())
}

async fn fetch(client: &reqwest::Client, job: &Job) -> Result<()> {
    if is_valid(job).await {
        return Ok(());
    }
    if let Some(parent) = job.dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let mut last = None;
    for attempt in 0..3u64 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(500 * attempt)).await;
        }
        match try_once(client, job).await {
            Ok(()) => return Ok(()),
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap()).with_context(|| format!("downloading {}", job.url))
}

/// Drops jobs that write to a file another job already writes to. Two downloads sharing one
/// destination would also share its ".part" file and could leave a half-written file behind.
pub fn dedupe(jobs: Vec<Job>) -> Vec<Job> {
    let mut seen = HashSet::new();
    jobs.into_iter().filter(|j| seen.insert(j.dest.clone())).collect()
}

/// Downloads all jobs in parallel (16 at a time). Existing files that are still valid are skipped.
pub async fn download_all(client: &reqwest::Client, jobs: Vec<Job>) -> Result<()> {
    let jobs = dedupe(jobs);
    let total = jobs.len();
    let mut done = 0usize;
    let mut results = stream::iter(jobs)
        .map(|job| {
            let c = client.clone();
            async move { fetch(&c, &job).await }
        })
        .buffer_unordered(16);
    while let Some(r) = results.next().await {
        r?;
        done += 1;
        if done % 100 == 0 || done == total {
            println!("  {done}/{total} files");
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(dest: &str) -> Job {
        Job { url: String::new(), dest: PathBuf::from(dest), sha1: None, size: None, content_addressed: false }
    }

    #[test]
    fn duplicate_destinations_are_dropped() {
        let jobs = dedupe(vec![job("a"), job("b"), job("a")]);
        let dests: Vec<_> = jobs.iter().map(|j| j.dest.clone()).collect();
        assert_eq!(dests, vec![PathBuf::from("a"), PathBuf::from("b")]);
    }

    #[test]
    fn sha1_of_empty_input() {
        assert_eq!(sha1_hex(b""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    }
}
