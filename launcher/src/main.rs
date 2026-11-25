//! Wabbit — a fast, native Minecraft 1.21.11 (Fabric) launcher.
//!
//! One small Rust executable: no browser engine, so it opens instantly. The
//! character on the left is the real skin of the player "eqsual", drawn in 3D
//! with a slow idle sway, cached on disk and re-checked with Mojang whenever a
//! button is clicked.

#![windows_subsystem = "windows"]

mod auth;
mod config;
mod install;
mod launch;
mod mods;
mod net;
mod render3d;
mod skin;
mod theme;

use config::{Account, Settings};
use eframe::egui::{self, Align, Color32, Layout, Pos2, Rect, RichText, Sense, Stroke, TextureHandle, TextureOptions, Vec2};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};
use theme::{CRIMSON, CRIMSON_DIM, CRIMSON_HOVER, LINE, PANEL, SILVER, SURFACE, TEXT};

const WHATS_NEW: &[&str] = &[
	"A real launcher window: sign in with Microsoft, install and play in one click",
	"Java, Minecraft 1.21.11, Fabric and 17 performance mods install themselves",
	"Quick join, multiple accounts, and eqsual on the left",
];

static START: OnceLock<Instant> = OnceLock::new();
/// eqsual's on-screen size, in points.
const FIGURE: Vec2 = Vec2::new(250.0, 400.0);

enum Msg {
	Status(String, Option<f32>),
	SkinChecked(Result<Option<skin::Skin>, String>),
	Face { uuid: String, png: Vec<u8> },
	ModInfo(Vec<mods::Info>),
	Ready(Ready),
	DeviceCode { code: String, url: String },
	SignedIn(Account),
	SignInFailed(String),
	AccountRotated { uuid: String, token: String },
	SessionRejected(String),
	Launched,
	LaunchFailed(String),
	GameExited(Option<i32>),
}

/// What's already installed, for the Ready check card.
#[derive(Clone, Default)]
struct Ready {
	minecraft: bool,
	java: bool,
	mods: usize,
	mods_total: usize,
}

#[derive(PartialEq, Clone, Copy)]
enum Tab {
	Play,
	Mods,
	Settings,
}

struct DeviceDialog {
	code: String,
	url: String,
	cancel: Arc<AtomicBool>,
}

struct ModRow {
	title: String,
	description: String,
	icon: Option<TextureHandle>,
}

struct App {
	settings: Settings,
	tx: Sender<Msg>,
	rx: Receiver<Msg>,
	tab: Tab,

	// eqsual
	skin_meta: skin::SkinMeta,
	skin_img: Option<image::RgbaImage>,
	figure: Option<TextureHandle>,
	last_render: Option<Instant>,
	anim_start: Instant,
	last_skin_check: Option<Instant>,
	skin_checking: bool,
	skin_note: String,

	// accounts
	faces: HashMap<String, TextureHandle>,
	faces_pending: HashSet<String>,

	// mods tab
	mod_rows: HashMap<String, ModRow>,
	mod_info_requested: bool,
	installed: BTreeMap<String, String>,

	ready: Option<Ready>,
	status: String,
	progress: Option<f32>,
	busy: bool,
	game_running: bool,
	signing_in: Option<DeviceDialog>,
	title_bar_done: bool,
	render_ms: f32,

	/// Test hook: WABBIT_SHOT=<file.png> saves a screenshot and exits.
	shot: Option<ShotState>,
}

struct ShotState {
	path: std::path::PathBuf,
	frames: u32,
	requested: bool,
	first_frame_ms: Option<u128>,
}

impl App {
	fn new(cc: &eframe::CreationContext<'_>) -> Self {
		theme::apply(&cc.egui_ctx);
		let (tx, rx) = channel();
		let settings = Settings::load();
		let cached = skin::cached();
		let app = Self {
			status: if settings.current().is_some() { "Ready to play.".into() } else { "Sign in to play.".into() },
			installed: mods::installed(&settings.game_dir),
			settings,
			tx,
			rx,
			tab: match std::env::var("WABBIT_TAB").as_deref() {
				Ok("mods") => Tab::Mods,
				Ok("settings") => Tab::Settings,
				_ => Tab::Play,
			},
			skin_meta: cached.meta.clone(),
			skin_img: image::load_from_memory(&cached.png).ok().map(|i| i.to_rgba8()),
			figure: None,
			last_render: None,
			anim_start: Instant::now(),
			last_skin_check: None,
			skin_checking: false,
			skin_note: String::new(),
			faces: HashMap::new(),
			faces_pending: HashSet::new(),
			mod_rows: HashMap::new(),
			mod_info_requested: false,
			ready: None,
			progress: None,
			busy: false,
			game_running: false,
			signing_in: None,
			title_bar_done: false,
			render_ms: 0.0,
			shot: std::env::var_os("WABBIT_SHOT").map(|p| ShotState { path: p.into(), frames: 0, requested: false, first_frame_ms: None }),
		};
		let mut app = app;
		// Test hook: WABBIT_DIALOG shows the sign-in dialog with a dummy code (no network).
		if std::env::var_os("WABBIT_DIALOG").is_some() {
			app.signing_in = Some(DeviceDialog { code: "ABCD-1234".into(), url: auth::MS_VERIFICATION_URL.into(), cancel: Arc::new(AtomicBool::new(false)) });
		}
		// Background work at startup; the cached skin is already on screen.
		app.check_skin(&cc.egui_ctx, true);
		app.refresh_ready(&cc.egui_ctx);
		app
	}

