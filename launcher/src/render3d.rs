//! A small software 3D renderer for a Minecraft skin: the real player model
//! (head, body, arms, legs, plus the outer layer), every skin texel drawn as a
//! shaded quad, orthographic camera, painter's ordering. Fast enough to redraw
//! an idle sway at 30 fps on the CPU.

use image::{Rgba, RgbaImage};

/// Camera and limb angles, in radians.
#[derive(Clone, Copy)]
pub struct Pose {
	pub yaw: f32,
	pub pitch: f32,
	/// Forward/back swing of the arms (opposite directions), around the shoulder.
	pub arm_swing: f32,
	/// Small sideways lift of both arms, for a breathing feel.
	pub arm_out: f32,
}

#[derive(Clone, Copy)]
struct V3 {
	x: f32,
	y: f32,
	z: f32,
}

fn v3(x: f32, y: f32, z: f32) -> V3 {
	V3 { x, y, z }
}

/// One cuboid of the model.
struct Part {
	center: V3,
	size: V3,
	/// Texture origin in the skin for the standard box UV layout.
	uv: (u32, u32),
	/// Overlay boxes grow slightly so they sit on top of the base layer.
	inflate: f32,
	/// Limb rotation: pivot point, angle around X (swing) and around Z (outward).
	pivot: Option<(V3, f32, f32)>,
}

const LIGHT: V3 = V3 { x: -0.35, y: 0.75, z: 0.56 };

fn rotate_limb(p: V3, pivot: V3, ax: f32, az: f32) -> V3 {
	let (mut x, mut y, mut z) = (p.x - pivot.x, p.y - pivot.y, p.z - pivot.z);
	// around X
	let (s, c) = ax.sin_cos();
	let (y1, z1) = (y * c - z * s, y * s + z * c);
	y = y1;
	z = z1;
	// around Z
	let (s, c) = az.sin_cos();
	let (x1, y1) = (x * c - y * s, x * s + y * c);
	x = x1;
	y = y1;
	v3(x + pivot.x, y + pivot.y, z + pivot.z)
}

fn view(p: V3, pose: &Pose) -> V3 {
	let (s, c) = pose.yaw.sin_cos();
	let (x, z) = (p.x * c + p.z * s, -p.x * s + p.z * c);
	let (s, c) = pose.pitch.sin_cos();
	let (y, z) = (p.y * c - z * s, p.y * s + z * c);
	v3(x, y, z)
}

/// One face of a part: its texture rectangle and a function giving the model-space
/// position of texel corner (i, j) on it.
struct Face {
	tex: (u32, u32, u32, u32),
	corner: Box<dyn Fn(f32, f32) -> V3>,
	normal: V3,
}

fn faces(part: &Part) -> Vec<Face> {
	let (u, v) = part.uv;
	let (w, h, d) = (part.size.x as u32, part.size.y as u32, part.size.z as u32);
	let g = part.inflate;
	let (sx, sy, sz) = (part.size.x + 2.0 * g, part.size.y + 2.0 * g, part.size.z + 2.0 * g);
	let min = v3(part.center.x - sx / 2.0, part.center.y - sy / 2.0, part.center.z - sz / 2.0);
	let max = v3(part.center.x + sx / 2.0, part.center.y + sy / 2.0, part.center.z + sz / 2.0);
	// Texel steps along each axis (the overlay is stretched by the inflation).
	let (fx, fy, fz) = (sx / w as f32, sy / h as f32, sz / d as f32);
	vec![
		// front (+z): texture left = player's right = -x
		Face { tex: (u + d, v + d, w, h), corner: Box::new(move |i, j| v3(min.x + i * fx, max.y - j * fy, max.z)), normal: v3(0.0, 0.0, 1.0) },
		// back (-z)
		Face { tex: (u + 2 * d + w, v + d, w, h), corner: Box::new(move |i, j| v3(max.x - i * fx, max.y - j * fy, min.z)), normal: v3(0.0, 0.0, -1.0) },
		// player's right side (-x): texture left edge is the back
		Face { tex: (u, v + d, d, h), corner: Box::new(move |i, j| v3(min.x, max.y - j * fy, min.z + i * fz)), normal: v3(-1.0, 0.0, 0.0) },
		// player's left side (+x): texture left edge is the front
		Face { tex: (u + d + w, v + d, d, h), corner: Box::new(move |i, j| v3(max.x, max.y - j * fy, max.z - i * fz)), normal: v3(1.0, 0.0, 0.0) },
		// top (+y): texture bottom edge meets the front
		Face { tex: (u + d, v, w, d), corner: Box::new(move |i, j| v3(min.x + i * fx, max.y, min.z + j * fz)), normal: v3(0.0, 1.0, 0.0) },
		// bottom (-y)
		Face { tex: (u + d + w, v, w, d), corner: Box::new(move |i, j| v3(min.x + i * fx, min.y, max.z - j * fz)), normal: v3(0.0, -1.0, 0.0) },
	]
}

