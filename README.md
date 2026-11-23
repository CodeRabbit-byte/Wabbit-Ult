# Wabbit Ultimate

Client-side Fabric mod for Minecraft **1.21.11**. Installs in the normal `mods` folder. Not a client or launcher.

## Install
1. Install Fabric Loader (0.18+) for 1.21.11 and put **Fabric API** in `mods`.
2. Put `wabbit-ultimate-0.11.0.jar` in `.minecraft/mods`.
3. Launch the game.

## Get the jar
Push to GitHub, open the **Actions** tab, open the latest run, download the `wabbit-ultimate-jar` artifact.
Local build: install JDK 21 and Gradle 9.2.1, then run `gradle build`. The jar is in `build/libs/`.

## Test Stage 1
1. Join a world, press **Right Shift**: the menu opens.
2. Features page: toggle FPS Counter and Memory Usage; the HUD appears top-left immediately.
3. Settings page: switch theme, cycle accent colour.
4. Restart the game: settings are kept.
5. Controls menu: change the keybind under "Wabbit Ultimate".

## Test Stage 2
1. Right Shift > Features > "Edit HUD Layout...".
2. Drag the HUD box with the left mouse button; release to save. Restart the game: it stays put.
3. Opacity -/+ changes the box background; Reset puts it back top-left.
4. Move the HUD, press Save 1, move it elsewhere, press Load 1: it jumps back.

## Test Stage 3
1. Right Shift > Mods > Open Mod Manager: your mods are listed, a few per page (Prev/Next).
2. Disable a harmless mod: it says "Restart required" and the jar in `mods` becomes `.jar.disabled`. Enable it again to undo.
3. Try disabling Fabric API or Wabbit: it must refuse.
4. Join a multiplayer server, open Mod Manager: the server address shows at the top and Flag is clickable.
5. Profiles...: "Save current state as profile", change some mods, then Apply to restore. "Use here" binds it to the current server.
6. Rejoin that server: if your mods differ from the bound profile, a chat message tells you.

## Test the Advent Calendar
1. Right Shift > DonutSMP > Open Advent Calendar.
2. Type a balance such as `2.5B`; the four budget recommendations appear.
3. Press Generate: 24 doors, with budget, calendar total and remaining balance shown. Total is always at or under the budget.
4. Click doors to open them. Opening a Christmas Jackpot triggers a gold flash.
5. Regenerate several times: values and rarities change each time, and door 24 is not always the best.
6. Copy list, then paste somewhere to check. Close and reopen the game: the calendar and opened doors are still there.

## Test the Giveaway Manager
1. Right Shift > DonutSMP > Open Giveaway Manager.
2. Type a balance (e.g. `500M`), then click the option buttons: they cycle through their choices.
3. Generate: a prize list appears, plus budget, pool total and balance after. Total is at or under the budget.
4. Save to history, regenerate, save again: both show under "Previous giveaways". Restart the game: they are still there.
5. Features > Balance HUD ON: your balance appears on the HUD. Open Edit HUD Layout to move it.

## Test Donation Alerts
1. Right Shift > Alerts > Open Donation Alerts.
2. Donor `Steve`, amount `10M`, Trigger alert: a toast slides in at the top with a level-up sound.
3. Try `100M` (banner), `1B` and `10B` (banner + confetti + flash).
4. Change a tier's message or effect, trigger again; close and reopen the game: your edits are saved.
5. Close the menu and trigger nothing: alerts only appear when you trigger them (they also show on the in-game HUD).

## Test YouTube links
1. Copy any YouTube link (a video or a live stream), then Right Shift > YouTube > Open YouTube Links.
2. Press Paste, then Add: the title and channel appear after a second or two.
3. Show puts `YT: <title>` on your HUD. Copy puts the watch link on your clipboard. X removes it.
4. Try a bad link such as `hello`: it should say it is not a YouTube link.
5. Restart the game: the list is still there.

## Test search, alerts, layout copy
1. Right Shift > Dashboard: type `spotify` in the box, click the result: the Spotify screen opens.
2. Features > Performance alerts ON. To see one: in a world, set render distance very high until FPS drops under 30 for ~5 seconds; a toast appears at the top.
3. Features > Edit HUD Layout > Copy layout, move the HUD, then Paste layout: it jumps back.

## Test Cosmetics
1. Join a world, Right Shift > Cosmetics > Open Cosmetics. Pick Halos > Halo > Equip.
2. Close the menu and press F5 (third person): a glowing halo floats above your head. Look up and down, and it follows your head. Sneak: it follows.
3. Try Wings (they flap), the Backpack, Sunglasses, a hat. Use the Color button to recolour.
4. Cape: drop any PNG into `config/wabbit-capes/` (the "Copy folder path" button copies the location), press Use, and look at your back in F5. If the picture is mirrored, press Flip image.
5. Save a loadout, Clear all, then Load it again.
If something looks wrong or the game crashes on joining a world, switch Cosmetics OFF in the menu (or remove the jar) and tell me what you saw.

## Spotify lyrics setup (one time)
Spotify's rules (early 2026) need a Premium account to own the developer app and allow only a few users per app. Check developer.spotify.com for the current rules.
1. developer.spotify.com/dashboard > Create app. Enable "Web API".
2. Add the Redirect URI `http://127.0.0.1:8888/callback` exactly.
3. Settings > User Management: add your Spotify name and email.
4. In game: Right Shift > Media > Spotify Lyrics. Paste the Client ID, press "Copy login link", open it in a browser, log in.
5. The final page will show a "can't connect" error. That is normal. Copy its full address, paste it into the box, press Connect.
6. Play a song in Spotify. The title and lyrics appear on the HUD. If no lyrics show, that song has no synced lyrics in LRCLIB.

## Backup & Sync
Right Shift > Settings > Backup & Sync. "Create backup now" copies your settings into `config/wabbit-backups/`.
To share settings between PCs, type the path of a folder your cloud service syncs (for example `C:\Users\you\Dropbox\Wabbit`), press Push on PC 1, then Pull on PC 2.

## Limitations
- Cosmetics show on your own player only. Spotify needs setup and a Premium developer account. There is no Wabbit account/server; sync uses your own cloud folder.
- YouTube links show title and channel only. No video playback, no live/offline detection, no scores.
- Donation alerts are manual only; nothing is read from chat or a donation site.
- Prizes are money amounts. There is no server API, so you hand them out yourself. The balance is typed in by you.
- No HUD resizing or layout import/export yet.
- Mods can't be switched live. Every change needs a restart, so profiles never apply by themselves.
- On Windows a running game may lock jars; if a rename fails the manager says so.
- Restricted-mod flags are your own list. Wabbit knows no server's real rules and never tries to bypass anti-cheat.
See `CHANGELOG.md`.