	fn spawn(&self, ctx: &egui::Context, job: impl FnOnce() -> Option<Msg> + Send + 'static) {
		let (tx, ctx) = (self.tx.clone(), ctx.clone());
		std::thread::spawn(move || {
			if let Some(m) = job() {
				let _ = tx.send(m);
				ctx.request_repaint();
			}
		});
	}

	/// Re-check eqsual's skin with Mojang, at most once per CHECK_EVERY.
	fn check_skin(&mut self, ctx: &egui::Context, force: bool) {
		if self.skin_checking || (!force && self.last_skin_check.is_some_and(|t| t.elapsed() < skin::CHECK_EVERY)) {
			return;
		}
		self.skin_checking = true;
		self.last_skin_check = Some(Instant::now());
		let known = self.skin_meta.url.clone();
		self.spawn(ctx, move || Some(Msg::SkinChecked(skin::check(&known).map_err(|e| format!("{e:#}")))));
	}

	/// Every button goes through here, so each click also re-checks the skin.
	fn clicked(&mut self, ctx: &egui::Context) {
		self.check_skin(ctx, false);
	}

	fn refresh_ready(&self, ctx: &egui::Context) {
		let dir = self.settings.game_dir.clone();
		let disabled = mods::effective_disabled(&self.settings.disabled_mods);
		self.spawn(ctx, move || {
			let v = install::MC_VERSION;
			let installed = mods::installed(&dir);
			Some(Msg::Ready(Ready {
				minecraft: dir.join("versions").join(v).join(format!("{v}.jar")).exists(),
				java: dir.join("runtime").join("java-runtime-delta").join("bin").join("javaw.exe").exists(),
				mods: mods::MODS.iter().filter(|(s, _)| !disabled.iter().any(|d| d == s) && installed.contains_key(*s)).count(),
				mods_total: mods::MODS.len() - disabled.len(),
			}))
		});
	}

	/// Face of a signed-in account: disk cache first, then Mojang.
	fn face(&mut self, ctx: &egui::Context, uuid: &str) -> Option<TextureHandle> {
		if let Some(t) = self.faces.get(uuid) {
			return Some(t.clone());
		}
		let cache = config::data_dir().join("faces").join(format!("{uuid}.png"));
		if let Ok(png) = std::fs::read(&cache) {
			if let Some(t) = face_texture(ctx, uuid, &png) {
				self.faces.insert(uuid.to_string(), t.clone());
				return Some(t);
			}
		}
		if self.faces_pending.insert(uuid.to_string()) {
			let uuid = uuid.to_string();
			self.spawn(ctx, move || {
				let s = skin::fetch(&uuid).ok()?;
				let _ = std::fs::create_dir_all(cache.parent()?);
				let _ = std::fs::write(&cache, &s.png);
				Some(Msg::Face { uuid, png: s.png })
			});
		}
		None
	}

	fn handle_messages(&mut self, ctx: &egui::Context) {
		while let Ok(msg) = self.rx.try_recv() {
			match msg {
				Msg::Status(s, p) => {
					self.status = s;
					self.progress = p;
				}
				Msg::SkinChecked(r) => {
					self.skin_checking = false;
					match r {
						Ok(Some(new)) => {
							let first = self.skin_meta.url.is_empty();
							self.skin_img = image::load_from_memory(&new.png).ok().map(|i| i.to_rgba8());
							self.skin_meta = new.meta;
							self.last_render = None;
							self.skin_note = format!("Skin {} {}", if first { "checked" } else { "updated" }, clock());
						}
						Ok(None) => self.skin_note = format!("Skin checked {}", clock()),
						Err(_) => self.skin_note = "Offline — showing the saved skin".into(),
					}
				}
				Msg::Face { uuid, png } => {
					if let Some(t) = face_texture(ctx, &uuid, &png) {
						self.faces.insert(uuid, t);
					}
				}
				Msg::ModInfo(list) => {
					for i in list {
						let icon = i.icon.as_deref().and_then(|b| image::load_from_memory(b).ok()).map(|img| {
							let img = img.resize_exact(64, 64, image::imageops::FilterType::Triangle).to_rgba8();
							ctx.load_texture(format!("icon-{}", i.slug), egui::ColorImage::from_rgba_unmultiplied([64, 64], img.as_raw()), TextureOptions::LINEAR)
						});
						self.mod_rows.insert(i.slug, ModRow { title: i.title, description: i.description, icon });
					}
				}
				Msg::Ready(r) => self.ready = Some(r),
				Msg::DeviceCode { code, url } => {
					if let Some(d) = &mut self.signing_in {
						d.code = code;
						d.url = url;
					}
				}
				Msg::SignedIn(acc) => {
					self.signing_in = None;
					self.status = format!("Signed in as {}. Ready to play.", acc.name);
					self.settings.upsert_account(acc);
					self.settings.save();
				}
				Msg::SignInFailed(e) => {
					self.signing_in = None;
					self.status = e;
				}
				Msg::AccountRotated { uuid, token } => {
					if let Some(a) = self.settings.accounts.iter_mut().find(|a| a.uuid == uuid) {
						a.refresh_token = token;
						self.settings.save();
					}
				}
				Msg::SessionRejected(e) => {
					self.settings.remove_active();
					self.settings.save();
					self.busy = false;
					self.progress = None;
					self.status = e;
				}
				Msg::Launched => {
					self.busy = false;
					self.game_running = true;
					self.progress = None;
					self.status = "Minecraft is starting…".into();
					self.installed = mods::installed(&self.settings.game_dir);
					self.refresh_ready(ctx);
					if self.settings.close_on_launch {
						ctx.send_viewport_cmd(egui::ViewportCommand::Close);
					}
				}
				Msg::LaunchFailed(e) => {
					self.busy = false;
					self.progress = None;
					self.status = e;
					self.refresh_ready(ctx);
				}
				Msg::GameExited(code) => {
					self.game_running = false;
					self.status = match code {
						Some(0) | None => "Game closed.".into(),
						Some(c) => format!("Game closed (exit code {c}). See Settings → Open game log."),
					};
				}
			}
		}
	}