fn model(slim: bool, legacy: bool, pose: &Pose) -> Vec<Part> {
	let aw = if slim { 3.0 } else { 4.0 };
	let shoulder_r = v3(-4.0 - aw / 2.0, 23.0, 0.0);
	let shoulder_l = v3(4.0 + aw / 2.0, 23.0, 0.0);
	let arm_r = Some((shoulder_r, pose.arm_swing, -pose.arm_out));
	let arm_l = Some((shoulder_l, -pose.arm_swing, pose.arm_out));
	// 64x32 (pre-1.8) skins have no separate left limbs or outer body layers.
	let (l_arm_uv, l_leg_uv) = if legacy { ((40, 16), (0, 16)) } else { ((32, 48), (16, 48)) };
	let mut parts = vec![
		Part { center: v3(0.0, 28.0, 0.0), size: v3(8.0, 8.0, 8.0), uv: (0, 0), inflate: 0.0, pivot: None },
		Part { center: v3(0.0, 18.0, 0.0), size: v3(8.0, 12.0, 4.0), uv: (16, 16), inflate: 0.0, pivot: None },
		Part { center: v3(-4.0 - aw / 2.0, 18.0, 0.0), size: v3(aw, 12.0, 4.0), uv: (40, 16), inflate: 0.0, pivot: arm_r },
		Part { center: v3(4.0 + aw / 2.0, 18.0, 0.0), size: v3(aw, 12.0, 4.0), uv: l_arm_uv, inflate: 0.0, pivot: arm_l },
		Part { center: v3(-2.0, 6.0, 0.0), size: v3(4.0, 12.0, 4.0), uv: (0, 16), inflate: 0.0, pivot: None },
		Part { center: v3(2.0, 6.0, 0.0), size: v3(4.0, 12.0, 4.0), uv: l_leg_uv, inflate: 0.0, pivot: None },
		Part { center: v3(0.0, 28.0, 0.0), size: v3(8.0, 8.0, 8.0), uv: (32, 0), inflate: 0.5, pivot: None },
	];
	if !legacy {
		parts.extend([
			Part { center: v3(0.0, 18.0, 0.0), size: v3(8.0, 12.0, 4.0), uv: (16, 32), inflate: 0.25, pivot: None },
			Part { center: v3(-4.0 - aw / 2.0, 18.0, 0.0), size: v3(aw, 12.0, 4.0), uv: (40, 32), inflate: 0.25, pivot: arm_r },
			Part { center: v3(4.0 + aw / 2.0, 18.0, 0.0), size: v3(aw, 12.0, 4.0), uv: (48, 48), inflate: 0.25, pivot: arm_l },
			Part { center: v3(-2.0, 6.0, 0.0), size: v3(4.0, 12.0, 4.0), uv: (0, 32), inflate: 0.25, pivot: None },
			Part { center: v3(2.0, 6.0, 0.0), size: v3(4.0, 12.0, 4.0), uv: (0, 48), inflate: 0.25, pivot: None },
		]);
	}
	parts
}

