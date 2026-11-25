//! Builds the java command line from the install plan and starts the game.

use crate::auth::Session;
use crate::install::LaunchPlan;
use anyhow::{Context, Result};
use std::collections::HashMap;
use std::path::Path;
use std::process::{Child, Command, Stdio};

/// Start Minecraft. stdout/stderr go to <game_dir>/logs/wabbit-launch.log.
/// `server` joins that server straight from the title screen (Minecraft's quick play).
pub fn start(plan: &LaunchPlan, session: &Session, game_dir: &Path, ram_mb: u32, server: Option<&str>) -> Result<Child> {
	let root = game_dir;
	let classpath = plan.classpath.iter().map(|p| p.to_string_lossy().into_owned()).collect::<Vec<_>>().join(";");
	let vars: HashMap<&str, String> = HashMap::from([
		("auth_player_name", session.name.clone()),
		("version_name", plan.version_name.clone()),
		("game_directory", game_dir.to_string_lossy().into_owned()),
		("assets_root", root.join("assets").to_string_lossy().into_owned()),
		("assets_index_name", plan.asset_index.clone()),
		("auth_uuid", session.uuid.clone()),
		("auth_access_token", session.access_token.clone()),
		("clientid", "wabbit".into()),
		("auth_xuid", session.xuid.clone()),
		("user_type", "msa".into()),
		("version_type", "release".into()),
		("natives_directory", plan.natives_dir.to_string_lossy().into_owned()),
		("launcher_name", "wabbit".into()),
		("launcher_version", env!("CARGO_PKG_VERSION").into()),
		("classpath", classpath),
		("classpath_separator", ";".into()),
		("library_directory", root.join("libraries").to_string_lossy().into_owned()),
	]);
	let fill = |template: &str| {
		let mut s = template.to_string();
		for (k, v) in &vars {
			s = s.replace(&format!("${{{k}}}"), v);
		}
		s
	};

	let mut args: Vec<String> = vec![format!("-Xmx{ram_mb}M"), format!("-Xms{}M", (ram_mb / 4).max(512))];
	args.extend(plan.jvm_args.iter().map(|a| fill(a)));
	// plan.logging_arg (Mojang's XML log layout, meant for a launcher's log
	// viewer) is deliberately not passed: without it the game logs plain text,
	// to this file and to logs/latest.log.
	args.push(plan.main_class.clone());
	args.extend(plan.game_args.iter().map(|a| fill(a)));
	if let Some(server) = server.map(str::trim).filter(|s| !s.is_empty()) {
		args.push("--quickPlayMultiplayer".into());
		args.push(server.to_string());
	}

	let logs = game_dir.join("logs");
	std::fs::create_dir_all(&logs)?;
	let out = std::fs::File::create(logs.join("wabbit-launch.log"))?;
	let err = out.try_clone()?;

	let mut cmd = Command::new(&plan.java);
	cmd.args(&args).current_dir(game_dir).stdin(Stdio::null()).stdout(out).stderr(err);
	#[cfg(windows)]
	{
		use std::os::windows::process::CommandExt;
		const CREATE_NO_WINDOW: u32 = 0x0800_0000;
		cmd.creation_flags(CREATE_NO_WINDOW);
	}
	cmd.spawn().with_context(|| format!("could not start {}", plan.java.display()))
}