	fn start_sign_in(&mut self, ctx: &egui::Context) {
		let cancel = Arc::new(AtomicBool::new(false));
		self.signing_in = Some(DeviceDialog { code: "········".into(), url: auth::MS_VERIFICATION_URL.into(), cancel: cancel.clone() });
		let (tx, ctx) = (self.tx.clone(), ctx.clone());
		std::thread::spawn(move || {
			let r = (|| -> anyhow::Result<Account> {
				let dc = auth::request_device_code()?;
				let _ = tx.send(Msg::DeviceCode { code: dc.user_code.clone(), url: dc.verification_uri.clone() });
				ctx.request_repaint();
				let ms = auth::poll(&dc, &cancel)?;
				let session = auth::to_minecraft(&ms.access)?;
				Ok(Account { name: session.name, uuid: session.uuid, refresh_token: ms.refresh })
			})();
			let _ = tx.send(match r {
				Ok(acc) => Msg::SignedIn(acc),
				Err(e) => Msg::SignInFailed(format!("Sign-in failed: {e:#}")),
			});
			ctx.request_repaint();
		});
	}

	fn start_play(&mut self, ctx: &egui::Context, server: Option<String>) {
		let Some(account) = self.settings.current().cloned() else { return };
		self.busy = true;
		self.status = "Signing in…".into();
		self.progress = Some(0.0);
		let settings = self.settings.clone();
		let (tx, ctx) = (self.tx.clone(), ctx.clone());
		std::thread::spawn(move || {
			let send = |m: Msg| {
				let _ = tx.send(m);
				ctx.request_repaint();
			};
			// 1. A fresh Minecraft session from the saved Microsoft refresh token.
			let session = match auth::refresh(&account.refresh_token) {
				Ok(ms) => {
					send(Msg::AccountRotated { uuid: account.uuid.clone(), token: ms.refresh.clone() });
					match auth::to_minecraft(&ms.access) {
						Ok(s) => s,
						Err(e) => return send(Msg::LaunchFailed(format!("{e:#}"))),
					}
				}
				Err(e) => {
					let text = format!("{e:#}");
					return send(if text.contains("expired") { Msg::SessionRejected(text) } else { Msg::LaunchFailed(text) });
				}
			};
			let progress = |s: &str, p: f32| send(Msg::Status(s.to_string(), Some(p)));
			// 2. Game + Fabric + Java, 3. mods, 4. start.
			let result = (|| -> anyhow::Result<std::process::Child> {
				let plan = install::prepare(&settings.game_dir, &|s, p| progress(s, p * 0.85))?;
				let warnings = mods::ensure(&settings.game_dir, &settings.disabled_mods, &|s, p| progress(s, 0.85 + p * 0.13))?;
				if !warnings.is_empty() {
					progress(&format!("Some mods skipped: {}", warnings.join("; ")), 0.98);
				}
				progress("Launching Minecraft…", 1.0);
				launch::start(&plan, &session, &settings.game_dir, settings.ram_mb, server.as_deref())
			})();
			match result {
				Ok(mut child) => {
					send(Msg::Launched);
					let code = child.wait().ok().and_then(|s| s.code());
					send(Msg::GameExited(code));
				}
				Err(e) => send(Msg::LaunchFailed(format!("Couldn't start: {e:#}"))),
			}
		});
	}

	/// Dark Windows title bar matching Midnight Black (Windows 10 20H1+ / 11).
	#[cfg(windows)]
	fn dark_title_bar(&mut self, frame: &eframe::Frame) {
		use raw_window_handle::{HasWindowHandle, RawWindowHandle};
		use windows_sys::Win32::Graphics::Dwm::DwmSetWindowAttribute;
		if self.title_bar_done {
			return;
		}
		let Ok(handle) = frame.window_handle() else { return };
		if let RawWindowHandle::Win32(h) = handle.as_raw() {
			let hwnd = h.hwnd.get() as windows_sys::Win32::Foundation::HWND;
			let dark: i32 = 1;
			let caption: u32 = 0x0014_1110; // COLORREF 0x00BBGGRR for #101114
			let text: u32 = 0x00EC_E8E8; // #E8E8EC
			unsafe {
				DwmSetWindowAttribute(hwnd, 20, &dark as *const i32 as *const _, 4); // DWMWA_USE_IMMERSIVE_DARK_MODE
				DwmSetWindowAttribute(hwnd, 35, &caption as *const u32 as *const _, 4); // DWMWA_CAPTION_COLOR (Win 11)
				DwmSetWindowAttribute(hwnd, 36, &text as *const u32 as *const _, 4); // DWMWA_TEXT_COLOR (Win 11)
			}
		}
		self.title_bar_done = true;
	}

