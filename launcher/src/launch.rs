use crate::install::{lib_enabled, lib_rel_path, Dirs};
use crate::model::*;
use crate::rules::rules_allow;
use anyhow::{bail, Context, Result};
use std::collections::{HashMap, HashSet};
use std::process::Command;

pub struct LaunchOptions {
    pub version_id: String,
    pub player_name: String,
    pub java: String,
    pub max_mem_mb: u32,
}

/// Same UUID Minecraft uses for offline players.
pub fn offline_uuid(name: &str) -> String {
    let mut d = md5::compute(format!("OfflinePlayer:{name}")).0;
    d[6] = (d[6] & 0x0f) | 0x30;
    d[8] = (d[8] & 0x3f) | 0x80;
    let h: String = d.iter().map(|b| format!("{:02x}", b)).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// Loads a version JSON and merges it with the version it inherits from (Fabric -> vanilla).
/// Returns the merged version and the id whose jar is the game jar.
pub fn load_resolved(dirs: &Dirs, id: &str) -> Result<(VersionJson, String)> {
    let path = dirs.version_json(id);
    let text = std::fs::read_to_string(&path)
        .with_context(|| format!("version {id} is not installed (run install first)"))?;
    let mut v: VersionJson = serde_json::from_str(&text)?;
    let Some(parent_id) = v.inherits_from.clone() else {
        return Ok((v, id.to_string()));
    };
    let (parent, base) = load_resolved(dirs, &parent_id)?;

    if v.main_class.is_empty() {
        v.main_class = parent.main_class.clone();
    }
    let mut libs = v.libraries.clone();
    libs.extend(parent.libraries.clone());
    let mut seen = HashSet::new();
    libs.retain(|l| seen.insert(lib_key(&l.name)));
    v.libraries = libs;

    let pa = parent.arguments.unwrap_or_default();
    let ca = v.arguments.take().unwrap_or_default();
    v.arguments = Some(Arguments {
        game: pa.game.into_iter().chain(ca.game).collect(),
        jvm: pa.jvm.into_iter().chain(ca.jvm).collect(),
    });
    v.asset_index = v.asset_index.or(parent.asset_index);
    v.assets = v.assets.or(parent.assets);
    v.downloads = v.downloads.or(parent.downloads);
    Ok((v, base))
}

/// group:artifact[:classifier] - the version is left out so a child's newer lib replaces the parent's.
fn lib_key(name: &str) -> String {
    let p: Vec<&str> = name.split(':').collect();
    match p.get(3) {
        Some(c) => format!("{}:{}:{}", p[0], p[1], c),
        None => format!("{}:{}", p[0], p[1]),
    }
}

fn subst(s: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = s.to_string();
    for (k, v) in vars {
        out = out.replace(&format!("${{{k}}}"), v);
    }
    out
}

fn expand(args: &[Arg], vars: &HashMap<&str, String>) -> Vec<String> {
    let mut out = Vec::new();
    for a in args {
        match a {
            Arg::Plain(s) => out.push(subst(s, vars)),
            Arg::Ruled { rules, value } if rules_allow(rules) => match value {
                OneOrMany::One(s) => out.push(subst(s, vars)),
                OneOrMany::Many(list) => out.extend(list.iter().map(|s| subst(s, vars))),
            },
            Arg::Ruled { .. } => {}
        }
    }
    out
}

/// Builds the java command for an installed version using an offline account.
/// Supports modern versions (1.19+) whose JSON uses the `arguments` format.
pub fn build_command(dirs: &Dirs, opts: &LaunchOptions) -> Result<Command> {
    let (v, base_id) = load_resolved(dirs, &opts.version_id)?;
    let args = v.arguments.clone()
        .context("this version uses the legacy argument format, which is not supported")?;

    let sep = if cfg!(windows) { ";" } else { ":" };
    let mut cp: Vec<String> = Vec::new();
    for lib in v.libraries.iter().filter(|l| lib_enabled(l)) {
        let Some(rel) = lib_rel_path(lib) else { continue };
        let path = dirs.libraries().join(rel);
        if !path.exists() {
            bail!("missing library {} - run install again", lib.name);
        }
        cp.push(path.to_string_lossy().into_owned());
    }
    let jar = dirs.version_jar(&base_id);
    if !jar.exists() {
        bail!("missing game jar for {base_id} - run install again");
    }
    cp.push(jar.to_string_lossy().into_owned());

    let natives = dirs.versions().join(&opts.version_id).join("natives");
    std::fs::create_dir_all(&natives)?;
    let asset_id = v.assets.clone()
        .or_else(|| v.asset_index.as_ref().map(|a| a.id.clone()))
        .context("version has no asset index id")?;

    let mut vars: HashMap<&str, String> = HashMap::new();
    vars.insert("auth_player_name", opts.player_name.clone());
    vars.insert("auth_uuid", offline_uuid(&opts.player_name));
    vars.insert("auth_access_token", "0".into());
    vars.insert("auth_xuid", "0".into());
    vars.insert("clientid", "0".into());
    vars.insert("user_type", "legacy".into());
    vars.insert("version_name", opts.version_id.clone());
    vars.insert("version_type", "release".into());
    vars.insert("game_directory", dirs.root.to_string_lossy().into_owned());
    vars.insert("assets_root", dirs.assets().to_string_lossy().into_owned());
    vars.insert("assets_index_name", asset_id);
    vars.insert("natives_directory", natives.to_string_lossy().into_owned());
    vars.insert("library_directory", dirs.libraries().to_string_lossy().into_owned());
    vars.insert("classpath_separator", sep.into());
    vars.insert("classpath", cp.join(sep));
    vars.insert("launcher_name", "Wabbit".into());
    vars.insert("launcher_version", "0.1.0".into());

    let mut cmd = Command::new(&opts.java);
    cmd.arg(format!("-Xmx{}M", opts.max_mem_mb));
    cmd.args(expand(&args.jvm, &vars));
    cmd.arg(&v.main_class);
    cmd.args(expand(&args.game, &vars));
    cmd.current_dir(&dirs.root);
    Ok(cmd)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offline_uuid_is_stable_and_versioned() {
        let u = offline_uuid("Steve");
        assert_eq!(u, offline_uuid("Steve"));
        assert_eq!(u.len(), 36);
        assert_eq!(&u[14..15], "3");
    }

    #[test]
    fn lib_key_ignores_version() {
        assert_eq!(lib_key("org.ow2.asm:asm:9.6"), lib_key("org.ow2.asm:asm:9.8"));
        assert_ne!(lib_key("org.lwjgl:lwjgl:3.3.3"), lib_key("org.lwjgl:lwjgl:3.3.3:natives-windows"));
    }

    #[test]
    fn placeholders_are_replaced() {
        let mut vars = HashMap::new();
        vars.insert("auth_player_name", "Steve".to_string());
        assert_eq!(subst("--username ${auth_player_name}", &vars), "--username Steve");
    }
}