/// Render the skin into a `width` x `height` image, `scale` pixels per skin texel,
/// feet near the bottom. `outline` draws a thin border around the silhouette.
pub fn render(skin: &RgbaImage, slim: bool, pose: Pose, width: u32, height: u32, scale: f32, outline: Option<[u8; 4]>) -> RgbaImage {
	let legacy = skin.height() == 32;
	let mut out = RgbaImage::new(width, height);
	let (cx, cy) = (width as f32 / 2.0, height as f32 - 3.0 * scale);
	let project = |p: V3| (cx + p.x * scale, cy - (p.y - 1.0) * scale);

	// Collect visible faces with their depth, farthest first.
	struct Drawn {
		depth: f32,
		part: usize,
		face: usize,
		shade: f32,
	}
	let parts = model(slim, legacy, &pose);
	let all: Vec<Vec<Face>> = parts.iter().map(faces).collect();
	let place = |part: &Part, p: V3| -> V3 {
		let p = match part.pivot {
			Some((pivot, ax, az)) => rotate_limb(p, pivot, ax, az),
			None => p,
		};
		view(p, &pose)
	};
	let mut order = Vec::new();
	for (pi, part) in parts.iter().enumerate() {
		for (fi, face) in all[pi].iter().enumerate() {
			// Normal after limb + camera rotation (rotate two points and subtract).
			let o = place(part, part.center);
			let n = place(part, v3(part.center.x + face.normal.x, part.center.y + face.normal.y, part.center.z + face.normal.z));
			let normal = v3(n.x - o.x, n.y - o.y, n.z - o.z);
			if normal.z <= 0.001 {
				continue; // facing away
			}
			let (tw, th) = (face.tex.2 as f32, face.tex.3 as f32);
			let c = place(part, (face.corner)(tw / 2.0, th / 2.0));
			let lit = (normal.x * LIGHT.x + normal.y * LIGHT.y + normal.z * LIGHT.z).max(0.0);
			order.push(Drawn { depth: c.z + part.inflate, part: pi, face: fi, shade: 0.52 + 0.48 * lit });
		}
	}
	order.sort_by(|a, b| a.depth.partial_cmp(&b.depth).unwrap_or(std::cmp::Ordering::Equal));

	for d in &order {
		let part = &parts[d.part];
		let face = &all[d.part][d.face];
		let (tx, ty, tw, th) = face.tex;
		for j in 0..th {
			for i in 0..tw {
				let (sx, sy) = (tx + i, ty + j);
				if sx >= skin.width() || sy >= skin.height() {
					continue;
				}
				let px = skin.get_pixel(sx, sy);
				if px[3] < 128 {
					continue;
				}
				let q = [
					project(place(part, (face.corner)(i as f32, j as f32))),
					project(place(part, (face.corner)(i as f32 + 1.0, j as f32))),
					project(place(part, (face.corner)(i as f32 + 1.0, j as f32 + 1.0))),
					project(place(part, (face.corner)(i as f32, j as f32 + 1.0))),
				];
				let s = |c: u8| ((c as f32) * d.shade).min(255.0) as u8;
				fill_quad(&mut out, q, Rgba([s(px[0]), s(px[1]), s(px[2]), 255]));
			}
		}
	}

	if let Some(color) = outline {
		let src = out.clone();
		let solid = |x: i64, y: i64| x >= 0 && y >= 0 && (x as u32) < width && (y as u32) < height && src.get_pixel(x as u32, y as u32)[3] > 0;
		let r = (scale / 4.0).round().max(1.0) as i64;
		for y in 0..height as i64 {
			for x in 0..width as i64 {
				if solid(x, y) {
					continue;
				}
				let near = (-r..=r).any(|dy| (-r..=r).any(|dx| dx * dx + dy * dy <= r * r && solid(x + dx, y + dy)));
				if near {
					out.put_pixel(x as u32, y as u32, Rgba(color));
				}
			}
		}
	}
	out
}

/// Fill a convex quad (screen space), slightly grown so neighbouring texels meet without seams.
fn fill_quad(img: &mut RgbaImage, q: [(f32, f32); 4], color: Rgba<u8>) {
	let (cx, cy) = ((q[0].0 + q[1].0 + q[2].0 + q[3].0) / 4.0, (q[0].1 + q[1].1 + q[2].1 + q[3].1) / 4.0);
	let grow = |p: (f32, f32)| {
		let (dx, dy) = (p.0 - cx, p.1 - cy);
		let len = (dx * dx + dy * dy).sqrt().max(1e-3);
		(p.0 + dx / len * 0.45, p.1 + dy / len * 0.45)
	};
	let q = [grow(q[0]), grow(q[1]), grow(q[2]), grow(q[3])];
	let minx = q.iter().map(|p| p.0).fold(f32::MAX, f32::min).floor().max(0.0) as i64;
	let maxx = q.iter().map(|p| p.0).fold(f32::MIN, f32::max).ceil().min(img.width() as f32 - 1.0) as i64;
	let miny = q.iter().map(|p| p.1).fold(f32::MAX, f32::min).floor().max(0.0) as i64;
	let maxy = q.iter().map(|p| p.1).fold(f32::MIN, f32::max).ceil().min(img.height() as f32 - 1.0) as i64;
	let edge = |a: (f32, f32), b: (f32, f32), p: (f32, f32)| (b.0 - a.0) * (p.1 - a.1) - (b.1 - a.1) * (p.0 - a.0);
	for y in miny..=maxy {
		for x in minx..=maxx {
			let p = (x as f32 + 0.5, y as f32 + 0.5);
			let e = [edge(q[0], q[1], p), edge(q[1], q[2], p), edge(q[2], q[3], p), edge(q[3], q[0], p)];
			if e.iter().all(|&v| v >= 0.0) || e.iter().all(|&v| v <= 0.0) {
				img.put_pixel(x as u32, y as u32, color);
			}
		}
	}
}