	/// Re-render eqsual at ~30 fps with a slow idle sway.
	fn animate_figure(&mut self, ctx: &egui::Context) {
		let Some(skin) = &self.skin_img else { return };
		if self.last_render.is_some_and(|t| t.elapsed() < Duration::from_millis(33)) {
			return;
		}
		self.last_render = Some(Instant::now());
		let t = self.anim_start.elapsed().as_secs_f32();
		let pose = render3d::Pose {
			yaw: -0.42 + 0.14 * (t * 0.5).sin(),
			pitch: 0.10,
			arm_swing: 0.07 * (t * 1.1).sin(),
			arm_out: 0.05 + 0.025 * (t * 1.1 + 1.3).sin(),
		};
		// Render at the screen's real pixel density so the pixel art stays sharp.
		let ppp = ctx.pixels_per_point();
		let (w, h) = ((FIGURE.x * ppp).round() as u32, (FIGURE.y * ppp).round() as u32);
		let rim = [0x5A, 0x5B, 0x63, 255]; // a quiet rim so the dark outfit reads on the panel
		let t0 = Instant::now();
		let img = render3d::render(skin, self.skin_meta.slim, pose, w, h, 10.5 * ppp, Some(rim));
		self.render_ms = t0.elapsed().as_secs_f32() * 1000.0;
		let color = egui::ColorImage::from_rgba_unmultiplied([img.width() as usize, img.height() as usize], img.as_raw());
		match &mut self.figure {
			Some(tex) => tex.set(color, TextureOptions::NEAREST),
			None => self.figure = Some(ctx.load_texture("eqsual-3d", color, TextureOptions::NEAREST)),
		}
	}
}

fn face_texture(ctx: &egui::Context, uuid: &str, png: &[u8]) -> Option<TextureHandle> {
	let img = skin::face(png).ok()?;
	Some(ctx.load_texture(format!("face-{uuid}"), egui::ColorImage::from_rgba_unmultiplied([8, 8], img.as_raw()), TextureOptions::NEAREST))
}

/// Local time as "HH:MM", for the skin-check note.
fn clock() -> String {
	chrono::Local::now().format("%H:%M").to_string()
}

// ------------------------------------------------------------------ widgets ----

/// A rounded panel.
fn card<R>(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui) -> R) -> R {
	egui::Frame::none()
		.fill(PANEL)
		.stroke(Stroke::new(1.0, LINE))
		.rounding(10.0)
		.inner_margin(16.0)
		.show(ui, |ui| {
			ui.set_width(ui.available_width());
			add(ui)
		})
		.inner
}

fn card_title(ui: &mut egui::Ui, text: &str) {
	ui.label(RichText::new(text.to_uppercase()).size(11.0).color(SILVER));
}

/// An on/off switch, crimson when on.
fn toggle(ui: &mut egui::Ui, on: &mut bool, enabled: bool) -> egui::Response {
	let size = Vec2::new(36.0, 20.0);
	let (rect, mut r) = ui.allocate_exact_size(size, if enabled { Sense::click() } else { Sense::hover() });
	if r.clicked() {
		*on = !*on;
		r.mark_changed();
	}
	let t = ui.ctx().animate_bool_with_time(r.id, *on, 0.12);
	let track = if !enabled { SURFACE } else { lerp_color(Color32::from_rgb(0x3A, 0x3B, 0x42), CRIMSON, t) };
	ui.painter().rect_filled(rect, 10.0, track);
	let x = egui::lerp((rect.left() + 10.0)..=(rect.right() - 10.0), t);
	ui.painter().circle_filled(Pos2::new(x, rect.center().y), 7.0, if enabled { TEXT } else { SILVER });
	if enabled && r.hovered() {
		ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
	}
	r
}

fn lerp_color(a: Color32, b: Color32, t: f32) -> Color32 {
	let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t).round() as u8;
	Color32::from_rgb(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()))
}

/// Small painted tab icons (no icon font needed).
fn tab_icon(painter: &egui::Painter, tab: Tab, c: Pos2, color: Color32) {
	match tab {
		Tab::Play => {
			let pts = vec![c + Vec2::new(-4.0, -5.5), c + Vec2::new(5.5, 0.0), c + Vec2::new(-4.0, 5.5)];
			painter.add(egui::Shape::convex_polygon(pts, color, Stroke::NONE));
		}
		Tab::Mods => {
			for (dx, dy) in [(-3.5, -3.5), (3.5, -3.5), (-3.5, 3.5), (3.5, 3.5)] {
				painter.rect_filled(Rect::from_center_size(c + Vec2::new(dx, dy), Vec2::splat(5.0)), 1.5, color);
			}
		}
		Tab::Settings => {
			painter.circle_stroke(c, 4.5, Stroke::new(2.0, color));
			for k in 0..6 {
				let a = k as f32 * std::f32::consts::TAU / 6.0;
				let d = Vec2::new(a.cos(), a.sin());
				painter.line_segment([c + d * 5.5, c + d * 7.5], Stroke::new(2.0, color));
			}
		}
	}
}

/// A dot for the Ready check: filled silver when ready, crimson ring when not.
fn status_dot(ui: &mut egui::Ui, ok: bool) {
	let (rect, _) = ui.allocate_exact_size(Vec2::splat(12.0), Sense::hover());
	if ok {
		ui.painter().circle_filled(rect.center(), 4.0, SILVER);
	} else {
		ui.painter().circle_stroke(rect.center(), 4.0, Stroke::new(1.5, CRIMSON));
	}
}

