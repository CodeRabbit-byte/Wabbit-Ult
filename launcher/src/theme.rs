//! Wabbit's palette and egui styling.
//!
//! Midnight Black is the canvas, Steel Silver carries secondary text and lines,
//! Crimson Red is the one accent (the Play button, progress, the active tab).

use eframe::egui::{self, Color32, FontData, FontDefinitions, FontFamily, Rounding, Stroke};

pub const MIDNIGHT: Color32 = Color32::from_rgb(0x10, 0x11, 0x14); // main background
pub const PANEL: Color32 = Color32::from_rgb(0x17, 0x18, 0x1C); // raised surfaces
pub const SURFACE: Color32 = Color32::from_rgb(0x1F, 0x20, 0x25); // inputs, inactive buttons
pub const LINE: Color32 = Color32::from_rgb(0x2A, 0x2B, 0x31); // hairlines
pub const SILVER: Color32 = Color32::from_rgb(0xA1, 0xA1, 0xAA); // secondary text / strokes
pub const TEXT: Color32 = Color32::from_rgb(0xE8, 0xE8, 0xEC); // primary text
/// Crimson Red #E32636 — the one accent.
pub const CRIMSON: Color32 = Color32::from_rgb(0xE3, 0x26, 0x36);
pub const CRIMSON_HOVER: Color32 = Color32::from_rgb(0xEE, 0x45, 0x53);
pub const CRIMSON_DIM: Color32 = Color32::from_rgb(0x5A, 0x14, 0x1B);

/// Apply the palette and, when present, Windows' Bahnschrift (headings) and
/// Segoe UI (body) — read from C:\Windows\Fonts at startup, never bundled.
pub fn apply(ctx: &egui::Context) {
	let mut fonts = FontDefinitions::default();
	let load = |file: &str| std::fs::read(format!("C:\\Windows\\Fonts\\{file}")).ok();
	if let Some(body) = load("segoeui.ttf") {
		fonts.font_data.insert("body".into(), FontData::from_owned(body));
		fonts.families.entry(FontFamily::Proportional).or_default().insert(0, "body".into());
	}
	let display = load("bahnschrift.ttf").or_else(|| load("segoeuib.ttf"));
	let mut display_family = fonts.families.get(&FontFamily::Proportional).cloned().unwrap_or_default();
	if let Some(d) = display {
		fonts.font_data.insert("display".into(), FontData::from_owned(d));
		display_family.insert(0, "display".into());
	}
	fonts.families.insert(FontFamily::Name("display".into()), display_family);
	ctx.set_fonts(fonts);

	let mut v = egui::Visuals::dark();
	v.panel_fill = MIDNIGHT;
	v.window_fill = PANEL;
	v.extreme_bg_color = SURFACE;
	v.faint_bg_color = PANEL;
	v.override_text_color = Some(TEXT);
	v.window_stroke = Stroke::new(1.0, LINE);
	v.selection.bg_fill = CRIMSON;
	v.selection.stroke = Stroke::new(1.0, TEXT);
	v.hyperlink_color = CRIMSON_HOVER;

	let round = Rounding::same(6.0);
	v.widgets.noninteractive.bg_fill = PANEL;
	v.widgets.noninteractive.weak_bg_fill = PANEL;
	v.widgets.noninteractive.bg_stroke = Stroke::new(1.0, LINE);
	v.widgets.noninteractive.fg_stroke = Stroke::new(1.0, SILVER);
	for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.open] {
		w.rounding = round;
		w.bg_fill = SURFACE;
		w.weak_bg_fill = SURFACE;
		w.bg_stroke = Stroke::new(1.0, LINE);
		w.fg_stroke = Stroke::new(1.0, TEXT);
		w.expansion = 0.0;
	}
	v.widgets.hovered.bg_stroke = Stroke::new(1.0, SILVER);
	v.widgets.active.bg_stroke = Stroke::new(1.0, CRIMSON);
	// Always dark, even when Windows is in light mode: egui would otherwise
	// switch the widgets to its light theme and leave the panels black.
	ctx.set_theme(egui::ThemePreference::Dark);
	ctx.set_visuals_of(egui::Theme::Dark, v.clone());
	ctx.set_visuals_of(egui::Theme::Light, v);

	ctx.all_styles_mut(|s| {
		s.spacing.item_spacing = egui::vec2(10.0, 10.0);
		s.spacing.button_padding = egui::vec2(14.0, 7.0);
	});
}

/// Headline text in the display face.
pub fn display(text: impl Into<String>, size: f32) -> egui::RichText {
	egui::RichText::new(text).family(FontFamily::Name("display".into())).size(size)
}
