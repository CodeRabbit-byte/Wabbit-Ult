use crate::model::Rule;

pub fn current_os() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

/// Mojang rule semantics: no rules = allowed; otherwise the last matching rule decides.
pub fn rules_allow(rules: &[Rule]) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for r in rules {
        if rule_matches(r) {
            allowed = r.action == "allow";
        }
    }
    allowed
}

fn rule_matches(r: &Rule) -> bool {
    // We enable no optional launcher features (demo mode, custom resolution, quick play).
    if r.features.is_some() {
        return false;
    }
    if let Some(os) = &r.os {
        if let Some(name) = &os.name {
            if name != current_os() {
                return false;
            }
        }
        if let Some(arch) = &os.arch {
            if arch == "x86" && !cfg!(target_arch = "x86") {
                return false;
            }
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{OsRule, Rule};

    fn rule(action: &str, os: Option<&str>) -> Rule {
        Rule {
            action: action.into(),
            os: os.map(|n| OsRule { name: Some(n.into()), arch: None }),
            features: None,
        }
    }

    #[test]
    fn empty_rules_allow() {
        assert!(rules_allow(&[]));
    }

    #[test]
    fn allow_then_disallow_other_os() {
        let other = if current_os() == "windows" { "linux" } else { "windows" };
        assert!(rules_allow(&[rule("allow", None), rule("disallow", Some(other))]));
        assert!(!rules_allow(&[rule("allow", None), rule("disallow", Some(current_os()))]));
    }

    #[test]
    fn os_specific_allow() {
        assert!(rules_allow(&[rule("allow", Some(current_os()))]));
        let other = if current_os() == "windows" { "linux" } else { "windows" };
        assert!(!rules_allow(&[rule("allow", Some(other))]));
    }
}