impl eframe::App for App {
	fn update(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
		#[cfg(windows)]
		self.dark_title_bar(frame);
		#[cfg(not(windows))]
		let _ = frame;
		self.handle_messages(ctx);
		// The idle sway only runs while the window has focus (and the first frame),
		// so a launcher left open in the background costs almost no CPU.
		let focused = ctx.input(|i| i.focused) || self.figure.is_none() || self.shot.is_some();
		if focused {
			self.animate_figure(ctx);
			ctx.request_repaint_after(Duration::from_millis(33));
		} else {
			ctx.request_repaint_after(Duration::from_millis(500));
		}

		// ---------------- character panel ----------------
		egui::SidePanel::left("character")
			.exact_width(300.0)
			.resizable(false)
			.frame(egui::Frame::none().fill(PANEL).inner_margin(24.0))
			.show(ctx, |ui| {
				ui.with_layout(Layout::top_down(Align::Center), |ui| {
					ui.add_space((ui.available_height() - FIGURE.y - 70.0).max(0.0) / 2.0);
					if let Some(tex) = &self.figure {
						let (rect, _) = ui.allocate_exact_size(FIGURE, Sense::hover());
						// Soft floor shadow, then the figure on top.
						let floor = Pos2::new(rect.center().x, rect.bottom() - 28.0);
						for (rx, ry, a) in [(80.0, 11.0, 40u8), (62.0, 9.0, 55), (42.0, 6.0, 70)] {
							ui.painter().add(egui::Shape::ellipse_filled(floor, Vec2::new(rx, ry), Color32::from_black_alpha(a)));
						}
						ui.painter().image(tex.id(), rect, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
					}
					ui.add_space(6.0);
					ui.label(theme::display(skin::NAME, 26.0).color(TEXT));
					let note = if self.skin_checking { "Checking skin…".to_string() } else { self.skin_note.clone() };
					ui.label(RichText::new(note).size(12.0).color(SILVER));
				});
			});

		// ---------------- main area ----------------
		egui::CentralPanel::default()
			.frame(egui::Frame::none().fill(theme::MIDNIGHT).inner_margin(egui::Margin::symmetric(32.0, 22.0)))
			.show(ctx, |ui| {
				ui.horizontal(|ui| {
					ui.label(theme::display("WABBIT", 40.0).color(TEXT));
					ui.add_space(4.0);
					ui.label(RichText::new(format!("Minecraft {} · Fabric", install::MC_VERSION)).size(14.0).color(SILVER));
				});
				self.tabs(ui, ctx);
				ui.add_space(4.0);
				match self.tab {
					Tab::Play => self.play_tab(ui, ctx),
					Tab::Mods => self.mods_tab(ui, ctx),
					Tab::Settings => self.settings_tab(ui, ctx),
				}
			});

		self.sign_in_dialog(ctx);
		self.shot_hook(ctx);
	}
}

impl App {
	fn tabs(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
		let mut rects = Vec::new();
		ui.horizontal(|ui| {
			for (tab, label) in [(Tab::Play, "Play"), (Tab::Mods, "Mods"), (Tab::Settings, "Settings")] {
				let active = self.tab == tab;
				let (rect, r) = ui.allocate_exact_size(Vec2::new(24.0 + label.len() as f32 * 8.6 + 8.0, 30.0), Sense::click());
				let hover = ui.ctx().animate_bool_with_time(r.id, r.hovered() || active, 0.12);
				let color = lerp_color(SILVER, TEXT, hover);
				tab_icon(ui.painter(), tab, Pos2::new(rect.left() + 8.0, rect.center().y), if active { CRIMSON } else { color });
				ui.painter().text(Pos2::new(rect.left() + 22.0, rect.center().y), egui::Align2::LEFT_CENTER, label, egui::FontId::proportional(15.0), color);
				if r.hovered() {
					ctx.set_cursor_icon(egui::CursorIcon::PointingHand);
				}
				if r.clicked() {
					self.tab = tab;
					self.clicked(ctx);
				}
				rects.push((tab, rect));
				ui.add_space(12.0);
			}
		});
		// Crimson underline that slides to the active tab.
		if let Some((_, rect)) = rects.iter().find(|(t, _)| *t == self.tab) {
			let x0 = ctx.animate_value_with_time(egui::Id::new("tab-x0"), rect.left(), 0.18);
			let x1 = ctx.animate_value_with_time(egui::Id::new("tab-x1"), rect.right(), 0.18);
			let y = rect.bottom() + 2.0;
			let painter = ui.painter();
			painter.line_segment([Pos2::new(ui.min_rect().left(), y + 1.0), Pos2::new(ui.max_rect().right(), y + 1.0)], Stroke::new(1.0, LINE));
			painter.line_segment([Pos2::new(x0, y), Pos2::new(x1, y)], Stroke::new(2.5, CRIMSON));
		}
		ui.add_space(10.0);
	}

