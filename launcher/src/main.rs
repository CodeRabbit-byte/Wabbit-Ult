use anyhow::{bail, Context, Result};
use std::path::PathBuf;
use wabbit_launcher::{download::new_client, fabric::install_fabric, install::{install_vanilla, Dirs},
    launch::{build_command, LaunchOptions}};

fn default_root() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        return PathBuf::from(appdata).join(".wabbit");
    }
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| ".".into())).join(".wabbit")
}

const USAGE: &str = "Wabbit launcher (core)\n\
  wabbit install <mc-version>            download vanilla (e.g. 1.21.11)\n\
  wabbit fabric  <mc-version>            install vanilla + Fabric, prints the version id\n\
  wabbit launch  <version-id> [--name Player] [--java java] [--mem 4096]\n\
Add --dir <folder> to use a different game folder. Options can go anywhere on the line.";

/// Command-line arguments: positional words plus the known `--option value` pairs, in any order.
#[derive(Debug, Default, PartialEq)]
struct Cli {
    positional: Vec<String>,
    dir: Option<String>,
    name: Option<String>,
    java: Option<String>,
    mem: Option<String>,
    help: bool,
}

fn parse_args(args: &[String]) -> Result<Cli> {
    let mut cli = Cli::default();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        let slot = match a.as_str() {
            "--dir" => &mut cli.dir,
            "--name" => &mut cli.name,
            "--java" => &mut cli.java,
            "--mem" => &mut cli.mem,
            "-h" | "--help" => {
                cli.help = true;
                continue;
            }
            s if s.starts_with("--") => bail!("unknown option {s}\n\n{USAGE}"),
            _ => {
                cli.positional.push(a.clone());
                continue;
            }
        };
        let value = it.next().filter(|v| !v.starts_with("--"))
            .with_context(|| format!("{a} needs a value"))?;
        *slot = Some(value.clone());
    }
    Ok(cli)
}

/// Minecraft account names: 3-16 letters, digits or underscores.
fn valid_player_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// A `--java` value that is a path (not just "java") is made absolute, because the game runs inside the
/// game folder and a relative path would otherwise point somewhere else.
fn resolve_java(java: String) -> Result<String> {
    if java.contains('/') || java.contains('\\') {
        Ok(std::path::absolute(&java)?.to_string_lossy().into_owned())
    } else {
        Ok(java)
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cli = parse_args(&args)?;
    if cli.help || cli.positional.is_empty() {
        println!("{USAGE}");
        return Ok(());
    }

    // The game is started inside this folder, so it must be absolute: a relative --dir would make every
    // path handed to Java point one folder too deep.
    let root = match &cli.dir {
        Some(d) => std::path::absolute(d).with_context(|| format!("bad folder {d}"))?,
        None => default_root(),
    };
    let dirs = Dirs { root };

    match (cli.positional[0].as_str(), cli.positional.get(1)) {
        ("install", Some(mc)) => {
            std::fs::create_dir_all(&dirs.root)?;
            install_vanilla(&new_client()?, &dirs, mc).await?;
            println!("Installed {mc}");
        }
        ("fabric", Some(mc)) => {
            std::fs::create_dir_all(&dirs.root)?;
            let id = install_fabric(&new_client()?, &dirs, mc).await?;
            println!("Installed {id}");
            println!("Start it with: wabbit launch {id} --name YourName");
        }
        ("launch", Some(id)) => {
            let player_name = cli.name.clone().unwrap_or_else(|| "Player".into());
            if !valid_player_name(&player_name) {
                bail!("player names are 3-16 letters, digits or underscores (got \"{player_name}\")");
            }
            let max_mem_mb = match &cli.mem {
                Some(m) => m.parse::<u32>().ok().filter(|v| *v >= 512)
                    .with_context(|| format!("--mem is in megabytes and at least 512 (got \"{m}\")"))?,
                None => 4096,
            };
            let opts = LaunchOptions {
                version_id: id.clone(),
                player_name,
                java: resolve_java(cli.java.clone().unwrap_or_else(|| "java".into()))?,
                max_mem_mb,
            };
            let status = build_command(&dirs, &opts)?.status()
                .with_context(|| format!("could not start Java (\"{}\")", opts.java))?;
            if !status.success() {
                bail!("Minecraft exited with {status}");
            }
        }
        (cmd, None) if matches!(cmd, "install" | "fabric" | "launch") => {
            bail!("\"{cmd}\" needs a version, for example: wabbit {cmd} 1.21.11\n\n{USAGE}")
        }
        (cmd, _) => bail!("unknown command \"{cmd}\"\n\n{USAGE}"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn options_can_come_first() {
        let cli = parse_args(&s(&["--name", "Bob", "launch", "fabric-x", "--dir", "game"])).unwrap();
        assert_eq!(cli.positional, s(&["launch", "fabric-x"]));
        assert_eq!(cli.name.as_deref(), Some("Bob"));
        assert_eq!(cli.dir.as_deref(), Some("game"));
    }

    #[test]
    fn missing_or_unknown_option_values_are_errors() {
        assert!(parse_args(&s(&["launch", "x", "--name"])).is_err());
        assert!(parse_args(&s(&["launch", "x", "--name", "--mem", "2048"])).is_err());
        assert!(parse_args(&s(&["launch", "x", "--colour", "red"])).is_err());
    }

    #[test]
    fn player_names() {
        assert!(valid_player_name("Steve_01"));
        assert!(!valid_player_name("ab"));
        assert!(!valid_player_name("a name with spaces"));
        assert!(!valid_player_name("${auth_access_token}"));
    }
}
