// Embeds eqsual's face (the launcher's character) as the .exe icon, plus version info, on Windows builds.
fn main() {
	println!("cargo:rerun-if-changed=assets/icon.ico");
	if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
		let mut res = winresource::WindowsResource::new();
		res.set_icon("assets/icon.ico")
			.set("ProductName", "Wabbit")
			.set("FileDescription", "Wabbit — Minecraft 1.21.11 launcher");
		if let Err(e) = res.compile() {
			// A missing resource compiler shouldn't stop the build; the exe just has no icon.
			println!("cargo:warning=could not embed the icon: {e}");
		}
	}
}
