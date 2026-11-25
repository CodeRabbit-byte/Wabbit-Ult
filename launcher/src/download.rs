use anyhow::{Context, Result};
use futures::stream::{self, StreamExt};
use sha1::{Digest, Sha1};
use std::path::PathBuf;

pub struct Job {
    pub url: String,
    pub dest: PathBuf,
    pub sha1: Option<String>,
}

pub fn new_client() -> Result<reqwest::Client> {
    Ok(reqwest::Client::builder().user_agent("WabbitLauncher/0.1").build()?)
}

pub fn sha1_hex(bytes: &[u8]) -> String {
    let mut h = Sha1::new();
    h.update(bytes);
    h.finalize().iter().map(|b| format!("{:02x}", b)).collect()
}

async fn is_valid(job: &Job) -> bool {
    match tokio::fs::read(&job.dest).await {
        Ok(existing) => match &job.sha1 {
            Some(h) => sha1_hex(&existing).eq_ignore_ascii_case(h),
            None => true,
        },
        Err(_) => false,
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
    for _ in 0..3 {
        match try_once(client, job).await {
            Ok(()) => return Ok(()),
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap()).with_context(|| format!("downloading {}", job.url))
}

/// Downloads all jobs in parallel (16 at a time). Existing files with a matching checksum are skipped.
pub async fn download_all(client: &reqwest::Client, jobs: Vec<Job>) -> Result<()> {
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
