# Changelog

What changed in each Glassio Yahsun release, newest first. `tools/release.sh` publishes a build with its section here as the release notes (the in-app update banner shows them), and it stops if the version has no section: every release gets one.

## 2.4.0 — 2026-10-27

**Glassio Yahsun is now its own app**
- It installs as **com.glassioyahsun.tv**, beside the original Glassio (com.glasslauncher.tv), so both can be on one TV.
- **Moving from 2.3 or earlier:** updating installs Glassio Yahsun as a second app, without your settings, and the old one stays. To move everything over: in the old one, Settings › System › *Back up settings*; open the new one and restore that backup (Settings › System › *Restore a backup*); set the new one as the Home app if the TV asks; then uninstall the old one.

**Stremio**
- **Sign in to your Stremio account** (Settings › Nuvio, Stremio & Home Assistant › *Connect Stremio account*): a QR code, you sign in to Stremio on your phone and confirm; no password on the TV.
- **Its add-ons** can fill the catalogs (*Add-ons from my Stremio account*), alongside the TV's and the Nuvio account's.
- **Continue Watching** includes what's in progress in Stremio; removing a title here rewinds it in Stremio too.
- **The Library** shows your Stremio library, and the + button can save to it.
- **Open titles in Stremio**: a choice under *Open titles in*.

**Continue Watching**
- Each card is tagged **Nuvio** or **Stremio**, and OK opens it in that app, whatever *Open titles in* says.
- **Hold OK on a card**: open it in your other app, mark it watched where it came from (your Nuvio account, Trakt, or a film in Stremio), or take it out of the row.
- A Nuvio series shows the furthest episode you've reached, as Nuvio resumes it, not an old episode you happened to start again.

**Library**
- **A tab for each list** (Saved, Nuvio, Stremio, Trakt), only for the lists you use; it reopens on the tab you last had.
- Titles in the Nuvio and Stremio tabs open in the app they came from.