	fn play_tab(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
		// ----- account
		let current = self.settings.current().cloned();
		let face = current.as_ref().and_then(|a| self.face(ctx, &a.uuid));
		card(ui, |ui| {
			ui.horizontal(|ui| {
				let (rect, _) = ui.allocate_exact_size(Vec2::splat(40.0), Sense::hover());
				match &face {
					Some(t) => {
						ui.painter().image(t.id(), rect, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
					}
					None => {
						ui.painter().rect_filled(rect, 6.0, SURFACE);
					}
				}
				ui.vertical(|ui| {
					card_title(ui, "Account");
					match &current {
						Some(a) if self.settings.accounts.len() > 1 => {
							let mut pick = self.settings.active;
							egui::ComboBox::from_id_salt("account-pick").selected_text(RichText::new(&a.name).size(16.0).strong()).show_ui(ui, |ui| {
								for (i, acc) in self.settings.accounts.iter().enumerate() {
									ui.selectable_value(&mut pick, i, &acc.name);
								}
							});
							if pick != self.settings.active {
								self.settings.active = pick;
								self.settings.save();
								self.status = format!("Switched to {}.", self.settings.accounts[pick].name);
								self.clicked(ctx);
							}
						}
						Some(a) => {
							ui.label(RichText::new(&a.name).size(17.0).strong());
						}
						None => {
							ui.label(RichText::new("Not signed in").size(17.0).strong());
						}
					}
				});
				ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
					if current.is_some() && ui.add_enabled(!self.busy, egui::Button::new("Sign out")).clicked() {
						self.settings.remove_active();
						self.settings.save();
						self.status = "Signed out.".into();
						self.clicked(ctx);
					}
					let add_label = if current.is_some() { "Add account" } else { "Sign in with Microsoft" };
					if ui.add_enabled(self.signing_in.is_none() && !self.busy, egui::Button::new(add_label)).clicked() {
						self.start_sign_in(ctx);
						self.clicked(ctx);
					}
				});
			});
		});
		ui.add_space(2.0);

		// ----- quick join + ready check
		let can_play = current.is_some() && !self.busy && !self.game_running;
		ui.columns(2, |cols| {
			card(&mut cols[0], |ui| {
				card_title(ui, "Quick join");
				ui.label(RichText::new(&self.settings.quick_server).size(17.0).strong());
				ui.label(RichText::new("Starts the game straight into this server.").size(12.0).color(SILVER));
				if ui.add_enabled(can_play, egui::Button::new(RichText::new("Join server").color(TEXT))).clicked() {
					let server = self.settings.quick_server.clone();
					self.start_play(ctx, Some(server));
					self.clicked(ctx);
				}
			});
			card(&mut cols[1], |ui| {
				card_title(ui, "Ready check");
				let r = self.ready.clone().unwrap_or_default();
				for (ok, text) in [
					(r.minecraft, format!("Minecraft {} + Fabric", install::MC_VERSION)),
					(r.java, "Java 21 (built in)".to_string()),
					(r.mods_total > 0 && r.mods == r.mods_total, format!("Mods {}/{}", r.mods, r.mods_total)),
				] {
					ui.horizontal(|ui| {
						status_dot(ui, ok);
						ui.label(RichText::new(text).size(13.0));
					});
				}
				let note = if r.minecraft && r.java { "Everything is installed." } else { "Missing parts install on Play." };
				ui.label(RichText::new(note).size(12.0).color(SILVER));
			});
		});

		// ----- what's new
		ui.add_space(2.0);
		card(ui, |ui| {
			card_title(ui, &format!("What's new in {}", env!("CARGO_PKG_VERSION")));
			for line in WHATS_NEW {
				ui.horizontal(|ui| {
					let (rect, _) = ui.allocate_exact_size(Vec2::new(10.0, 14.0), Sense::hover());
					ui.painter().circle_filled(rect.center(), 2.5, CRIMSON);
					ui.label(RichText::new(*line).size(13.0).color(TEXT));
				});
			}
		});

