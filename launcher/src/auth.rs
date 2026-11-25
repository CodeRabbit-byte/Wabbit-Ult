//! Microsoft sign-in with a code (live.com "remote connect" device flow), then
//! Xbox Live → XSTS → Minecraft services. Same flow as Pika Client's launcher.
//! No passwords are ever seen: Microsoft's own page handles them.

use crate::net;
use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

const MS_CLIENT_ID: &str = "00000000402b5328";
const MS_DEVICE_CODE_URL: &str = "https://login.live.com/oauth20_connect.srf";
const MS_TOKEN_URL: &str = "https://login.live.com/oauth20_token.srf";
const MS_SCOPE: &str = "XboxLive.signin offline_access";
pub const MS_VERIFICATION_URL: &str = "https://login.live.com/oauth20_remoteconnect.srf";

pub struct DeviceCode {
	pub user_code: String,
	pub verification_uri: String,
	device_code: String,
	interval: u64,
	expires_in: u64,
}

pub struct MsToken {
	pub access: String,
	pub refresh: String,
}

/// What the game needs to start signed in.
pub struct Session {
	pub name: String,
	pub uuid: String,
	pub access_token: String,
	pub xuid: String,
}

/// POST a form; non-2xx responses still return their JSON body (the token
/// endpoint answers "authorization_pending" with HTTP 400).
fn post_form(url: &str, form: &[(&str, &str)]) -> Result<(u16, Value)> {
	match net::agent().post(url).send_form(form) {
		Ok(r) => Ok((r.status(), r.into_json().unwrap_or(Value::Null))),
		Err(ureq::Error::Status(code, r)) => Ok((code, r.into_json().unwrap_or(Value::Null))),
		Err(e) => Err(e).with_context(|| format!("POST {url}")),
	}
}

fn post_json(url: &str, body: Value) -> Result<(u16, Value)> {
	let req = net::agent().post(url).set("Accept", "application/json");
	match req.send_json(body) {
		Ok(r) => Ok((r.status(), r.into_json().unwrap_or(Value::Null))),
		Err(ureq::Error::Status(code, r)) => Ok((code, r.into_json().unwrap_or(Value::Null))),
		Err(e) => Err(e).with_context(|| format!("POST {url}")),
	}
}

pub fn request_device_code() -> Result<DeviceCode> {
	let (status, j) = post_form(
		MS_DEVICE_CODE_URL,
		&[("client_id", MS_CLIENT_ID), ("scope", MS_SCOPE), ("response_type", "device_code")],
	)?;
	if status >= 400 {
		bail!("Could not start sign-in ({status}): {}", j["error_description"].as_str().unwrap_or("unknown error"));
	}
	Ok(DeviceCode {
		user_code: j["user_code"].as_str().context("no user_code")?.to_string(),
		device_code: j["device_code"].as_str().context("no device_code")?.to_string(),
		verification_uri: j["verification_uri"].as_str().unwrap_or(MS_VERIFICATION_URL).to_string(),
		interval: j["interval"].as_u64().unwrap_or(5),
		expires_in: j["expires_in"].as_u64().unwrap_or(900),
	})
}