**Live TV**
- **Designs instead of themes**: Apple TV, YouTube TV, NOW, Sky, Sling, Hulu, Netflix, Prime Video, Disney+, Max, Plex and Pluto TV each bring their whole look: colors, focus, corners, guide, info bar and lettering. Netflix, Prime Video, Disney+, Max, Plex and Pluto TV have guides of their own.
- **Custom design**: pick the colors, focus, corners, guide, info bar and lettering from any design, with a picture of each choice. Your old Custom settings become the closest designs; design codes are 8 digits now.
- The channel list and search take their look from the design too.
- Design pickers show three a row, and **Design** and **Start-up** are separate sections in Settings.
- The guide's country tag sits at the bottom right of the channel, under the catch-up mark.
- **Episodes as a season and number chip** ("S5 · E8") with the title beside it, in the guide, its details, the info bar and the show's page; the synopsis no longer repeats the line.
- **Episode numbers from TMDB** for shows the guide sends only by title (matched by the episode's name, allowing for small differences in spelling), and Up next in the info bar shows the next episode's chip.
- **Public logos load faster**: each channel's match is kept, and the weekly check downloads the database again only when it has changed. Logo pictures are kept when the TV clears its caches (up to 80 MB), and backups leave them out.

## 2.3.0 — 2026-10-27

**Country tags and voice**
- **Country tags**: a channel's country (US, UK, CA…) on a small glass pill beside its name — in the guide, the channel list, the info bar, the categories and the home screen's cards — read from the name ("US: TLC", "TLC (US)", "TLC USA", a flag) or else its category ("UK | Sky Sports"), and the name shown without it. On or off in Live TV › Settings › Channels and categories.
- **Channels by voice**: "Play TLC USA on Glassio Yahsun", "TLC US", "channel 2340" open Live TV on that channel; a country at either end picks that country's channel, and without one the channels you watch win. Live TV's channels can also show in the TV's own search (when Google TV lists apps' results there). On or off in the same section: off, Glassio isn't offered to the TV for this at all.
- **Voice inside Live TV** (the remote's mic button, or the mic in Search): "TLC USA" or "302" tunes that channel at once (with Channels by voice on), and "watch …" always does; a country said picks that country's channel.
- **Favorites by voice**: "Add TLC to favourites", "Remove TLC from my favorites", "favourite Sky News", "unfavorite TLC" (and in Portuguese, "adicionar Globo aos favoritos").
- **Arabic channels**: providers mark them AR, which is Argentina's code; they're now tagged ARABIC (also from ARB, ARAB, ARABIC), unless the name says Argentina. Saying "Arabic" picks them by voice.
- Every setting in Live TV that's on or off is now a switch, as in Glassio's own settings (purple when on), in the sections and in their preview.

**Logos**
- **Channel logos** (Live TV › Settings › Channels and categories): from your provider, the TV guide (EPG), or the **public database** (iptv-org: sharp logos for over 30,000 channels, found by each channel's name and country; about 14 MB fetched once a week). The other sources fill in what the chosen one doesn't have, and a logo that can't be loaded falls back to the provider's.
- **A logo per channel**: hold OK on a channel (in the guide, or in the channel list) › Logo: as in Settings, the provider's, the TV guide's or the public database's, for that channel alone.
- **Better public matches**: the database's other names for each channel, and a guide that uses iptv-org's channel ids (TLC.us) gets that exact channel's logo.
- **The home screen's Live TV cards** show the same logos as Live TV (public and guide logos included). Pictures from Wikimedia, where most public logos live, now load on the home screen (it refused Glassio's picture loader before, leaving some cards without a logo).
- **Logos and names in the guide** (Live TV › Settings › Guide): lined up at the left of the channel column (the new default), or in the middle as before.
- **Logo in the player** (Live TV › Settings › Theme and start-up): steady, centered in its own space, so it no longer moves when a show has little or no synopsis (the new default), or centered on the text beside it as before.

**Good to know**
- Google TV's own voice search ("Watch TLC" said to Google) treats a channel name as one of the TV's tuner channels and doesn't ask apps. Naming Glassio ("Play TLC USA on Glassio Yahsun") asks Google to pass the request to Glassio, which then tunes the channel; inside Live TV, the remote's mic button always reaches Glassio.

## 2.2.0 — 2026-10-26

**Backup and restore**
- **Back up settings** (Settings › System): everything in one file, sign-ins included (Nuvio, Trakt, TMDB, Live TV's playlists and logins), your app icons, Live TV's favorites, Following, profiles and reminders, and Glassio's TV access, so no new "Allow USB debugging" after a restore. Locked with a code you choose (at least 6 characters); without the code the file can't be opened.
- The backup goes to the TV's Downloads, which an uninstall leaves alone, and to your phone from Manage from phone (Download a backup).
- **Restore a backup** (Settings › System, or Restore on the TV from the phone page): Glassio's settings are replaced and it restarts by itself. After a reinstall it reads the backup from Downloads through TV access, or you send it from your phone.
- Recordings and the TV guide stay out of the backup (Live TV reads the guide again).

**Watch on Home**
- The small window is selected as it opens: OK goes full screen in Live TV, Up / Down (or the remote's channel buttons) change the channel, Left goes back to the home screen. Back selects the window again, and Back on it closes it.
- Its video has rounded corners, like its frame.

**Live TV**
- The TV guide shows each channel's number before its name ("2340  TLC").
- Back in the guide goes straight back to the channel, full screen (before anything plays, it still opens the categories). Back in the categories goes back to the guide.
- A channel changed in the small window (Watch on Home) is the one watched last from then on: the home screen's Live TV card, History, and Live TV's next start follow it.
- **Record from the home screen**: hold OK on a Live TV card for Record this show (or Stop recording) and Remove from Continue Watching.
- **Kick-off alert**: when a team you follow starts playing while the home screen is in front, a glass card under the weather offers Watch, Small window or Not now. Once per match; it goes after half a minute.

**Backup**
- Settings › System says when the last backup was made, and marks Back up settings "Due" when there's none or it's over a month old.

**For developers**
- Tests for the backup's lock (right code, wrong code, a changed or cut-short file) and the kick-off timing run on every release; `tools/release.sh` stops when one fails.
- A GitHub Actions workflow builds, tests, signs and publishes a release when a version tag is pushed (it needs the release key as repository secrets).
- Screenshots of Live TV, Following, the styles and backup in the README.

**Live TV styles**
- Six new styles (Live TV › Settings › Theme and start-up › Style): **Netflix** (near black, a red focus, nearly square corners, a big picture), **Prime Video** (deep navy, a bright blue focus), **Disney+** (royal blue into night, glass with a light edge, a blue focus), **Max** (black into deep blue, the large picture at the right), **Plex** (dark grey, an amber focus) and **Pluto TV** (black, a yellow focus, a guide with many rows). Each also works for the morning, night and late-night styles.
- The style picker shows the 14 styles in two full rows (Custom last), so every tile is large enough to read.

**Polish**
- After a fresh install the home screen lists the steps to set Glassio up, each with where it stands (restore a backup, catalogs, Nuvio, TV access, the Home button, Live TV) and OK to go there.
- Live TV's lists: the selected row is a rounded plate inside the card, as in Glassio's own lists.
- Codes typed in Glassio's own dialogs (a backup's) show as dots.
- Fixed: a page asked for as Glassio started (after an update, say) could close again by itself. Google TV's own home screen shows for a moment while Glassio starts, and the Home-button service's fallback answered it with Home a split second later.

## 2.1.1 — 2026-10-26

**Install once by hand**: from this version, Glassio Yahsun is signed with its own release key instead of a development one. If you have 2.1.0, uninstall it and install 2.1.1 (updates work again after that). Uninstalling clears Glassio's settings and Live TV's sign-in, so note your playlist details first.

- Releases are signed with Glassio Yahsun's own key, and the release script refuses any APK signed with another one.
- Tidied what was left from porting Live TV: its developer test commands are documented as they work in Glassio, and unused code is gone.

## 2.1.0 — 2026-10-26

The first release of Glassio Yahsun: it also brings everything in 2.0.0 (Live TV, Following, Continue Watching in its own row; see [CHANGELOG.md](https://github.com/GizmoH2o/glassio-yahsun/blob/main/CHANGELOG.md)).

**Live TV on the home screen**
- **Live TV in Continue Watching**: the channel you watched last leads the row, with what's on it now (its TMDB picture, the channel logo, a red LIVE tag and how far into the show). OK opens Live TV on it.
- **Followed matches in Continue Watching**: while a team you follow is playing, its match comes first in the row, with the score. OK goes straight to the channel.
- **Recordings in Continue Watching**: your three newest finished recordings (from the last 14 days). OK plays one in Live TV. Hold OK on any Live TV card to take it out of the row.
- **Watch on Home** (Live TV's menu): the channel keeps playing in a small glass window under the weather while you browse the home screen. Back closes it; opening Live TV again goes on full screen. The screensaver waits while it plays.

**Live TV**
- Menus and dialogs in Glassio's style are Glassio's dark glass with its light edge and the white focus (the other styles — Apple TV, YouTube TV, NOW, Sky, Sling, Hulu, Custom — keep their own look).
- Typing (signing in, the guide's link, Multiview's search) uses a Glassio card: a frosted field, a white caret, OK and Cancel on the white plate, kept above the TV keyboard. Passwords stay hidden as you type.
- The sign-in screen's rows are glass, like the rest of Live TV.
- Fixed: adding a playlist from Settings went to a black screen instead of the sign-in screen.
- Removed the "What's new in Live TV" card (Free My TV's own notes).

**Updates**
- Updates now come from this repository's releases (github.com/GizmoH2o/glassio-yahsun).

## 2.0.0 — Glassio Yahsun

The first version under the new name (never published on its own; 2.1.0 is the first release).

- **Live TV**: an IPTV player for Xtream Codes, M3U links and MAG portals, with a full guide, favorites, pause and rewind, catch-up, recordings, Multiview, profiles (with a Kids one), reminders and live scores, in its own process. Its default look is Glassio's own. Ported from [Zero](https://github.com/GizmoH2o/zero-tv) (MIT).
- **Live TV page** and **Following** in the menu: follow a team or a show; its next match shows in a spotlight with the live score, then what's coming up. TMDB pictures for the shows.
- **Continue Watching from the official Nuvio app** (signed in to your Nuvio account).
- **Continue Watching in its own row**, as Apple TV's Up Next, along the bottom of the page; the apps scroll up under it.
- **Followed matches on the screensaver**, in Glassio's screensaver and the system one.
- **Back to Glassio after a restart** (Settings → System → *Open Glassio when the TV starts*).
- New name, icon and banner.
- Faster home screen: slide changes no longer redraw the whole screen, a pre-painted shade under the row, less work for accessibility when no screen reader is on.
- Needs Android 9 or newer.

## Before 2.0

Glassio Yahsun is a fork of [Glassio](https://github.com/mohammadmrh12/glassio) 1.9.8; its README lists what came before.