		// ----- Play bar at the bottom, progress shown inside it
		let block = 98.0;
		ui.add_space((ui.available_height() - block).max(10.0));
		let label = if self.game_running {
			"PLAYING".to_string()
		} else if self.busy {
			match self.progress {
				Some(p) if p > 0.0 => format!("INSTALLING  {:.0}%", p * 100.0),
				_ => "SIGNING IN…".to_string(),
			}
		} else {
			"PLAY".to_string()
		};
		let (rect, r) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 64.0), Sense::click());
		let hover = ctx.animate_bool_with_time(r.id, r.hovered() && can_play, 0.15);
		let painter = ui.painter();
		if self.busy {
			painter.rect_filled(rect, 10.0, CRIMSON_DIM);
			let p = ctx.animate_value_with_time(egui::Id::new("play-progress"), self.progress.unwrap_or(0.0), 0.25);
			let filled = Rect::from_min_max(rect.min, Pos2::new(rect.left() + rect.width() * p.clamp(0.0, 1.0), rect.bottom()));
			painter.rect_filled(filled, 10.0, CRIMSON);
		} else {
			let fill = if can_play { lerp_color(CRIMSON, CRIMSON_HOVER, hover) } else { CRIMSON_DIM };
			painter.rect_filled(rect, 10.0, fill);
		}
		let text_color = if can_play || self.busy { Color32::WHITE } else { SILVER };
		painter.text(rect.center(), egui::Align2::CENTER_CENTER, label, egui::FontId::new(28.0, egui::FontFamily::Name("display".into())), text_color);
		if r.hovered() && can_play {
			ctx.set_cursor_icon(egui::CursorIcon::PointingHand);
		}
		if r.clicked() {
			if can_play {
				self.start_play(ctx, None);
			}
			self.clicked(ctx);
		}
		ui.add_space(8.0);
		ui.label(RichText::new(&self.status).size(13.0).color(SILVER));
	}

	fn mods_tab(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
		if !self.mod_info_requested {
			self.mod_info_requested = true;
			self.spawn(ctx, || mods::info().ok().map(Msg::ModInfo));
		}
		ui.label(RichText::new(format!("Installed automatically for Minecraft {}. Visual and performance only. Changes apply on next Play.", install::MC_VERSION)).size(13.0).color(SILVER));
		ui.add_space(4.0);
		let disabled_now = mods::effective_disabled(&self.settings.disabled_mods);
		let mut changed: Option<(String, bool)> = None;
		egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
			for (slug, fallback) in mods::MODS {
				let row = self.mod_rows.get(*slug);
				let locked = mods::LOCKED.contains(slug) || (*slug == "cloth-config" && !disabled_now.iter().any(|d| d == "fastquit"));
				let mut on = !disabled_now.iter().any(|d| d == slug);
				card(ui, |ui| {
					ui.horizontal(|ui| {
						let (rect, _) = ui.allocate_exact_size(Vec2::splat(36.0), Sense::hover());
						match row.and_then(|r| r.icon.as_ref()) {
							Some(t) => {
								ui.painter().image(t.id(), rect, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
							}
							None => {
								ui.painter().rect_filled(rect, 8.0, SURFACE);
							}
						}
						// Text gets its own column; the status + switch keep 170 pt on the right.
						let text_w = (ui.available_width() - 170.0).max(120.0);
						ui.allocate_ui_with_layout(Vec2::new(text_w, 40.0), Layout::top_down(Align::Min), |ui| {
							ui.set_width(text_w);
							let title = row.map(|r| r.title.clone()).unwrap_or_else(|| fallback.split(" — ").next().unwrap_or(fallback).to_string());
							ui.add(egui::Label::new(RichText::new(title).size(15.0).strong()).truncate());
							let desc = row.map(|r| r.description.clone()).unwrap_or_else(|| fallback.split(" — ").nth(1).unwrap_or("").to_string());
							ui.add(egui::Label::new(RichText::new(desc).size(12.0).color(SILVER)).truncate());
						});
						ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
							if toggle(ui, &mut on, !locked).changed() {
								changed = Some((slug.to_string(), on));
							}
							let status = if locked && mods::LOCKED.contains(slug) {
								"Always on"
							} else if !on {
								"Off"
							} else if self.installed.contains_key(*slug) {
								"Installed"
							} else {
								"Installs on Play"
							};
							ui.label(RichText::new(status).size(12.0).color(SILVER));
						});
					});
				});
			}
		});
		if let Some((slug, on)) = changed {
			self.settings.disabled_mods.retain(|s| s != &slug);
			if !on {
				self.settings.disabled_mods.push(slug);
			}
			self.settings.save();
			self.refresh_ready(ctx);
			self.clicked(ctx);
		}
	}

	fn settings_tab(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
		egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
			card(ui, |ui| {
				card_title(ui, "Memory");
				let mut ram = self.settings.ram_mb as f32 / 1024.0;
				ui.spacing_mut().slider_width = 360.0;
				if ui.add(egui::Slider::new(&mut ram, 2.0..=16.0).step_by(0.5).suffix(" GB").trailing_fill(true)).changed() {
					self.settings.ram_mb = (ram * 1024.0) as u32;
					self.settings.save();
				}
				ui.label(RichText::new("4 GB is plenty with the performance mods. Don't give Minecraft more than half your PC's RAM.").size(12.0).color(SILVER));
			});
			card(ui, |ui| {
				card_title(ui, "Quick join server");
				let r = ui.add(egui::TextEdit::singleline(&mut self.settings.quick_server).desired_width(320.0).hint_text("donutsmp.net"));
				if r.lost_focus() {
					self.settings.quick_server = self.settings.quick_server.trim().to_string();
					self.settings.save();
				}
			});
			card(ui, |ui| {
				card_title(ui, "Game folder");
				ui.label(RichText::new(self.settings.game_dir.display().to_string()).size(13.0).color(SILVER).monospace());
				ui.horizontal(|ui| {
					if ui.button("Open game folder").clicked() {
						let _ = std::fs::create_dir_all(&self.settings.game_dir);
						let _ = open::that(&self.settings.game_dir);
						self.clicked(ctx);
					}
					if ui.button("Open mods folder").clicked() {
						let dir = self.settings.game_dir.join("mods");
						let _ = std::fs::create_dir_all(&dir);
						let _ = open::that(&dir);
						self.clicked(ctx);
					}
					if ui.button("Open game log").clicked() {
						let _ = open::that(self.settings.game_dir.join("logs").join("wabbit-launch.log"));
						self.clicked(ctx);
					}
				});
			});
			card(ui, |ui| {
				card_title(ui, "Launcher");
				ui.horizontal(|ui| {
					let mut close = self.settings.close_on_launch;
					if toggle(ui, &mut close, true).changed() {
						self.settings.close_on_launch = close;
						self.settings.save();
						self.clicked(ctx);
					}
					ui.label("Close the launcher when the game starts");
				});
				if ui.button("Check eqsual's skin now").clicked() {
					self.check_skin(ctx, true);
				}
			});
		});
	}

	fn sign_in_dialog(&mut self, ctx: &egui::Context) {
		let Some(dialog) = &self.signing_in else { return };
		let (code, url, cancel) = (dialog.code.clone(), dialog.url.clone(), dialog.cancel.clone());
		let mut clicked = false;
		// Dim everything behind the dialog.
		let screen = ctx.screen_rect();
		egui::Area::new(egui::Id::new("dim")).order(egui::Order::Middle).fixed_pos(screen.min).show(ctx, |ui| {
			ui.painter().rect_filled(screen, 0.0, Color32::from_black_alpha(150));
			ui.allocate_rect(screen, Sense::click());
		});
		egui::Window::new("Sign in with a code")
			.title_bar(false)
			.collapsible(false)
			.resizable(false)
			.order(egui::Order::Foreground)
			.anchor(egui::Align2::CENTER_CENTER, Vec2::ZERO)
			.fixed_size(Vec2::new(400.0, 0.0))
			.frame(egui::Frame::none().fill(PANEL).stroke(Stroke::new(1.0, LINE)).rounding(12.0).inner_margin(24.0))
			.show(ctx, |ui| {
				ui.label(theme::display("Sign in with a code", 24.0).color(TEXT));
				ui.label(RichText::new("On any device, open the link and enter this code:").color(SILVER));
				ui.add_space(10.0);
				egui::Frame::none().fill(SURFACE).rounding(8.0).inner_margin(14.0).show(ui, |ui| {
					ui.set_width(ui.available_width());
					ui.vertical_centered(|ui| ui.label(RichText::new(&code).size(32.0).monospace().strong()));
				});
				ui.add_space(10.0);
				ui.horizontal(|ui| {
					let open_btn = egui::Button::new(RichText::new("Open microsoft.com/link").color(Color32::WHITE)).fill(CRIMSON);
					if ui.add(open_btn).clicked() {
						let _ = open::that(&url);
						clicked = true;
					}
					if ui.button("Copy code").clicked() {
						ctx.copy_text(code.clone());
						clicked = true;
					}
					ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
						if ui.button("Cancel").clicked() {
							cancel.store(true, Ordering::Relaxed);
							clicked = true;
						}
					});
				});
				ui.label(RichText::new("Waiting for you to finish signing in…").size(12.0).color(SILVER));
			});
		if clicked {
			self.clicked(ctx);
		}
	}

	/// WABBIT_SHOT: let the window settle, screenshot, write a report, close.
	fn shot_hook(&mut self, ctx: &egui::Context) {
		let Some(shot) = &mut self.shot else { return };
		if shot.first_frame_ms.is_none() {
			shot.first_frame_ms = START.get().map(|s| s.elapsed().as_millis());
		}
		shot.frames += 1;
		ctx.request_repaint();
		let mods_ready = self.tab != Tab::Mods || !self.mod_rows.is_empty() || shot.frames > 400;
		if !shot.requested && shot.frames > 40 && !self.skin_checking && mods_ready {
			shot.requested = true;
			ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot);
		}
		let image = ctx.input(|i| i.raw.events.iter().find_map(|e| match e {
			egui::Event::Screenshot { image, .. } => Some(image.clone()),
			_ => None,
		}));
		if let Some(image) = image {
			let rgba: Vec<u8> = image.pixels.iter().flat_map(|c| c.to_array()).collect();
			if let Some(img) = image::RgbaImage::from_raw(image.size[0] as u32, image.size[1] as u32, rgba) {
				let _ = img.save(&shot.path);
			}
			let report = serde_json::json!({
				"first_frame_ms": shot.first_frame_ms,
				"skin_note": self.skin_note,
				"status": self.status,
				"mod_rows": self.mod_rows.len(),
				"render_ms": self.render_ms,
			});
			let _ = std::fs::write(shot.path.with_extension("json"), report.to_string());
			ctx.send_viewport_cmd(egui::ViewportCommand::Close);
		}
	}
}

