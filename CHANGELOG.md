# Changelog

## 0.11.0 - Better cosmetics and bug fixes (startup tested, cosmetics not yet confirmed in-game)
- Capes now use the real Minecraft cape model and your full-resolution picture, so they swing, flap and lean exactly like a Mojang cape. HD cape files (up to 1024x512) keep their detail
- A Wabbit cape replaces your own Mojang or launcher cape instead of drawing on top of it. Capes and wings hide while you wear an elytra, the way vanilla capes do
- Cosmetics are attached to the real head and body, so they now show and follow you while swimming, crawling, flying, riding and sleeping (before, they disappeared in every pose except standing and sneaking)
- Head items sit on top of the skin's hat layer and on top of helmets; back items and capes sit on top of chestplates instead of inside them
- Fixed: other players standing within 2 blocks of you were drawn wearing your cosmetics
- New items: Wizard Hat, Bunny Ears, Cat Ears, Devil Horns, Butterfly Wings
- The menu preview shows your cape, fits tall hats inside the panel, and no longer overlaps text on small screens. The cape help text no longer covers the bottom buttons; tabs and buttons shrink to fit short or narrow windows
- Fixed: a typo in `config/wabbit-ultimate.json` wiped every setting. The broken file is now kept as `wabbit-ultimate.json.broken-<time>` and settings are saved atomically
- Fixed: restoring a backup brought back an old Spotify login and sync folder
- Fixed: a mod present as both `x.jar` and `x.jar.disabled` showed twice and could never be toggled
- Fixed: a balance typed in the Advent or Giveaway menu was lost when the menu was closed with Esc

## 0.10.0 - Search, performance alerts, HUD layout copy/paste (not yet confirmed in-game)
- Dashboard search box: type "spotify", "hat", "backup"... and click a result to jump straight to it
- Performance alerts (Features > Performance alerts): a toast when FPS stays under 30 for 5 seconds, or memory stays at 90% for 10 seconds. Only while you are in a world with no menu open, with long cool-downs so it never spams. Limits can be changed in `config/wabbit-ultimate.json` (`perfLowFps`, `perfHighMemPct`)
- HUD editor: Copy layout / Paste layout (one line of text) to move a layout between PCs or share it
- Not done: HUD resizing and colours, a notification history page, more keybinds
- Earlier versions unchanged

## 0.9.0 - Cosmetics (not yet confirmed in-game; this is the riskiest build so far)
- Right Shift > Cosmetics > "Open Cosmetics...". 16 built-in cosmetics, each made of coloured blocks:
  - Hats: Top Hat, Santa Hat, Party Hat. Crowns: Royal Crown, Tiara. Halos: Halo, Double Halo (glow, gently bob)
  - Masks: Bandit Mask, White Mask. Glasses: Sunglasses, Neon Shades (glow)
  - Wings: Angel Wings, Dragon Wings (flap). Back items: Backpack, Santa Sack, Jetpack (glowing flames)
- One item per category. A Color button recolours the equipped item (11 colours)
- Favorites tab, 3 saved loadouts (Save/Load), master Cosmetics ON/OFF, Clear all
- Live spinning preview in the menu (a simple flat blocky figure, not the real 3D model)
- Cape: put a PNG in `config/wabbit-capes/`, press Use. Standard 64x32 / 128x64 / 22x17 cape files use their cape area, any other picture is stretched to fit. Flip button, ON/OFF, 2D preview
- The cape hides while you wear wings or a back item
- Cosmetics are drawn on YOUR player only. Other players cannot see them (there is no server)
- Drawn with Minecraft 1.21.11's custom render queue, so no model or texture files are needed
- Not done: cosmetics on other players, uploading capes from inside the game, animated capes, items that follow elytra/swimming poses (they hide then), custom cosmetic editor (colour choice only)
- Earlier versions unchanged

## 0.8.0 - Spotify lyrics HUD + Backup & Sync (not yet confirmed in-game)
- Media page now holds Donation Alerts, YouTube Links and Spotify Lyrics. Backup & Sync is on the Settings page
- Spotify Lyrics HUD: log in with Spotify (PKCE, no client secret), shows the song and previous / current / upcoming synced lyric lines
  - Lyrics come from LRCLIB (free public lyrics database). Not every song has synced lyrics; the HUD says so
  - Login is copy-link / paste-address, so no local web server is needed. Needs a one-time Spotify developer app (Premium required by Spotify)
  - HUD corner (4 choices) and 3 or 5 lyric lines. Spotify is only asked while the HUD is on or the menu is open
  - Not built: album artwork, free-drag HUD position, resizing
- Backup & Sync (instead of a Wabbit cloud account, which would need a server):
  - Create backup now, keeps the last 10, Restore any of the newest 4 (a safety backup is taken first)
  - Sync folder: point it at a folder your Dropbox/OneDrive/iCloud/Google Drive keeps in sync, then Push / Pull between PCs
  - Warns when both sides changed. Pull always backs up first. Spotify login stays on each PC
  - Not built: accounts, passwords, automatic background sync, merging individual settings
- Earlier stages unchanged

