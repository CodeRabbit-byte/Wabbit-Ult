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
    v.libraries = merge_libraries(&v.libraries, &parent.libraries);

    let pa = parent.arguments.unwrap_or_default();
    let ca = v.arguments.take().unwrap_or_default();
    v.arguments = Some(Arguments {
        game: pa.game.into_iter().chain(ca.game).collect(),
        jvm: pa.jvm.into_iter().chain(ca.jvm).collect(),
    });
    v.asset_index = v.asset_index.or(parent.asset_index);
    v.assets = v.assets.or(parent.assets);
    v.downloads = v.downloads.or(parent.downloads);
    v.java_version = v.java_version.or(parent.java_version);
    Ok((v, base))
}

/// Child libraries first, then the parent's; only the ones allowed on this OS, and each library once
/// (the child's copy wins). Filtering by OS before removing duplicates matters: some versions list the
/// same library twice, once per OS, and keeping the wrong copy would drop it altogether.
pub fn merge_libraries(child: &[Library], parent: &[Library]) -> Vec<Library> {
    let mut seen = HashSet::new();
    child.iter().chain(parent)
        .filter(|l| lib_enabled(l))
        .filter(|l| seen.insert(lib_key(&l.name)))
        .cloned()
        .collect()
}

/// group:artifact[:classifier] - the version is left out so a child's newer lib replaces the parent's.
fn lib_key(name: &str) -> String {
    let p: Vec<&str> = name.split(':').collect();
    match (p.get(3), p.len()) {
        (Some(c), _) => format!("{}:{}:{}", p[0], p[1], c),
        (None, n) if n >= 2 => format!("{}:{}", p[0], p[1]),
        _ => name.to_string(),
    }
}

/// Replaces `${name}` placeholders in one pass, so text that comes from a value (a player name, a
/// folder name) is never expanded again. Unknown placeholders are left as they are.
fn subst(s: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find("${") {
        out.push_str(&rest[..i]);
        let after = &rest[i + 2..];
        match after.find('}') {
            Some(j) => {
                let key = &after[..j];
                match vars.get(key) {
                    Some(v) => out.push_str(v),
                    None => {
                        out.push_str("${");
                        out.push_str(key);
                        out.push('}');
                    }
                }
                rest = &after[j + 1..];
            }
            None => {
                out.push_str(&rest[i..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
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

/// Reads the major version from `java -version` output: `java version "1.8.0_402"` is 8,
/// `openjdk version "21.0.5" 2024-10-15` is 21, `openjdk version "22-ea"` is 22.
pub fn parse_java_major(text: &str) -> Option<u32> {
    let start = text.find("version \"")? + "version \"".len();
    let rest = &text[start..];
    let ver = &rest[..rest.find('"')?];
    let mut parts = ver.split(|c: char| !c.is_ascii_digit());
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// Runs `java -version` and returns the major version.
pub fn java_major(java: &str) -> Result<u32> {
    let out = Command::new(java).arg("-version").output().with_context(|| {
        format!("could not run Java (\"{java}\"). Install Java 21 from https://adoptium.net (then open the launcher again), or pass --java <path to java.exe>")
    })?;
    let text = format!("{}{}", String::from_utf8_lossy(&out.stderr), String::from_utf8_lossy(&out.stdout));
    parse_java_major(&text).with_context(|| {
        format!("could not read the Java version from \"{}\"", text.lines().next().unwrap_or("").trim())
    })
}

/// Builds the java command for an installed version using an offline account.
/// Supports modern versions (1.19+) whose JSON uses the `arguments` format.
pub fn build_command(dirs: &Dirs, opts: &LaunchOptions) -> Result<Command> {
    let (v, base_id) = load_resolved(dirs, &opts.version_id)?;
    let args = v.arguments.clone()
        .context("this version uses the legacy argument format, which is not supported")?;

    if let Some(need) = v.java_version.as_ref().map(|j| j.major_version) {
        let have = java_major(&opts.java)?;
        if have < need {
            bail!(
                "Minecraft {base_id} needs Java {need} or newer, but \"{}\" is Java {have}. \
                 Install Java {need} from https://adoptium.net, or pass --java <path to java.exe>",
                opts.java
            );
        }
    }

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
    vars.insert("launcher_version", env!("CARGO_PKG_VERSION").into());

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
    use crate::rules::current_os;

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
        assert_eq!(subst("${unknown} and ${", &vars), "${unknown} and ${");
    }

    #[test]
    fn values_are_not_expanded_twice() {
        let mut vars = HashMap::new();
        vars.insert("auth_player_name", "${auth_access_token}".to_string());
        vars.insert("auth_access_token", "secret".to_string());
        assert_eq!(subst("${auth_player_name}", &vars), "${auth_access_token}");
    }

    #[test]
    fn java_versions_are_read() {
        assert_eq!(parse_java_major("java version \"1.8.0_402\"\nJava(TM) SE Runtime"), Some(8));
        assert_eq!(parse_java_major("openjdk version \"21.0.5\" 2024-10-15"), Some(21));
        assert_eq!(parse_java_major("java version \"25.0.5.1\" 2026-11-17 LTS"), Some(25));
        assert_eq!(parse_java_major("openjdk version \"22-ea\""), Some(22));
        assert_eq!(parse_java_major("not java"), None);
    }

    fn lib(name: &str, os: Option<&str>) -> Library {
        Library {
            name: name.into(),
            downloads: None,
            rules: os.map(|o| vec![Rule {
                action: "allow".into(),
                os: Some(OsRule { name: Some(o.into()), arch: None }),
                features: None,
            }]),
            url: None,
            sha1: None,
            size: None,
        }
    }

    #[test]
    fn os_filter_runs_before_duplicate_removal() {
        let other = if current_os() == "windows" { "linux" } else { "windows" };
        // the first copy is for another OS; the second one must survive
        let parent = vec![lib("org.lwjgl:lwjgl:3.3.1", Some(other)), lib("org.lwjgl:lwjgl:3.3.2", Some(current_os()))];
        let merged = merge_libraries(&[], &parent);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].name, "org.lwjgl:lwjgl:3.3.2");
    }

    #[test]
    fn child_library_wins() {
        let merged = merge_libraries(&[lib("org.ow2.asm:asm:9.8", None)], &[lib("org.ow2.asm:asm:9.6", None)]);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].name, "org.ow2.asm:asm:9.8");
    }
}