/// WABBIT_SELFTEST=<game dir>: install 1.21.11 + Fabric + Java + mods into that
/// folder with the real code, then start the game offline (no window, no account).
fn selftest(dir: std::path::PathBuf) -> anyhow::Result<()> {
	let log = |s: &str, p: f32| eprintln!("[{:>3.0}%] {s}", p * 100.0);
	let t = Instant::now();
	let plan = install::prepare(&dir, &log)?;
	eprintln!("install done in {:.1}s, classpath {} entries, java {}", t.elapsed().as_secs_f32(), plan.classpath.len(), plan.java.display());
	let warnings = mods::ensure(&dir, &[], &log)?;
	eprintln!("mods done, warnings: {warnings:?}");
	let session = auth::Session { name: "WabbitTest".into(), uuid: "00000000000000000000000000000000".into(), access_token: "0".into(), xuid: "0".into() };
	let mut child = launch::start(&plan, &session, &dir, 3072, None)?;
	eprintln!("game started, pid {}", child.id());
	let code = child.wait()?;
	eprintln!("game exited: {code}");
	Ok(())
}

fn main() -> eframe::Result<()> {
	START.get_or_init(Instant::now);
	if let Some(dir) = std::env::var_os("WABBIT_SELFTEST") {
		if let Err(e) = selftest(dir.into()) {
			eprintln!("SELFTEST FAILED: {e:#}");
			std::process::exit(1);
		}
		return Ok(());
	}
	let icon = image::load_from_memory(include_bytes!("../assets/icon.png")).ok().map(|i| {
		let i = i.to_rgba8();
		Arc::new(egui::IconData { width: i.width(), height: i.height(), rgba: i.into_raw() })
	});
	let mut viewport = egui::ViewportBuilder::default()
		.with_title("Wabbit")
		.with_inner_size([1000.0, 680.0])
		.with_min_inner_size([880.0, 600.0]);
	if let Some(icon) = icon {
		viewport = viewport.with_icon(icon);
	}
	eframe::run_native("Wabbit", eframe::NativeOptions { viewport, ..Default::default() }, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