## 0.7.0 - YouTube links (not yet confirmed in-game)
- Right Shift > YouTube > "Open YouTube Links...": paste a video or livestream link (or press Paste) and press Add
- Understands youtube.com/watch, youtu.be, /live/, /shorts/, /embed/ links and bare video ids
- Looks up the title and channel through YouTube's public oEmbed page (no login, no API key) in the background
- Keeps up to 6 links (saved across restarts). Per link: Show (put its title on the HUD), Copy (copies the watch link), X (remove)
- HUD title can be switched off; it also appears in the HUD editor preview
- The sidebar buttons are slightly smaller so all 7 pages fit
- Cannot play video inside Minecraft, tell whether a stream is live, or read scores. Not built: scoreboard HUD, multiple-game tracking
- Earlier stages unchanged

## 0.6.0 - Donation alerts (not yet confirmed in-game)
- Right Shift > Alerts > "Open Donation Alerts...": type a donor name and amount (10M, 250M, 1.5B) and press Trigger alert
- 4 tiers by default: 10M Supporter, 100M Big Donor, 1B Mega Donor, 10B LEGEND. The highest tier your amount reaches is used
- Each tier is editable in-game: minimum amount, message ({name} {amount} {tier}), sound (none, level up, challenge, fireworks, XP orb) and effect
- Effects: Toast (small slide-in), Banner (bordered), Celebration (banner + confetti + screen flash)
- Alerts show on your HUD in game (queued if several arrive); the menu shows a live preview. Sound can be switched off
- Amounts below the lowest tier show a plain small toast
- Alerts are manual only: they are NOT read from chat, a server or a donation site (no known legitimate feed to hook up)
- Not done: Stage 4 cosmetics, Spotify, YouTube tracker, cloud sync, adding or removing tiers, custom sound files
- Earlier stages unchanged

## 0.5.0 - Giveaway Manager + balance HUD (not yet confirmed in-game)
- Right Shift > DonutSMP > "Open Giveaway Manager...". Uses the same balance box as the Advent Calendar
- Options: budget tier (Safe/Balanced/Generous/Large), keep 0/10/25/50% of your balance, 3-20 prizes, max single prize 10-100% of budget
- Generates a random prize pool (rarity + value), biggest first. Pool total never exceeds the budget
- Shows balance, spendable, budget, pool total (% of budget used) and balance after
- Save to history (last 10, shows the latest 4), Clear history, Copy list
- Features > "Balance HUD": shows your typed balance on the HUD (manual only; it does not read the server)
- Not done: live balance tracking, custom alerts, in-game editing of drop chances, Stage 4/5/7 features
- Stages 1-3 and the Advent Calendar unchanged

## 0.4.0 - Christmas RNG Advent Calendar (not yet confirmed in-game)
- Right Shift > DonutSMP > "Open Advent Calendar..."
- Type your balance (2.5B, 750M, 12k, 12000000), pick Safe 5% / Balanced 10% / Generous 20% / Large 35%
- Shows all four budget recommendations, then Generate makes 24 fully random prizes (rarity and value)
- Total prize value never exceeds the budget; shows balance, budget, calendar total, remaining and rarity percentages
- Door 24 gets no special treatment; every Regenerate is a new calendar; duplicates allowed
- 24 clickable doors with open flash, jackpot celebration, snowfall, countdown to Christmas, tree/present/candy cane art
- Reveal all / Hide all / Copy list (copies "Door N: amount (rarity)" and the total to the clipboard)
- The calendar and opened doors are saved, so it survives restarts (open one door per day if you like)
- Drop chances are editable in `config/wabbit-ultimate.json` (`adventChances`, percent)
- Not done: general Giveaway Manager, giveaway history, DonutSMP HUD/balance tracking, in-game chance editor
- Stages 1-3 unchanged

## 0.3.0 - Stage 3: Mod manager (not yet confirmed in-game)
- Right Shift > Mods > "Open Mod Manager...": lists every jar in your mods folder
- Disable/Enable renames the jar to `.jar.disabled` and back. Restart required; nothing is unloaded live
- Refuses to disable Wabbit itself or any mod another enabled mod depends on
- Profiles (up to 5): save the current enabled/disabled state, apply later, bind a profile to a server
- Server detection on join: warns in chat if the bound profile differs from your mods folder
- "Flag" button marks mods you consider risky on the current server; a chat warning shows if flagged mods are loaded
- Not done: automatic profile switching (needs a restart, so it can't be live), built-in restricted-mod lists, per-version mod jars
- Stages 1-2 unchanged

## 0.2.0 - Stage 2: HUD editor (not yet confirmed in-game)
- Features > "Edit HUD Layout...": drag the HUD box anywhere on screen
- HUD background opacity buttons, Reset button
- 3 saved HUD layout slots (Save 1-3 / Load 1-3), stored in the config file
- Not done yet: resizing, per-colour customisation, import/export, extra widgets (none exist yet)
- Stage 1 features unchanged

## 0.1.0 - Stage 1: Foundation (not yet confirmed in-game)
- Fabric project for Minecraft 1.21.11 (client-side only)
- Menu opens with Right Shift (rebindable in Controls > Wabbit Ultimate)
- Sidebar GUI: Dashboard, Features, Settings
- FPS counter and memory usage HUD with toggles
- Dark/light theme, 6 accent colours
- Settings auto-save to `config/wabbit-ultimate.json`
- GitHub Actions builds the jar on every push