/// Wait for the user to finish on Microsoft's page. `cancel` aborts.
pub fn poll(device: &DeviceCode, cancel: &AtomicBool) -> Result<MsToken> {
	let mut interval = Duration::from_secs(device.interval.max(1));
	let deadline = Instant::now() + Duration::from_secs(device.expires_in);
	while Instant::now() < deadline {
		// Sleep in small steps so Cancel is instant.
		let wake = Instant::now() + interval;
		while Instant::now() < wake {
			if cancel.load(Ordering::Relaxed) {
				bail!("Sign-in cancelled.");
			}
			std::thread::sleep(Duration::from_millis(200));
		}
		let (_, j) = post_form(
			MS_TOKEN_URL,
			&[
				("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
				("client_id", MS_CLIENT_ID),
				("device_code", &device.device_code),
			],
		)?;
		if let Some(access) = j["access_token"].as_str() {
			return Ok(MsToken { access: access.into(), refresh: j["refresh_token"].as_str().unwrap_or_default().into() });
		}
		match j["error"].as_str() {
			Some("authorization_pending") | None => {}
			Some("slow_down") => interval += Duration::from_secs(5),
			Some("expired_token") => bail!("The code expired. Please try again."),
			Some("authorization_declined") => bail!("Sign-in was declined."),
			Some(other) => bail!("{}", j["error_description"].as_str().unwrap_or(other)),
		}
	}
	bail!("Timed out waiting for sign-in.")
}

/// A fresh Microsoft token from the saved refresh token (tokens rotate).
pub fn refresh(refresh_token: &str) -> Result<MsToken> {
	let (status, j) = post_form(
		MS_TOKEN_URL,
		&[
			("grant_type", "refresh_token"),
			("client_id", MS_CLIENT_ID),
			("refresh_token", refresh_token),
			("scope", MS_SCOPE),
		],
	)?;
	match j["access_token"].as_str() {
		Some(access) => Ok(MsToken {
			access: access.into(),
			refresh: j["refresh_token"].as_str().unwrap_or(refresh_token).into(),
		}),
		None if (400..500).contains(&status) => bail!("Your session expired — please sign in again."),
		None => bail!("Microsoft sign-in is unavailable right now ({status}). Try again in a moment."),
	}
}

/// Microsoft token → Minecraft session (Xbox Live, XSTS, Minecraft services, profile).
pub fn to_minecraft(ms_access: &str) -> Result<Session> {
	let (status, xbl) = post_json(
		"https://user.auth.xboxlive.com/user/authenticate",
		json!({
			"Properties": { "AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access}") },
			"RelyingParty": "http://auth.xboxlive.com",
			"TokenType": "JWT"
		}),
	)?;
	let xbl_token = xbl["Token"].as_str().with_context(|| format!("Xbox Live sign-in failed ({status})"))?.to_string();

	// XSTS reports account problems as HTTP 401 with an XErr code in the body.
	let (status, xsts) = post_json(
		"https://xsts.auth.xboxlive.com/xsts/authorize",
		json!({
			"Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
			"RelyingParty": "rp://api.minecraftservices.com/",
			"TokenType": "JWT"
		}),
	)?;
	if let Some(xerr) = xsts["XErr"].as_u64() {
		bail!("This Microsoft account has no Xbox profile or can't play Minecraft (XErr {xerr}).");
	}
	let xsts_token = xsts["Token"].as_str().with_context(|| format!("Xbox sign-in failed ({status})"))?;
	let uhs = xsts["DisplayClaims"]["xui"][0]["uhs"].as_str().context("no user hash")?;
	let xuid = xsts["DisplayClaims"]["xui"][0]["xid"].as_str().unwrap_or("0").to_string();

	let (status, mc) = post_json(
		"https://api.minecraftservices.com/authentication/login_with_xbox",
		json!({ "identityToken": format!("XBL3.0 x={uhs};{xsts_token}") }),
	)?;
	let access_token = mc["access_token"].as_str().with_context(|| format!("Minecraft sign-in failed ({status})"))?.to_string();

	let profile: Value = match net::agent()
		.get("https://api.minecraftservices.com/minecraft/profile")
		.set("Authorization", &format!("Bearer {access_token}"))
		.call()
	{
		Ok(r) => r.into_json()?,
		Err(ureq::Error::Status(404, _)) => bail!("Signed in, but this account doesn't own Minecraft: Java Edition."),
		Err(e) => return Err(e).context("profile lookup"),
	};
	Ok(Session {
		name: profile["name"].as_str().context("no profile name")?.to_string(),
		uuid: profile["id"].as_str().context("no profile id")?.to_string(),
		access_token,
		xuid,
	})
}
